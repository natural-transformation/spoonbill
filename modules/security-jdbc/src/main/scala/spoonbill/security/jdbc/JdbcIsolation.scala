package spoonbill.security.jdbc

import java.io.PrintWriter
import java.sql.{Connection, ConnectionBuilder, ShardingKeyBuilder}
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Explicit isolation certification for the data sources that Spoonbill's JDBC
 * adapters open transactions on.
 *
 * Every Spoonbill transaction runs at READ COMMITTED. By default each one calls
 * setTransactionIsolation first. The PostgreSQL driver sends a SET for that
 * call even when the session already has the level, so every transaction pays
 * one extra round trip. A deployment whose pool establishes READ COMMITTED on
 * every checkout can certify that and save the round trip.
 */
object JdbcIsolation {

  /**
   * Certify that the pool behind `source` establishes READ COMMITTED on every
   * checkout: on a new physical connection, after a reset or a replacement, and
   * on reuse after another borrower changed the level. Spoonbill's transactions
   * on the returned source then neither set nor read the isolation level.
   *
   * Apply it only after verifying the pool's checkout policy. A one-time startup
   * query cannot establish how a pool treats reused connections, so Spoonbill
   * does not check it. Pass the returned source to Spoonbill directly: another
   * wrapper around it hides the certification, and the ordinary path then sets
   * the level as before. Certify a provider's work source and a separate clock
   * source independently. A caller-supplied transaction runner owns its own
   * transaction setup and is not affected.
   */
  def readCommittedOnCheckout(source: DataSource): DataSource = source match {
    case certified: ReadCommittedOnCheckout => certified
    case other                              => new ReadCommittedOnCheckout(other)
  }

  /** True when transactions on `source` may skip the isolation setter. */
  private[jdbc] def certified(source: DataSource): Boolean = source.isInstanceOf[ReadCommittedOnCheckout]

  /**
   * A delegating source whose only behavior is its type, the certification.
   * Both acquisition overloads and the builders go to the pool unchanged.
   */
  private final class ReadCommittedOnCheckout(delegate: DataSource) extends DataSource {
    def getConnection: Connection                                  = delegate.getConnection
    def getConnection(user: String, password: String): Connection = delegate.getConnection(user, password)
    override def createConnectionBuilder(): ConnectionBuilder     = delegate.createConnectionBuilder()
    override def createShardingKeyBuilder(): ShardingKeyBuilder   = delegate.createShardingKeyBuilder()
    def getLogWriter: PrintWriter                                  = delegate.getLogWriter
    def setLogWriter(out: PrintWriter): Unit                       = delegate.setLogWriter(out)
    def setLoginTimeout(seconds: Int): Unit                        = delegate.setLoginTimeout(seconds)
    def getLoginTimeout: Int                                       = delegate.getLoginTimeout
    def getParentLogger: Logger                                    = delegate.getParentLogger

    // Standard JDBC unwrapping: this source, the pool it wraps, then the pool's own chain.
    def unwrap[T](kind: Class[T]): T =
      if (kind.isInstance(this)) kind.cast(this)
      else if (kind.isInstance(delegate)) kind.cast(delegate)
      else delegate.unwrap(kind)
    def isWrapperFor(kind: Class[?]): Boolean =
      kind.isInstance(this) || kind.isInstance(delegate) || delegate.isWrapperFor(kind)
  }
}
