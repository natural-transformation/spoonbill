package spoonbill.http.protocol

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Effect, Stream}

final class Http11InformationalResponseSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private def bytes(value: String): Bytes = Bytes.wrap(value.getBytes("US-ASCII"))
  private val informational = "HTTP/1.1 103 Early Hints\r\n\r\n"
  private val finalResponse = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"
  private val parser = new Http11[Bytes]

  private def chunks(wire: String, chunkSize: Int = 8096): Seq[Bytes] =
    wire.grouped(chunkSize).map(bytes).toVector

  private def source(wire: String, chunkSize: Int = 8096): Stream[Future, Bytes] =
    await(Stream(chunks(wire, chunkSize)*).mat[Future]())

  for ((count, chunkSize) <- Seq((3, 8096), (250, 8096), (3000, 8096), (3000, 1))) {
    "Informational response handling" should s"reach the final response after $count eager headers in $chunkSize-byte chunks" in {
      // Eager input reproduces the synchronous FutureEffect continuation chain;
      // the larger case spans multiple chunks within the default read size.
      val responses = parser.decodeResponse(Decoder(source(
        informational * count + finalResponse + finalResponse, chunkSize)))
      for (_ <- 1 to 2) {
        val response = await(responses.pull()).get
        response.status.code shouldBe 200
        await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "safe"
      }
      await(responses.pull()) shouldBe None
    }
  }

  it should "preserve upgraded bytes after a large informational burst" in {
    val upgrade = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: test\r\nContent-Length: 0\r\n\r\nframes"
    val response = await(parser.decodeResponse(Decoder(source(informational * 3000 + upgrade))).pull()).get
    response.status.code shouldBe 101
    await(response.body.fold(Bytes.empty)(_ ++ _)).asAsciiString shouldBe "frames"
  }

  it should "finish without a response when a burst ends before final headers are complete" in {
    for (tail <- Seq("", "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n")) {
      val responses = parser.decodeResponse(Decoder(source(informational * 3000 + tail)))
      await(responses.pull()) shouldBe None
    }
  }

  it should "report malformed final headers after a large informational burst" in {
    val malformed = "HTTP/1.1 200 OK\r\nMissing-Colon\r\n\r\n"
    val responses = parser.decodeResponse(Decoder(source(informational * 3000 + malformed)))
    val error = intercept[IllegalArgumentException](await(responses.pull()))
    error.getMessage should include("Malformed HTTP response header field")
  }

  it should "propagate an input failure after a large informational burst" in {
    val failure = new IOException("input failed before the final response")
    val input = chunks(informational * 3000).iterator
    val upstream = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] =
        if (input.hasNext) Future.successful(Some(input.next())) else Future.failed(failure)
      def cancel(): Future[Unit] = Future.successful(())
    }
    val responses = parser.decodeResponse(Decoder(upstream))
    intercept[IOException](await(responses.pull())) should be theSameInstanceAs failure
  }

  it should "forward cancellation while waiting for final headers after a large burst" in {
    val input = chunks(informational * 3000).iterator
    val waiting = Promise[Unit]()
    val pending = Promise[Option[Bytes]]()
    val cancellations = new AtomicInteger()
    val upstream = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] =
        if (input.hasNext) Future.successful(Some(input.next()))
        else {
          waiting.trySuccess(())
          pending.future
        }
      def cancel(): Future[Unit] = {
        cancellations.incrementAndGet()
        pending.trySuccess(None)
        Future.successful(())
      }
    }
    val responses = parser.decodeResponse(Decoder(upstream))
    val response = responses.pull()
    await(waiting.future)
    response.isCompleted shouldBe false
    await(responses.cancel())
    cancellations.get() shouldBe 1
    await(response) shouldBe None
  }
}
