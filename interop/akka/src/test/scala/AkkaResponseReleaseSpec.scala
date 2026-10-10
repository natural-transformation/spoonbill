package spoonbill.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import java.util.concurrent.atomic.AtomicInteger
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, WebSocketRequest}
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.effect.syntax.*
import spoonbill.server.{SpoonbillService, WebSocketRequest => SpoonbillWebSocketRequest, WebSocketResponse}

/** The adapter must execute the response finalizer when the socket ends. */
final class AkkaResponseReleaseSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("akka-response-release-spec")
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val timeout = 8.seconds

  override def afterAll(): Unit =
    try Await.result(system.terminate(), timeout)
    finally super.afterAll()

  private def counted(output: Stream[Future, Bytes]): (Promise[Unit], WebSocketResponse.SendThenClose[Future]) = {
    val done = Promise[Unit]()
    val release = WebSocketResponse.releaseOnce[Future](() => output.cancel().map { _ => done.trySuccess(()); () })
    (done, WebSocketResponse.SendThenClose(output, "json", release))
  }

  private def service(make: () => Future[WebSocketResponse[Future]]): SpoonbillService[Future] =
    new SpoonbillService[Future] {
      def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
      def ws(request: SpoonbillWebSocketRequest[Future]) = make()
    }

  private def bind(server: SpoonbillService[Future]) =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(configureWsRoute(server, AkkaHttpServerConfig(), Reporter.PrintReporter, false)), timeout)

  private def connect(port: Int) = {
    val receive = Flow[Message].mapAsync(1) {
      case BinaryMessage.Strict(bytes) => Future.successful(bytes.utf8String)
      case other => Future.successful(other.toString)
    }.toMat(Sink.seq[String])(Keep.right)
    Http().singleWebSocketRequest(
      WebSocketRequest(s"ws://127.0.0.1:$port/bridge/web-socket/probe", subprotocol = Some("json")),
      Flow.fromSinkAndSourceMat(receive, Source.maybe[Message])(Keep.both)
    )
  }

  "Akka response release" - {
    "runs after finite output completes" in {
      val frames = (1 to 64).map(index => s"frame-$index")
      val (releases, response) = counted(Await.result(Stream.emits(frames.map(BytesLike[Bytes].utf8)).mat(), timeout))
      val binding = bind(service(() => Future.successful(response)))
      try {
        val (upgrade, (received, outgoing)) = connect(binding.localAddress.getPort)
        try {
          Await.result(upgrade, timeout)
          Await.result(received, timeout) shouldBe frames
          Await.result(releases.future, timeout)
        } finally outgoing.trySuccess(None)
      } finally Await.result(binding.terminate(2.seconds), timeout)
    }

    "runs when output fails" in {
      val output = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = Future.failed(new IllegalStateException("output failed"))
        def cancel(): Future[Unit] = Future.unit
      }
      val (releases, response) = counted(output)
      val binding = bind(service(() => Future.successful(response)))
      try {
        val (upgrade, (received, outgoing)) = connect(binding.localAddress.getPort)
        try {
          Await.result(upgrade, timeout)
          try Await.result(received, timeout) catch { case _: Throwable => () }
          Await.result(releases.future, timeout)
        } finally outgoing.trySuccess(None)
      } finally Await.result(binding.terminate(2.seconds), timeout)
    }

    "runs when the peer leaves a duplex session" in {
      val gate = Promise[Option[Bytes]]()
      val pulled = Promise[Unit]()
      val output = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = effect.delay { pulled.trySuccess(()); () } *> gate.future
        def cancel(): Future[Unit] = Future.successful { gate.trySuccess(None); () }
      }
      val releases = Promise[Unit]()
      val release = WebSocketResponse.releaseOnce[Future](() => Future.successful { releases.trySuccess(()); () })
      val response = WebSocketResponse.Duplex(output, "json", release)
      val binding = bind(service(() => Future.successful(response)))
      try {
        val (upgrade, (_, outgoing)) = connect(binding.localAddress.getPort)
        try {
          Await.result(upgrade, timeout)
          Await.result(pulled.future, timeout)
          outgoing.tryFailure(new java.io.IOException("peer reset"))
          Await.result(releases.future, timeout)
        } finally outgoing.trySuccess(None)
      } finally Await.result(binding.terminate(2.seconds), timeout)
    }

    "releases a response that arrives after the upgrade request times out" in {
      val entered = Promise[Unit]()
      val responseGate = Promise[WebSocketResponse[Future]]()
      val input = Promise[Stream[Future, Bytes]]()
      val released = Promise[Unit]()
      val pulls = new AtomicInteger(0)
      val output = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = effect.delay {
          pulls.incrementAndGet()
          None
        }
        def cancel(): Future[Unit] = Future.unit
      }
      val route = withRequestTimeout(500.millis) {
          configureWsRoute(
            new SpoonbillService[Future] {
              def http(request: spoonbill.server.HttpRequest[Future]) = Future.failed(new IllegalStateException("unused"))
              def ws(request: SpoonbillWebSocketRequest[Future]) = {
                input.trySuccess(request.httpRequest.body)
                entered.trySuccess(())
                responseGate.future
              }
            },
            AkkaHttpServerConfig(),
            Reporter.PrintReporter,
            false
          )
      }
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), timeout)
      try {
        val (upgrade, (_, outgoing)) = connect(binding.localAddress.getPort)
        try {
          Await.result(entered.future, timeout)
          val rejected = Await.result(upgrade, timeout).response
          rejected.status shouldBe StatusCodes.ServiceUnavailable
          rejected.discardEntityBytes()
          Await.result(Await.result(input.future, timeout).pull(), timeout) shouldBe None
          val release = WebSocketResponse.releaseOnce[Future](() => effect.delay { released.trySuccess(()); () })
          responseGate.success(WebSocketResponse.SendThenClose(output, "json", release))
          pulls.get() shouldBe 0
          Await.result(released.future, timeout)
          pulls.get() shouldBe 0
        } finally outgoing.trySuccess(None)
      } finally Await.result(binding.terminate(2.seconds), timeout)
    }
  }

}
