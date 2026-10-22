package spoonbill.http.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Effect, Stream}
import spoonbill.web.Request

final class Http11ResponseFramingSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private def bytes(value: String): Bytes = Bytes.wrap(value.getBytes("US-ASCII"))
  private val parser = new Http11[Bytes]
  private val next = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"

  "Response framing" should "preserve bodyless metadata and the next response across every split" in {
    val cases = Seq(
      Request.Method.Get -> "204 No Content",
      Request.Method.Get -> "304 Not Modified",
      Request.Method.Head -> "200 OK"
    )
    for {
      (method, status) <- cases
      field <- Seq("", "Content-Length: 42\r\n", "Transfer-Encoding: chunked\r\n")
    } {
      val wire = s"HTTP/1.1 $status\r\n$field\r\n" + next
      for (split <- 1 until wire.length) {
        withClue(s"$method $status $field split=$split: ") {
          val source = await(Stream(bytes(wire.take(split)), bytes(wire.drop(split))).mat[Future]())
          val decoder = Decoder(source)
          val response = await(parser.decodeResponse(decoder, method).pull()).get
          response.contentLength shouldBe (if (field.startsWith("Content-Length")) Some(42L) else None)
          await(response.body.pull()) shouldBe None
          val following = await(parser.decodeResponse(decoder).pull()).get
          following.status.code shouldBe 200
          await(following.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
        }
      }
    }
  }

  it should "skip fragmented informational headers without consuming the final body" in {
    val wire = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </style.css>\r\n\r\n" + next
    val source = await(Stream(wire.map(c => bytes(c.toString))*).mat[Future]())
    val response = await(parser.decodeResponse(Decoder(source)).pull()).get
    response.status.code shouldBe 200
    await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
  }

  it should "preserve 101 upgraded bytes even when a length field is present" in {
    Seq("", "Content-Length: 0\r\n").foreach { field =>
      val wire = s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n$field\r\nframes"
      val source = await(Stream(bytes(wire)).mat[Future]())
      val response = await(parser.decodeResponse(Decoder(source)).pull()).get
      response.status.code shouldBe 101
      await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "frames"
    }
  }
}
