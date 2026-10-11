import avocet.dsl.*
import avocet.dsl.html.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import spoonbill.Context
import spoonbill.pekko.{PekkoHttpServerConfig, pekkoHttpService}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Resolved from publication metadata, never dependsOn the repository projects. */
object NativePekkoConsumer {
  def main(args: Array[String]): Unit = {
    val status = scala.util.Try(verify()).fold(error => { error.printStackTrace(); 1 }, _ => 0)
    // The existing library-wide Scheduler owns a non-daemon Timer. End this
    // isolated publication probe only after HTTP/actor cleanup and assertions.
    sys.exit(status)
  }

  private def verify(): Unit = {
    val typed = ActorSystem[Nothing](Behaviors.empty, "native-pekko-consumer")
    implicit val classic: org.apache.pekko.actor.ActorSystem = typed.classicSystem
    implicit val ec: ExecutionContext = typed.executionContext
    implicit val materializer: Materializer = SystemMaterializer(typed).materializer
    val ctx = Context[Future, String, Any]
    import ctx.*
    val document: ctx.Render = state => Html(body(div(state)))
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("published native Scala 3"), document = document
    )
    try {
      require(Persistence(classic) != null)
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0)
        .bind(pekkoHttpService(config).apply(PekkoHttpServerConfig())), 10.seconds)
      try {
        val response = Await.result(Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:${binding.localAddress.getPort}/")), 10.seconds)
        require(response.status == StatusCodes.OK)
        val html = Await.result(response.entity.toStrict(5.seconds), 10.seconds).data.utf8String
        require(html.contains("published native Scala 3"))
      } finally Await.result(binding.terminate(2.seconds), 10.seconds)
    } finally {
      typed.terminate()
      Await.result(typed.whenTerminated, 10.seconds)
    }
  }
}
