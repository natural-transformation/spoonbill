package spoonbill.akka

import avocet.dsl.*
import avocet.dsl.html.*
import com.typesafe.config.ConfigFactory
import java.util.UUID
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.headers.Cookie
import akka.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*

/**
 * A long poll that has nothing to deliver waits on the server, and Akka HTTP
 * ends a request after its request timeout with `503` (20 s by default, 1 s
 * here). The poll route must outlast the poll wait, so that a quiet page gets
 * its `204` and not a failed poll.
 */
final class AkkaLongPollWaitSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem =
    ActorSystem("long-poll-wait-spec", ConfigFactory.parseString("akka.http.server.request-timeout = 1s").withFallback(ConfigFactory.load()))
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext       = system.dispatcher
  private val timeout                             = 20.seconds

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  private val device = Cookie("deviceId" -> "long-poll-wait")

  private def get(uri: String): (HttpResponse, String, Long) = {
    val started  = System.nanoTime()
    val response = Await.result(Http().singleRequest(HttpRequest(uri = uri, headers = List(device))), timeout)
    val body     = Await.result(response.entity.toStrict(timeout).map(_.data.utf8String), timeout)
    (response, body, (System.nanoTime() - started) / 1000000L)
  }

  "A quiet long poll" - {
    "is answered with 204 at the end of the poll wait, not with 503 at the request timeout" in {
      val config = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default("quiet"),
        document = state => Html(body(p(state))),
        stateStorage = StateStorage.ephemeral[Future, String](16),
        // A poll waits at most half the idle timeout: 2 s here, above the 1 s
        // request timeout of this server.
        sessionIdleTimeout = 4.seconds,
        heartbeatLimit = None
      )
      val route   = akkaHttpService(config).apply(AkkaHttpServerConfig())
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      try {
        val base          = s"http://127.0.0.1:${binding.localAddress.getPort}"
        val (rendered, html, _) = get(base + "/")
        val session = "sid:'([^']+)'".r.findFirstMatchIn(html).map(_.group(1))
          .getOrElse(fail(s"No session id in the page: ${rendered.status} ${html.take(300)}"))
        val page          = UUID.randomUUID()
        var seen          = 0L
        var quiet         = Option.empty[(Int, Long)]
        while (quiet.isEmpty && seen < 20) {
          val (response, _, elapsed) = get(s"$base/bridge/long-polling/$session/subscribe?page=$page&seen=$seen")
          val frame = response.headers.find(_.is("spoonbill-frame")).map(_.value().toLong)
          if (response.status.intValue == 200 && frame.nonEmpty) seen = frame.get
          else quiet = Some(response.status.intValue -> elapsed)
        }
        val (status, elapsed) = quiet.getOrElse(fail("The page never had every frame"))
        withClue(s"the quiet poll after $elapsed ms: ") {
          status shouldBe 204
          elapsed should be >= 1500L
        }
        // The service routes a trailing slash to the same handler, so it gets
        // the same timeout (the review at b6194262).
        val (slashed, _, slashElapsed) = get(s"$base/bridge/long-polling/$session/subscribe/?page=$page&seen=$seen")
        withClue(s"the quiet poll with a trailing slash after $slashElapsed ms: ") {
          slashed.status.intValue shouldBe 204
          slashElapsed should be >= 1500L
        }
      } finally Await.result(binding.unbind(), timeout)
    }
  }

  "An application route whose path only looks like a poll" - {
    "keeps the request timeout of the server" in {
      // Its render takes 2 s, more than the 1 s request timeout of this server
      // and less than the timeout of a poll.
      val config = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default("quiet"),
        router = spoonbill.Router[Future, String](
          { case _ => spoonbill.web.PathAndQuery.Root },
          {
            case path if path.mkString.startsWith("/reports") =>
              _ => Future(scala.concurrent.blocking { Thread.sleep(2000); "slow" })
          }
        ),
        document = state => Html(body(p(state))),
        stateStorage = StateStorage.ephemeral[Future, String](16),
        sessionIdleTimeout = 4.seconds,
        heartbeatLimit = None
      )
      val route   = akkaHttpService(config).apply(AkkaHttpServerConfig())
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      try {
        val base             = s"http://127.0.0.1:${binding.localAddress.getPort}"
        val (response, _, _) = get(base + "/reports/bridge/long-polling/archive/subscribe")
        response.status.intValue shouldBe 503
      } finally Await.result(binding.unbind(), timeout)
    }
  }
}
