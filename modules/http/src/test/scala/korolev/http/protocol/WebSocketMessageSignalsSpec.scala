package spoonbill.http.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.web.{PathAndQuery, Request, Response}

class WebSocketMessageSignalsSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private val protocol = new WebSocketProtocol[Bytes]
  private val handshakeKey = "dGhlIHNhbXBsZSBub25jZQ=="
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def wire(frame: WebSocketProtocol.Frame[Bytes]): Bytes = protocol.encodeFrame(frame, Some(0))

  private class Fixture(chunks: Seq[Bytes], failCompletion: Boolean = false) {
    var signals = Vector.empty[String]
    var frames = Option.empty[Stream[Future, WebSocketProtocol.Frame.Merged[Bytes]]]
    val error = new IllegalStateException("completion observer")
    val input = result(Stream.emits(chunks).mat[Future]())
    val request = Request(Request.Method.Get, PathAndQuery.Root, Seq(
      "connection" -> "upgrade", "upgrade" -> "websocket",
      "sec-websocket-key" -> handshakeKey, "sec-websocket-version" -> "13"
    ), None, input)
    val upgrade = protocol.upgrade[Future](WebSocketProtocol.Intention(handshakeKey),
      () => signals :+= "start",
      () => { signals :+= "complete"; if (failCompletion) throw error }
    ) { request =>
      frames = Some(request.body)
      Future.successful(Response(Response.Status.Ok,
        Stream.empty[Future, WebSocketProtocol.Frame.Merged[Bytes]], Nil, None))
    }
    result(upgrade(request))
    def pull() = frames.get.pull()
  }

  "WebSocket assembly signals" should "ignore empty chunks and finish a complete message" in {
    val f = new Fixture(Seq(Bytes.empty, wire(WebSocketProtocol.Frame.Binary(Bytes(1)))))
    result(f.pull()).get shouldBe WebSocketProtocol.Frame.Binary(Bytes(1))
    f.signals shouldBe Vector("start", "complete")
    result(f.pull()) shouldBe None
    f.signals shouldBe Vector("start", "complete")
  }

  it should "rearm a buffered frame tail before the application asks for its next message" in {
    val f = new Fixture(Seq(wire(WebSocketProtocol.Frame.Binary(Bytes(1))) ++
      wire(WebSocketProtocol.Frame.Binary(Bytes(2)))))
    result(f.pull()).get shouldBe WebSocketProtocol.Frame.Binary(Bytes(1))
    f.signals shouldBe Vector("start", "complete", "start")
    result(f.pull()).get shouldBe WebSocketProtocol.Frame.Binary(Bytes(2))
    f.signals shouldBe Vector("start", "complete", "start", "start", "complete")
  }

  it should "keep fragmented message ownership across an interleaved control frame" in {
    val f = new Fixture(Seq(
      wire(WebSocketProtocol.Frame.Binary(Bytes(1), fin = false)),
      wire(WebSocketProtocol.Frame.Ping),
      wire(WebSocketProtocol.Frame.Continuation(Bytes(2), fin = true))
    ))
    result(f.pull()).get.opcode shouldBe WebSocketProtocol.OpPing
    f.signals shouldBe Vector("start", "start")
    result(f.pull()).get shouldBe WebSocketProtocol.Frame.Binary(Bytes(1, 2))
    f.signals shouldBe Vector("start", "start", "start", "complete")
  }

  it should "not restart a tail after completion rejects the preceding message" in {
    val f = new Fixture(Seq(wire(WebSocketProtocol.Frame.Binary(Bytes(1))) ++ Bytes(0x82)), true)
    result(f.pull().failed) shouldBe f.error
    f.signals shouldBe Vector("start", "complete")
  }
}
