package external

import _root_.cats.effect.{Deferred, IO, IOLocal}
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.cats.withDefaultRuntime
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Stream}
import spoonbill.http.protocol.Http11

final class HttpInformationalIOSpec extends AnyFlatSpec with Matchers {
  private val informational = "HTTP/1.1 103 Early Hints\r\n\r\n" * 3000
  private val finalResponse = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nsafe"

  private def source(wire: String): IO[Stream[IO, Bytes]] =
    Stream(wire.grouped(8096).map(part => Bytes.wrap(part.getBytes("US-ASCII"))).toVector*).mat[IO]()

  "Informational response decoding with IO" should "retain caller context across a large eager burst" in {
    val checked = for {
      local <- IOLocal("default")
      input <- source(informational + finalResponse)
      upstream = new Stream[IO, Bytes] {
        def pull(): IO[Option[Bytes]] =
          local.get.flatMap(value => IO(value shouldBe "request")) *> input.pull()
        def cancel(): IO[Unit] = input.cancel()
      }
      responses = new Http11[Bytes].decodeResponse(Decoder(upstream))
      _ <- local.set("request")
      response <- responses.pull().map(_.get)
      body <- response.body.fold(Bytes.empty)(_ ++ _)
      context <- local.get
      _ <- IO {
        response.status.code shouldBe 200
        body.asAsciiString shouldBe "safe"
        context shouldBe "request"
      }
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }

  it should "cancel the upstream pull when its caller is canceled after a large burst" in {
    val checked = for {
      waiting <- Deferred[IO, Unit]
      canceled <- Deferred[IO, Unit]
      input <- source(informational)
      upstream = new Stream[IO, Bytes] {
        def pull(): IO[Option[Bytes]] = input.pull().flatMap {
          case Some(chunk) => IO.pure(Some(chunk))
          case None =>
            (waiting.complete(()) *> IO.never[Option[Bytes]])
              .onCancel(canceled.complete(()).void)
        }
        def cancel(): IO[Unit] = input.cancel()
      }
      responses = new Http11[Bytes].decodeResponse(Decoder(upstream))
      worker <- responses.pull().start
      _ <- (for {
        _ <- waiting.get
        _ <- worker.cancel
        cancellation <- canceled.tryGet
        outcome <- worker.join
        _ <- IO {
          cancellation shouldBe Some(())
          outcome.isCanceled shouldBe true
        }
      } yield ()).guarantee(worker.cancel)
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }
}
