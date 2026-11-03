package bench

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.auth.*
import spoonbill.security.jdbc.*

/**
 * The counter app behind the JDBC provider with concurrent checks enabled.
 * Usage: ConcurrentMain <maxConcurrentChecks> <port>. Only the working-tree
 * variant has the opt-in, so this main lives outside the shared guarded sources.
 */
object ConcurrentMain {
  def main(args: Array[String]): Unit = {
    val Array(capArg, portArg) = args
    val cap                    = capArg.toInt
    GuardedMain.serve(s"jdbc-concurrent-$cap", portArg.toInt)(
      executor => {
        given ExecutionContext = executor
        val setup = GuardedMain.jdbcSetup()
        // The reserved clock source: its own small pool with a bounded wait.
        val clock = new HikariConfig()
        clock.setJdbcUrl(sys.env("SPOONBILL_JDBC_TEST_URL"))
        clock.setMaximumPoolSize(2)
        clock.setConnectionTimeout(5000)
        val clockSource = new HikariDataSource(clock)
        GuardedMain.initialized(
          setup,
          new JdbcBrowserPersistence[Future, String](
            setup.source,
            setup.blocking,
            GuardedMain.scope.realm,
            GuardedMain.scope.cookieNamespace,
            () => Instant.now(),
            setup.host,
            setup.vault,
            BrowserOwnership.ExclusiveScope,
            concurrentChecks = Some(JdbcConcurrentChecks(clockSource, cap))
          )
        )
      },
      stats = browser => {
        val s = browser.coordinatorStatistics
        s"""{"runningReads":${s.runningReads},"queuedReads":${s.queuedReads},"queuedWrites":${s.queuedWrites},""" +
          s""""bindingWaits":${s.bindingWaits},"rejections":${s.rejections},"writerWaitMs":${s.writerWaitNanos / 1000000}}"""
      }
    )
  }
}
