package bench

import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.recovery.*

/**
 * The harness's payload adapter, modelled on the browser-auth example's
 * JdbcPayloadStore: a bench-only PostgreSQL table used as an external store.
 * It is neither the provider's schema nor a provider transaction; staging and
 * reference publication stay separate operations. A delete leaves a tombstone
 * that a later put of the same reference refuses under the same row lock, so
 * the store declares a durable retirement fence. Tombstones are never
 * reclaimed here. `delayMs` adds a fixed pause to every put and get, to model
 * a store slower than the database the provider uses.
 */
final class BenchPayloadStore(source: DataSource, blockingContext: ExecutionContext, delayMs: Double)
    extends SnapshotPayloadStore[Future] {
  val puts        = new AtomicLong()
  val gets        = new AtomicLong()
  val deletes     = new AtomicLong()
  val putBytes    = new AtomicLong()
  val getBytes    = new AtomicLong()
  val conflicts   = new AtomicLong()
  val retired     = new AtomicLong()
  val missing     = new AtomicLong()
  val unavailable = new AtomicLong()

  def cleanupBasis: CleanupBasis = CleanupBasis.DurableRetirementFence

  private def pause(): Unit = if (delayMs > 0) LockSupport.parkNanos((delayMs * 1000000).toLong)

  private def transaction[A](unresolved: A)(body: Connection => A): Future[A] = Future {
    Using.resource(source.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        val result = body(connection)
        connection.commit()
        result
      } catch {
        case NonFatal(_) =>
          try connection.rollback()
          catch { case NonFatal(_) => () }
          unavailable.incrementAndGet()
          unresolved
      }
    }
  }(blockingContext)

  def put(ref: PayloadRef, bytes: SnapshotBytes): Future[PayloadWrite] = {
    puts.incrementAndGet(); putBytes.addAndGet(bytes.length.toLong)
    transaction[PayloadWrite](PayloadWrite.Uncertain) { connection =>
      pause()
      val existing =
        Using.resource(connection.prepareStatement("SELECT retired,bytes FROM bench_payload WHERE id=? FOR UPDATE")) { query =>
          query.setObject(1, ref.id)
          Using.resource(query.executeQuery())(rows => if (rows.next()) Some((rows.getBoolean(1), Option(rows.getBytes(2)))) else None)
        }
      existing match {
        case Some((true, _)) => retired.incrementAndGet(); PayloadWrite.Retired
        case Some((false, Some(stored))) if java.util.Arrays.equals(stored, bytes.toArray) => PayloadWrite.Durable
        case Some((false, _)) => conflicts.incrementAndGet(); PayloadWrite.Conflict
        case None =>
          Using.resource(
            connection.prepareStatement("INSERT INTO bench_payload(id,digest,length,bytes,retired) VALUES (?,?,?,?,FALSE)")
          ) { query =>
            query.setObject(1, ref.id); query.setBytes(2, ref.digest.toArray); query.setInt(3, ref.length)
            query.setBytes(4, bytes.toArray); query.executeUpdate()
          }
          PayloadWrite.Durable
      }
    }
  }

  def get(ref: PayloadRef): Future[PayloadRead] = {
    gets.incrementAndGet()
    Future {
      try
        Using.resource(source.getConnection) { connection =>
          pause()
          Using.resource(
            connection.prepareStatement("SELECT bytes FROM bench_payload WHERE id=? AND NOT retired AND octet_length(bytes)<=?")
          ) { query =>
            query.setObject(1, ref.id); query.setInt(2, SnapshotBytes.MaximumLength)
            Using.resource(query.executeQuery()) { rows =>
              if (rows.next()) {
                val found = rows.getBytes(1)
                getBytes.addAndGet(found.length.toLong)
                PayloadRead.Found(SnapshotBytes.copyOf(found))
              } else { missing.incrementAndGet(); PayloadRead.Missing }
            }
          }
        }
      catch { case NonFatal(_) => unavailable.incrementAndGet(); PayloadRead.Unavailable }
    }(blockingContext)
  }

  /** One statement writes the tombstone and drops the bytes; a concurrent put waits on the row and then sees it. */
  def delete(ref: PayloadRef): Future[PayloadDelete] = {
    deletes.incrementAndGet()
    transaction[PayloadDelete](PayloadDelete.Uncertain) { connection =>
      Using.resource(
        connection.prepareStatement(
          """INSERT INTO bench_payload(id,digest,length,bytes,retired) VALUES (?,?,?,NULL,TRUE)
          ON CONFLICT (id) DO UPDATE SET bytes=NULL,retired=TRUE"""
        )
      ) { query =>
        query.setObject(1, ref.id); query.setBytes(2, ref.digest.toArray); query.setInt(3, ref.length); query.executeUpdate()
      }
      PayloadDelete.FinalDeleted
    }
  }

  def json: String =
    s"""{"puts":${puts.get},"gets":${gets.get},"deletes":${deletes.get},"putBytes":${putBytes.get},""" +
      s""""getBytes":${getBytes.get},"conflicts":${conflicts.get},"retiredWrites":${retired.get},""" +
      s""""missing":${missing.get},"unavailable":${unavailable.get}}"""
}

object BenchPayloadStore {
  val schema: Vector[String] = Vector(
    """CREATE TABLE IF NOT EXISTS bench_payload(id UUID PRIMARY KEY,digest BYTEA NOT NULL,length INTEGER NOT NULL,
      bytes BYTEA,retired BOOLEAN NOT NULL DEFAULT FALSE)"""
  )
}
