package bench

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.pekko.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*

/** Unguarded counter app on the Pekko adapter. Usage: PlainMain <port>. */
object PlainMain {
  def main(args: Array[String]): Unit = {
    val port = args.headOption.fold(8080)(_.toInt)
    given system: ActorSystem   = ActorSystem("bench-plain")
    given ExecutionContext      = system.dispatcher
    given Materializer          = Materializer(system)
    val config = SpoonbillServiceConfig[Future, Counter, Any](
      stateLoader = StateLoader.default(Counter(0)),
      document = CounterPage.document
    )
    val route = pekkoHttpService(config).apply(PekkoHttpServerConfig())
    Http().newServerAt("127.0.0.1", port).bindFlow(route).foreach { _ =>
      println(s"bench-plain ready on $port rows=${CounterPage.rows}")
    }
  }
}
