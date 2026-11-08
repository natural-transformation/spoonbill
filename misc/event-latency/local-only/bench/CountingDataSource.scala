package bench

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Provider transactions, classified by the SQL each committed transaction
 * executed. The recovery plan's commit budget counts snapshot-provider
 * transactions: one for an inline save, two (reserve, publish) for a
 * reference-backed save. Every other transaction the provider commits is
 * reported beside them, never mixed into that count.
 */
final class TransactionCounters {
  /** Wrote a snapshot row, a reservation or a publication (the fenced authority check runs inside it). */
  val snapshotSave = new AtomicLong()
  /** Read snapshot metadata, a reference, a pin or an operation status without such a write. */
  val snapshotRead = new AtomicLong()
  /** Garbage claims, retries and completions of retired references. */
  val cleanup = new AtomicLong()
  /** Durable clock checkpoints. */
  val clock = new AtomicLong()
  /** Authority checks, claims, captures, ceremonies and retention: everything else. */
  val authority      = new AtomicLong()
  val rollbacks      = new AtomicLong()
  val statements     = new AtomicLong()
  val saveStatements = new AtomicLong()

  def committed(kind: Int, statementCount: Int): Unit = {
    kind match {
      case TransactionCounters.Save    => snapshotSave.incrementAndGet(); saveStatements.addAndGet(statementCount.toLong)
      case TransactionCounters.Cleanup => cleanup.incrementAndGet()
      case TransactionCounters.Read    => snapshotRead.incrementAndGet()
      case TransactionCounters.Clock   => clock.incrementAndGet()
      case _                           => authority.incrementAndGet()
    }
    ()
  }

  def json: String =
    s"""{"snapshotSave":${snapshotSave.get},"snapshotRead":${snapshotRead.get},"cleanup":${cleanup.get},""" +
      s""""clock":${clock.get},"authority":${authority.get},"rollbacks":${rollbacks.get},""" +
      s""""statements":${statements.get},"saveStatements":${saveStatements.get}}"""
}

object TransactionCounters {
  // Ranks: a transaction takes the highest rank of any statement it ran.
  val Authority = 0
  val Clock     = 1
  val Read      = 2
  val Cleanup   = 3
  val Save      = 4

  /** The provider's SQL text is the classifier; see JdbcViewSnapshots and JdbcSnapshotReferences. */
  def classify(sql: String): Int =
    if (sql.contains("INSERT INTO spoonbill_view_snapshot") || sql.contains("INSERT INTO spoonbill_snapshot_reference")) Save
    else if (sql.contains("claim_id=") || sql.contains("deleted_at=?") || sql.contains("reservation_deadline<=")) Cleanup
    else if (
      sql.contains("spoonbill_view_snapshot") || sql.contains("spoonbill_snapshot_reference") ||
      sql.contains("spoonbill_snapshot_read_pin")
    ) Read
    else if (sql.contains("spoonbill_auth_clock")) Clock
    else Authority
}

/**
 * A data source whose connections count and classify the transactions
 * committed through them. The provider sees only this wrapper; the pool
 * underneath is shared with the payload store, which keeps its own counters.
 */
final class CountingDataSource(delegate: DataSource) extends DataSource {
  val counters = new TransactionCounters

  def getConnection: Connection = CountingDataSource.counted(delegate.getConnection, counters)
  def getConnection(user: String, password: String): Connection =
    CountingDataSource.counted(delegate.getConnection(user, password), counters)
  def getLogWriter: PrintWriter                 = delegate.getLogWriter
  def setLogWriter(out: PrintWriter): Unit      = delegate.setLogWriter(out)
  def setLoginTimeout(seconds: Int): Unit       = delegate.setLoginTimeout(seconds)
  def getLoginTimeout: Int                      = delegate.getLoginTimeout
  def getParentLogger: Logger                   = delegate.getParentLogger
  def unwrap[T](iface: Class[T]): T             = delegate.unwrap(iface)
  def isWrapperFor(iface: Class[?]): Boolean    = delegate.isWrapperFor(iface)
}

object CountingDataSource {
  def counted(raw: Connection, counters: TransactionCounters): Connection =
    Proxy
      .newProxyInstance(classOf[CountingDataSource].getClassLoader, Array[Class[?]](classOf[Connection]), new Handler(raw, counters))
      .asInstanceOf[Connection]

  /** One handler per connection; a connection is used by one thread at a time. */
  private final class Handler(raw: Connection, counters: TransactionCounters) extends InvocationHandler {
    private var kind       = TransactionCounters.Authority
    private var statements = 0
    private def reset(): Unit = { kind = TransactionCounters.Authority; statements = 0 }

    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
      method.getName match {
        case "prepareStatement" | "prepareCall" =>
          counters.statements.incrementAndGet()
          statements += 1
          kind = math.max(kind, TransactionCounters.classify(args(0).asInstanceOf[String]))
        case "commit"   => counters.committed(kind, statements); reset()
        case "rollback" => counters.rollbacks.incrementAndGet(); reset()
        case _          => ()
      }
      val arguments = if (args == null) Array.empty[AnyRef] else args
      try method.invoke(raw, arguments*)
      catch { case error: InvocationTargetException => throw error.getCause }
    }
  }
}
