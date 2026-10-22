package spoonbill.akka

import _root_.akka.actor.ActorSystem
import _root_.akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import _root_.akka.stream.Materializer
import _root_.akka.stream.scaladsl.Source
import _root_.akka.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.web.WebSocketLimits

final class AkkaWebSocketLimitsSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  implicit private val system: ActorSystem        = ActorSystem("akka-websocket-limits")
  implicit private val materializer: Materializer = Materializer(system)
  implicit private val ec: ExecutionContext       = system.dispatcher
  private val config                              = AkkaHttpServerConfig(wsMaxMessageBytes = 4)
  private val timeout                             = 5.seconds

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  "Akka message admission" - {
    "counts UTF-8 across chunk boundaries" in {
      Await
        .result(boundedMessage(TextMessage(Source(List("\uD83D", "\uDE00"))), config), timeout)
        .asUtf8String shouldBe "😀"
      intercept[WebSocketLimits.Exceeded] {
        Await.result(boundedMessage(TextMessage(Source(List("é", "é", "a"))), config), timeout)
      }
    }
    "enforces the same strict and fragmented binary limit" in {
      Await.result(boundedMessage(BinaryMessage(ByteString("1234")), config), timeout).length shouldBe 4L
      intercept[WebSocketLimits.Exceeded] {
        Await.result(boundedMessage(BinaryMessage(Source(List(ByteString("1234"), ByteString("5")))), config), timeout)
      }
    }
  }
}
