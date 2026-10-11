package spoonbillprobe
import java.time.Instant
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.auth.BrowserOwnership
import spoonbill.effect.Effect
import spoonbill.security.jdbc.*
object ProviderAdapter {
  def jdbc(source: DataSource, clockSource: DataSource, context: ExecutionContext, realm: String,
    clock: () => Instant, host: JdbcHostSessions[String], vault: JdbcCredentialVault)(using Effect[Future]) =
    new JdbcBrowserPersistence[Future, String](source, context, realm, "session", clock, host, vault,
      ownership = BrowserOwnership.ExclusiveScope, clockSource = Some(clockSource))
}
