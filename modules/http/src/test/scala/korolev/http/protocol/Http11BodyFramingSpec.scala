package spoonbill.http.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Effect, Stream}

final class Http11BodyFramingSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private def bytes(value: String): Bytes = Bytes.wrap(value.getBytes("ISO-8859-1"))
  private val header = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
  private def decode(parts: Seq[String], parser: Http11[Bytes] = new Http11[Bytes]) = {
    val source = await(Stream(parts.map(bytes)*).mat[Future]())
    val requests = parser.decodeRequest(Decoder(source))
    requests -> await(requests.pull()).get.body
  }

  "Request framing" should "reject ambiguous or invalid lengths and transfer codings before dispatch" in {
    val fields = Seq(
      "Content-Length: 0\r\nContent-Length: 0", "Content-Length: 0\r\ncontent-length: 1",
      "Content-Length: 0, 0", "Content-Length: -1", "Content-Length: +1", "Content-Length:",
      "Content-Length: 9223372036854775808", "Content-Length: 1x",
      "Content-Length: 1\r\nTransfer-Encoding: chunked",
      "Transfer-Encoding: chunked\r\nContent-Length: 1",
      "Transfer-Encoding: gzip", "Transfer-Encoding: gzip, chunked", "Transfer-Encoding: chunked, chunked",
      "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked", "Transfer-Encoding:",
      "Transfer-Encoding: chunked; bad=value"
    )
    val parser = new Http11[Bytes]
    fields.foreach { value =>
      withClue(value) {
        intercept[Http11.InvalidFraming](parser.decodeRequest(Bytes.empty, bytes(s"POST / HTTP/1.1\r\n$value\r\n\r\n")))
      }
    }
    intercept[Http11.InvalidFraming](parser.decodeRequest(Bytes.empty,
      bytes("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n")))
  }

  it should "treat an unframed body as empty even with a WebSocket-key field" in {
    val next = "GET /next HTTP/1.1\r\n\r\n"
    val (requests, body) = decode(Seq("POST / HTTP/1.1\r\nSec-WebSocket-Key: spoof\r\n\r\n" + next))
    await(body.pull()) shouldBe None
    await(requests.pull()).get.pq.toString should include("next")
  }

  "Chunked bodies" should "stream a large advertised chunk before its remainder arrives" in {
    val (_, body) = decode(Seq(header + "7fffffffffffffff\r\n" + "a" * 37))
    await(body.pull()).get.asUtf8String shouldBe "a" * 37
    intercept[Http11.InvalidFraming](await(body.pull())) // EOF is not successful completion.
  }

  it should "preserve delimiters, extensions, trailers and the following request across every split" in {
    val content = "4;foo=bar;quoted=\"a\\\"b\"\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Checksum: ok\r\n\r\n"
    val next = "GET /next HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
    val wire = header + content + next
    (1 until wire.length).foreach { split =>
      val (requests, body) = decode(Seq(wire.take(split), wire.drop(split)))
      await(body.fold(Bytes.empty)(_ ++ _)).asUtf8String shouldBe "Wikipedia"
      await(requests.pull()).get.pq.toString should include("next")
    }
    val (_, bytewise) = decode(wire.map(_.toString))
    await(bytewise.fold(Bytes.empty)(_ ++ _)).asUtf8String shouldBe "Wikipedia"
  }

  it should "bound metadata before buffering and accept exact line and trailer budgets" in {
    val parser = new Http11[Bytes](maxChunkLineBytes = 8, maxTrailerBytes = 8)
    val (_, exact) = decode(Seq(header + "1;x=ab\r\nz\r\n0\r\nX: y\r\n\r\n"), parser)
    await(exact.fold(Bytes.empty)(_ ++ _)).asUtf8String shouldBe "z"
    Seq("123456789", "1;x=abc\r\nz\r\n0\r\n\r\n", "0\r\nX: yy\r\n\r\n").foreach { suffix =>
      val (_, body) = decode(Seq(header + suffix), parser)
      intercept[Http11.InvalidFraming](await(body.fold(Bytes.empty)(_ ++ _)))
    }
  }

  it should "reject malformed chunk syntax, forbidden trailers and truncated bodies" in {
    val invalid = Seq(
      "-1\r\n", "+1\r\n", "0x1\r\n", "8000000000000000\r\n", "1\n", "1\rX",
      "1;\r\n", "1;x=\r\n", "1;x=\"unterminated\r\n", "1;x=\"\\\u007f\"\r\na\r\n0\r\n\r\n",
      "1;x \r\n", "1;x=y \r\n", "1;x=\"y\" \r\n", "1\r\naXX", "1\r\na\rX",
      "0\r\nBad\r\n\r\n", "0\r\nContent-Length: 0\r\n\r\n", "0\r\nTransfer-Encoding: chunked\r\n\r\n",
      "1\r\n", "1\r\na", "1\r\na\r", "0\r\n", "0\r\nX: y\r\n"
    )
    invalid.foreach { suffix =>
      withClue(suffix) {
        val (_, body) = decode(Seq(header + suffix))
        intercept[Http11.InvalidFraming](await(body.fold(Bytes.empty)(_ ++ _)))
      }
    }
  }

  it should "reject premature EOF in fixed-length bodies" in {
    val (_, body) = decode(Seq("POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\nabc"))
    intercept[Http11.InvalidFraming](await(body.fold(Bytes.empty)(_ ++ _)))
  }
}
