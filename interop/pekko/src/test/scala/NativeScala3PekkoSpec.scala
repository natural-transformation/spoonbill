package spoonbill.pekko

import avocet.dsl.*
import avocet.dsl.html.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Context
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*

/**
 * A consumer can combine native Scala 3 typed/classic, persistence and HTTP
 * with the official adapter, without exclusions or a copied HTTP bridge.
 */
final class NativeScala3PekkoSpec extends AnyFreeSpec with Matchers {
  "native Scala 3 Pekko modules coexist with the official service" in {
    val typed                                                = ActorSystem[Nothing](Behaviors.empty, "native-scala3-adapter")
    implicit val classic: org.apache.pekko.actor.ActorSystem = typed.classicSystem
    implicit val ec: ExecutionContext                        = typed.executionContext
    implicit val materializer: Materializer                  = SystemMaterializer(typed).materializer
    val ctx                                                  = Context[Future, String, Any]
    import ctx.*
    val document: ctx.Render = state => Html(body(div(state)))
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("native Scala 3"),
      document = document
    )
    try {
      Persistence(classic) should not be null
      val binding = Await.result(
        Http()
          .newServerAt("127.0.0.1", 0)
          .bind(pekkoHttpService(config).apply(PekkoHttpServerConfig())),
        10.seconds
      )
      try {
        val response = Await.result(
          Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:${binding.localAddress.getPort}/")),
          10.seconds
        )
        response.status shouldBe StatusCodes.OK
        Await.result(response.entity.toStrict(5.seconds), 10.seconds).data.utf8String should include("native Scala 3")
      } finally Await.result(binding.terminate(2.seconds), 10.seconds)
    } finally {
      typed.terminate()
      Await.result(typed.whenTerminated, 10.seconds)
    }
  }
}
