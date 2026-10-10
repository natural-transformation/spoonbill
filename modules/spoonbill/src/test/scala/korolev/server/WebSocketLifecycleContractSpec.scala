package spoonbill.server

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*
import spoonbill.testExecution.defaultExecutor

/** Transport-neutral fixture. It uses only the public response variants and
  * ordinary stream operations: input cancellation does not select the mode.
  */
final class PublicWebSocketAdapter[F[_]: Effect] {
  def deliver(input: Stream[F, Bytes], response: WebSocketResponse[F]): F[Vector[String]] =
    response match {
      case WebSocketResponse.SendThenClose(output, _, _) =>
        input.cancel().recover { case NonFatal(_) => () } *> output
          .fold(Vector.empty[String])((frames, bytes) => frames :+ bytes.asUtf8String)
      case WebSocketResponse.Duplex(output, _, release) =>
        input.pull().flatMap {
          case None => release().as(Vector.empty[String])
          case Some(bytes) =>
            output.fold(Vector(bytes.asUtf8String))((frames, next) => frames :+ next.asUtf8String)
        }
    }
}

/** A wrapper must keep the disposition while transforming output. */
final class OutputWrapper[F[_]: Effect] extends SpoonbillService[F] {
  def http(request: HttpRequest[F]): F[HttpResponse[F]] = Effect[F].fail(new IllegalStateException("unused"))
  def ws(request: WebSocketRequest[F]): F[WebSocketResponse[F]] =
    Effect[F].fail(new IllegalStateException("unused"))

  def preserve(response: WebSocketResponse[F]): WebSocketResponse[F] =
    response.mapOutput(_.map(identity).mapAsync(bytes => Effect[F].pure(bytes)))
}

final class WebSocketLifecycleContractSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val adapter = new PublicWebSocketAdapter[Future]
  private val wrapper = new OutputWrapper[Future]

  private final class OpenInput extends Stream[Future, Bytes] {
    private val pending = Promise[Option[Bytes]]()
    val canceled = new AtomicBoolean(false)
    def pull(): Future[Option[Bytes]] = pending.future
    def cancel(): Future[Unit] = effect.delay {
      canceled.set(true)
      pending.trySuccess(None)
      ()
    }
  }

  private def finite(frames: String*): Future[Stream[Future, Bytes]] =
    Stream.emits(frames.map(BytesLike[Bytes].utf8)).mat()

  private def terminal(input: OpenInput, frames: String*): Future[WebSocketResponse[Future]] =
    input.cancel() *> finite(frames*).map { output =>
      WebSocketResponse.SendThenClose(
        output,
        "json",
        WebSocketResponse.releaseOnce[Future](() => output.cancel().recover { case NonFatal(_) => () })
      )
    }

  private def duplex(input: OpenInput, pulled: AtomicInteger): Future[WebSocketResponse[Future]] =
    input.cancel() *> finite("protected").map { output =>
      val guarded = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = effect.delay(pulled.incrementAndGet()) *> output.pull()
        def cancel(): Future[Unit] = output.cancel()
      }
      WebSocketResponse.Duplex(
        guarded,
        "json",
        WebSocketResponse.releaseOnce[Future](() => guarded.cancel().recover { case NonFatal(_) => () })
      )
    }

  "SendThenClose" should "deliver arbitrary frames after input was released" in {
    val input = new OpenInput
    val result = for {
      response <- terminal(input, "alpha", "beta")
      _ = input.canceled.get() shouldBe true
      frames <- adapter.deliver(input, response)
    } yield frames
    Await.result(result, 3.seconds) shouldBe Vector("alpha", "beta")
  }

  it should "keep its mode and bytes through map and mapAsync" in {
    val input = new OpenInput
    val result = for {
      response <- terminal(input, "one", "two")
      wrapped = wrapper.preserve(response)
      frames <- wrapped match {
                  case WebSocketResponse.SendThenClose(output, protocol, _) =>
                    protocol shouldBe "json"
                    output.fold(Vector.empty[String])((frames, bytes) => frames :+ bytes.asUtf8String)
                  case WebSocketResponse.Duplex(_, _, _) =>
                    fail("mapOutput changed SendThenClose into Duplex")
                }
    } yield frames
    Await.result(result, 3.seconds) shouldBe Vector("one", "two")
  }

  "Duplex" should "terminate when input was canceled and must not emit queued output" in {
    val input = new OpenInput
    val pulled = new AtomicInteger(0)
    val result = for {
      response <- duplex(input, pulled)
      frames <- adapter.deliver(input, response)
    } yield frames
    Await.result(result, 3.seconds) shouldBe Vector.empty
    pulled.get() shouldBe 0
  }

  it should "stay duplex when output is replaced" in {
    val input = new OpenInput
    val pulled = new AtomicInteger(0)
    val result = for {
      response <- duplex(input, pulled)
      replaced <- finite("replacement").map(output => response.withOutput(output))
    } yield replaced
    Await.result(result, 3.seconds) match {
      case WebSocketResponse.Duplex(output, "json", release) =>
        Await.result(release(), 3.seconds)
        Await.result(output.pull(), 3.seconds) shouldBe None
      case other => fail(s"withOutput lost the duplex disposition: $other")
    }
  }

  "release" should "run an eager effect once and not at construction" in {
    val runs = new AtomicInteger(0)
    val release = WebSocketResponse.releaseOnce[Future](() => effect.delay { runs.incrementAndGet(); () })
    runs.get() shouldBe 0
    Await.result(release(), 3.seconds)
    Await.result(release(), 3.seconds)
    runs.get() shouldBe 1
  }

  it should "run concurrent eager releases as one attempt" in {
    val runs = new AtomicInteger(0)
    val finish = scala.concurrent.Promise[Unit]()
    val release = WebSocketResponse.releaseOnce[Future](() => {
      runs.incrementAndGet()
      finish.future
    })
    val first = release()
    val second = release()
    first.isCompleted shouldBe false
    second.isCompleted shouldBe false
    finish.success(())
    Await.result(first.zip(second), 3.seconds)
    runs.get() shouldBe 1
  }

  it should "let a later eager release wait for one in-flight cleanup" in {
    val runs = new AtomicInteger(0)
    val gate = Promise[Unit]()
    val release = WebSocketResponse.releaseOnce[Future](() => gate.future.map { _ => runs.incrementAndGet(); () })
    val first = release()
    val second = release()
    runs.get() shouldBe 0
    gate.success(())
    Await.result(first.zip(second), 3.seconds)
    runs.get() shouldBe 1
  }

  "mapOutput" should "cancel a resource-owning wrapper when the response is released" in {
    val wrapperCancels = new AtomicInteger(0)
    val input = new OpenInput
    val result = for {
      response <- terminal(input, "kept")
      wrapped = response.mapOutput { output =>
                  new Stream[Future, Bytes] {
                    def pull(): Future[Option[Bytes]] = output.pull()
                    def cancel(): Future[Unit] = effect.delay { wrapperCancels.incrementAndGet(); () }
                  }
                }
      _ <- wrapped.release()
    } yield wrapperCancels.get()
    Await.result(result, 3.seconds) shouldBe 1
  }

  "withOutput" should "release the original response when replacement cancel throws" in {
    val original = new AtomicInteger(0)
    val output = Await.result(finite("kept"), 3.seconds)
    val response = WebSocketResponse.SendThenClose(
      output,
      "json",
      WebSocketResponse.releaseOnce[Future](() => effect.delay { original.incrementAndGet(); () })
    )
    val wrapped = response.withOutput(new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.successful(None)
      def cancel(): Future[Unit] = throw new IllegalStateException("cancel construction failed")
    })
    Await.result(wrapped.release(), 3.seconds)
    original.get() shouldBe 1
  }
}
