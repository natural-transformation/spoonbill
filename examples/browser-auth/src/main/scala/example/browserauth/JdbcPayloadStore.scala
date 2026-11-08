package example.browserauth

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.recovery.*

/**
 * The example's payload adapter: an application-owned PostgreSQL table used as
 * an external store. It is deliberately not the provider's schema and not a
 * provider transaction; staging and reference publication stay separate
 * operations. Deletion leaves a tombstone row that a later put of the same
 * reference refuses under the same row lock, so the store declares a durable
 * retirement fence. Tombstones are never reclaimed here; a real adapter must
 * state its own retention of fence markers.
 */
final class JdbcPayloadStore(source: DataSource, blockingContext: ExecutionContext) extends SnapshotPayloadStore[Future] {
  def cleanupBasis: CleanupBasis = CleanupBasis.DurableRetirementFence

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
          unresolved
      }
    }
  }(blockingContext)

  def put(ref: PayloadRef, bytes: SnapshotBytes): Future[PayloadWrite] = transaction[PayloadWrite](PayloadWrite.Uncertain) {
    connection =>
      val existing = Using.resource(connection.prepareStatement("SELECT retired,bytes FROM example_payload WHERE id=? FOR UPDATE")) {
        query =>
          query.setObject(1, ref.id)
          Using.resource(query.executeQuery())(rows => if (rows.next()) Some((rows.getBoolean(1), Option(rows.getBytes(2)))) else None)
      }
      existing match {
        case Some((true, _))                                                     => PayloadWrite.Retired
        case Some((false, Some(stored))) if java.util.Arrays.equals(stored, bytes.toArray) => PayloadWrite.Durable
        case Some((false, _))                                                    => PayloadWrite.Conflict
        case None =>
          Using.resource(
            connection.prepareStatement("INSERT INTO example_payload(id,digest,length,bytes,retired) VALUES (?,?,?,?,FALSE)")
          ) { query =>
            query.setObject(1, ref.id); query.setBytes(2, ref.digest.toArray); query.setInt(3, ref.length)
            query.setBytes(4, bytes.toArray); query.executeUpdate()
          }
          PayloadWrite.Durable
      }
  }

  def get(ref: PayloadRef): Future[PayloadRead] = Future {
    try
      Using.resource(source.getConnection) { connection =>
        Using.resource(connection.prepareStatement("SELECT bytes FROM example_payload WHERE id=? AND NOT retired AND octet_length(bytes)<=?")) {
          query =>
            query.setObject(1, ref.id); query.setInt(2, SnapshotBytes.MaximumLength)
            Using.resource(query.executeQuery()) { rows =>
              if (rows.next()) PayloadRead.Found(SnapshotBytes.copyOf(rows.getBytes(1))) else PayloadRead.Missing
            }
        }
      }
    catch { case NonFatal(_) => PayloadRead.Unavailable }
  }(blockingContext)

  /** One statement writes the tombstone and drops the bytes; a concurrent put waits on the row and then sees it. */
  def delete(ref: PayloadRef): Future[PayloadDelete] = transaction[PayloadDelete](PayloadDelete.Uncertain) { connection =>
    Using.resource(
      connection.prepareStatement(
        """INSERT INTO example_payload(id,digest,length,bytes,retired) VALUES (?,?,?,NULL,TRUE)
        ON CONFLICT (id) DO UPDATE SET bytes=NULL,retired=TRUE"""
      )
    ) { query =>
      query.setObject(1, ref.id); query.setBytes(2, ref.digest.toArray); query.setInt(3, ref.length); query.executeUpdate()
    }
    PayloadDelete.FinalDeleted
  }
}

object JdbcPayloadStore {
  val schema: Vector[String] = Vector(
    """CREATE TABLE IF NOT EXISTS example_payload(id UUID PRIMARY KEY,digest BYTEA NOT NULL,length INTEGER NOT NULL,
      bytes BYTEA,retired BOOLEAN NOT NULL DEFAULT FALSE)"""
  )
}
