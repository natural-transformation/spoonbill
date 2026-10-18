package spoonbill.server

import avocet.dsl.*
import avocet.dsl.html.*
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.{Executors, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.{ActionForms, Context, Qsid, SensitiveRegion}
import spoonbill.action.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.effect.io.ServerSocket
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.sensitive.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.Request

/**
 * Synthetic, loopback-only service used by test-sensitive-acknowledgement.sh.
 * The transport gate observes actual output and never manufactures client
 * frames.
 */
object SensitiveAcknowledgementBrowserFixture {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private val modes                           = Set("no-update", "identity", "modify", "revocation")
  private val region                          = RegionId.parse("setup").toOption.get
  private val purpose                         = Purpose.parse("setup").toOption.get
  private val silentReporter = new Reporter {
    def debug(message: String): Unit                         = ()
    def debug(message: String, a: Any): Unit                 = ()
    def debug(message: String, a: Any, b: Any): Unit         = ()
    def debug(message: String, a: Any, b: Any, c: Any): Unit = ()
    def info(message: String): Unit                          = ()
    def warning(message: String): Unit                       = ()
    def warning(message: String, cause: Throwable): Unit     = ()
    def error(message: String): Unit                         = ()
    def error(message: String, cause: Throwable): Unit       = ()
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Pass the readiness file path")
    val executor                      = Executors.newFixedThreadPool(6)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
    val timer                         = Executors.newSingleThreadScheduledExecutor()
    val group                         = AsynchronousChannelGroup.withFixedThreadPool(4, Executors.defaultThreadFactory())
    val origin                        = new AtomicReference[String]("")
    def bounded[A](future: Future[A]): Future[A] = {
      val result = Promise[A]()
      val job = timer.schedule(
        new Runnable {
          def run(): Unit = { result.tryFailure(new TimeoutException("Fixture control deadline exceeded")); () }
        },
        9,
        TimeUnit.SECONDS
      )
      future.onComplete { value =>
        job.cancel(false); result.tryComplete(value); ()
      }
      result.future
    }

    final class Run(val mode: String) {
      val armed                = new AtomicBoolean()
      val revoked              = new AtomicBoolean()
      val acknowledgements     = new AtomicInteger()
      val confirmations        = new AtomicInteger()
      val modifications        = new AtomicInteger()
      val completed            = new AtomicInteger()
      val patches              = new AtomicInteger()
      val submissionCount      = new AtomicInteger()
      val submissions          = new AtomicReference(Vector.empty[(String, String)])
      val revealDone           = Promise[Unit]()
      val confirmDone          = Promise[Unit]()
      val patchSeen            = Promise[Unit]()
      val guardClosed          = Promise[Unit]()
      val gateReleased         = Promise[Unit]()
      val cancelled            = new AtomicBoolean()
      val releaseResponse      = new AtomicReference[Option[() => Future[Unit]]](None)
      @volatile var connection = ""
      @volatile var revision   = "0"
      private val audience     = Audience.fromUuid(UUID.randomUUID())
      private val control = new SessionAccessControl[Future, Int] {
        def authorizeHttp(request: Request.Head, state: Int): Future[Unit] = Future.unit
        def open(qsid: Qsid, request: Request.Head, id: ConnectionId): Future[SessionGuard[Future, Int]] =
          Future.successful(new SessionGuard[Future, Int] {
            val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
            override val ownershipEpoch        = Some(ViewOwnershipEpoch.initial)
            def authorize(state: Int): Future[Unit] =
              if (revoked.get()) Future.failed(new SessionAccessDenied) else Future.unit
            def connected(state: Int): Future[Int] = Future.successful(state)
            def close(): Future[Unit]              = { guardClosed.trySuccess(()); Future.unit }
            override val sensitive = Some(new SensitiveAccess[Future, Int] {
              def authorize(requested: Purpose, state: Int): Future[Audience] =
                if (requested == purpose && !revoked.get()) Future.successful(audience)
                else Future.failed(new SessionAccessDenied)
            })
          })
      }
      private val actions = new Actions[Future, Int, Unit]
      private val reveal = actions.public(
        ActionName.parse("fixture.reveal").toOption.get,
        InputSchema.empty,
        PublicPolicy.allow[Future, Unit]
      ) { (_, context) =>
        Future.successful(
          context.presentSensitive[Int](
            region,
            purpose,
            SensitivePayload.textList(Vector("synthetic-setup-only")).toOption.get,
            1.minute
          ) { outcome =>
            require(outcome == DisclosureOutcome.BrowserProcessed, "Expected the browser's normal acknowledgement")
            acknowledgements.incrementAndGet()
            mode match {
              case "identity" =>
                StateUpdate.Modify { state =>
                  modifications.incrementAndGet(); state
                }
              case "modify" =>
                StateUpdate.Modify { state =>
                  modifications.incrementAndGet(); state + 1
                }
              case _ => StateUpdate.noUpdate[Int]
            }
          }
        )
      }
      private val confirm = actions.public(
        ActionName.parse("fixture.confirm").toOption.get,
        InputSchema.empty,
        PublicPolicy.allow[Future, Unit]
      ) { (_, _) =>
        confirmations.incrementAndGet()
        Future.successful(UiOutcome.noUpdate[Int])
      }
      private val rejected: ActionRejection => StateUpdate[Int] = _ =>
        throw new AssertionError("Unexpected typed action rejection")
      private type Binding = Context.Binding[Future, Int, Any]
      val service = spoonbillService(
        SpoonbillServiceConfig[Future, Int, Any](
          stateLoader = StateLoader.default(0),
          document = state =>
            Html(
              head(title("Sensitive acknowledgement fixture")),
              body(
                p(id := "value", state.toString),
                form(
                  id := "reveal",
                  ActionForms.onSubmit[Future, Int, Any, Unit](reveal, rejected),
                  button("Reveal setup")
                ),
                SensitiveRegion[Binding](region),
                form(
                  id := "confirm",
                  ActionForms.onSubmit[Future, Int, Any, Unit](confirm, rejected),
                  button("Confirm setup")
                )
              )
            ),
          sessionAccessControl = Some(control),
          // Guarded transport requires an exact allowed origin, even though
          // this synthetic guard needs no account or authentication ceremony.
          authenticationCompletion = Some(
            AuthenticationCompletionConfig[Future](
              bindingCookieName = "fixture-binding",
              sessionCookieName = "fixture-session",
              allowedOrigins = Set(origin.get()),
              secureCookies = false,
              cookieMaxAgeSeconds = 60L,
              deliver = (_, _) => Future.successful(None),
              logout = (_, _) => Future.unit
            )
          ),
          heartbeatInterval = 1.minute,
          reporter = silentReporter
        )
      )
      private val reset   = """^\[19,"[0-9]+","([0-9a-f-]{36})","([0-9]+)",.*""".r
      private val patch   = """^\[20,"[0-9a-f-]{36}","[0-9]+","([0-9]+)",.*""".r
      private val counter = """^\[0,"[^"]+","submit",[0-9]+\s*\]$""".r
      private val submission = """^\[7,"([0-9a-f-]{36}):([0-9]+):0:[0-9]+:[^:"]+:submit:.*""".r

      // Observe actual input without replacing, reordering or replaying it.
      // Retain only two sanitized stamps; fields and payloads are never saved.
      def observeInput(bytes: Bytes): Bytes = {
        if (bytes.length <= 16384) bytes.asUtf8String match {
          case submission(id, atRevision) =>
            submissionCount.incrementAndGet()
            submissions.updateAndGet(previous => (previous :+ (id -> atRevision)).take(2))
          case _ => ()
        }
        bytes
      }

      def metadata: String = {
        val observed = submissions.get().map { case (id, atRevision) =>
          s"""{"connection":"$id","revision":"$atRevision"}"""
        }.mkString("[", ",", "]")
        s"""{"acknowledgements":${acknowledgements.get()},"confirmations":${confirmations
          .get()},"modifications":${modifications.get()},"completed":${completed.get()},"patches":${patches
          .get()},"connection":"$connection","revision":"$revision","closed":${guardClosed.isCompleted},"submissionCount":${submissionCount.get()},"submissions":$observed}"""
      }

      def gate(source: Stream[Future, Bytes]): Stream[Future, Bytes] = new Stream[Future, Bytes] {
        // One producer and one consumer; at most 32 buffered frames and one
        // held delivery. Later frames never pass the first blocked patch.
        private val queue    = Queue[Future, (Bytes, Boolean)](32)
        private val started  = new AtomicBoolean()
        private val pumpDone = Promise[Unit]()
        private def pump(): Future[Unit] =
          if (cancelled.get()) Future.unit
          else
            source.pull().flatMap {
              case None => queue.stop()
              case Some(bytes) =>
                require(bytes.length <= 1024 * 1024, "Fixture frame bound exceeded")
                val text = bytes.asUtf8String
                val hold = armed.get() && text.startsWith("[20,")
                text match {
                  case reset(id, next) => connection = id; revision = next
                  case patch(next) =>
                    revision = next
                    if (armed.get()) { patches.incrementAndGet(); patchSeen.trySuccess(()) }
                  case counter() if armed.get() =>
                    if (completed.incrementAndGet() == 1) revealDone.trySuccess(())
                    else confirmDone.trySuccess(())
                  case _ => ()
                }
                queue.enqueue((bytes, hold)).flatMap(_ => pump())
            }
        def pull(): Future[Option[Bytes]] = {
          if (started.compareAndSet(false, true)) {
            pump().onComplete { result =>
              pumpDone.tryComplete(result)
              result.failed.foreach(queue.failUnsafe)
            }
          }
          queue.stream.pull().flatMap {
            case Some((bytes, true))  => gateReleased.future.map(_ => if (cancelled.get()) None else Some(bytes))
            case Some((bytes, false)) => Future.successful(if (cancelled.get()) None else Some(bytes))
            case None                 => Future.successful(None)
          }
        }
        def cancel(): Future[Unit] = {
          cancelled.set(true)
          gateReleased.trySuccess(())
          queue.close().flatMap(_ => source.cancel()).flatMap(_ => if (started.get()) pumpDone.future else Future.unit)
        }
      }
      def close(): Future[Unit] = {
        cancelled.set(true)
        gateReleased.trySuccess(())
        releaseResponse.get().fold(Future.unit)(release => release().flatMap(_ => guardClosed.future))
      }
    }

    val current = new AtomicReference[Option[Run]](None)
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] = {
        val path = request.pq.asPath.mkString
        if (path == "/health") HttpResponse.ok[Future]("ready")
        else if (path.startsWith("/fixture/start/")) {
          val mode = path.stripPrefix("/fixture/start/")
          require(modes(mode) && request.method == Request.Method.Post)
          bounded(current.get().fold(Future.unit)(_.close())).flatMap { _ =>
            current.set(Some(new Run(mode)))
            HttpResponse.ok[Future]("ready")
          }
        } else if (path.startsWith("/fixture/")) {
          val run = current.get().getOrElse(throw new IllegalStateException("Start a run first"))
          val result = path match {
            case "/fixture/arm"           => run.armed.set(true); Future.unit
            case "/fixture/revoke"        => run.revoked.set(true); Future.unit
            case "/fixture/release"       => run.close()
            case "/fixture/await-reveal"  => run.revealDone.future
            case "/fixture/await-patch"   => run.patchSeen.future
            case "/fixture/await-confirm" => run.confirmDone.future
            case "/fixture/await-close"   => run.guardClosed.future
            case "/fixture/status"        => Future.unit
            case _                        => Future.failed(new IllegalArgumentException("Unknown fixture control"))
          }
          bounded(result).flatMap(_ => HttpResponse.ok[Future](run.metadata))
        } else current.get().getOrElse(throw new IllegalStateException("Start a run first")).service.http(request)
      }
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] = {
        val run = current.get().getOrElse(throw new IllegalStateException("Start a run first"))
        val observed = request.copy(
          httpRequest = request.httpRequest.copy(body = request.httpRequest.body.map(run.observeInput))
        )
        run.service.ws(observed).map { response =>
          val wrapped = response.mapOutput(run.gate)
          run.releaseResponse.set(Some(wrapped.release))
          wrapped
        }
      }
    }
    val server  = new AtomicReference[Option[ServerSocket.ServerSocketHandler[Future]]](None)
    val stopped = new AtomicBoolean()
    def cleanup(): Unit = if (stopped.compareAndSet(false, true)) {
      try Await.result(bounded(current.get().fold(Future.unit)(_.close())), 10.seconds)
      finally {
        try Await.result(server.get().fold(Future.unit)(_.stopServingRequests()), 10.seconds)
        finally { group.shutdownNow(); timer.shutdownNow(); executor.shutdownNow() }
      }
    }
    Runtime.getRuntime.addShutdownHook(new Thread(() => cleanup(), "sensitive-fixture-cleanup"))
    try {
      val handler = Await.result(
        standalone.buildServer[Future, Array[Byte]](
          service,
          new InetSocketAddress("127.0.0.1", 0),
          group,
          gracefulShutdown = false
        ),
        15.seconds
      )
      server.set(Some(handler))
      val port = handler.localAddress.asInstanceOf[InetSocketAddress].getPort
      origin.set(s"http://127.0.0.1:$port")
      Files.writeString(Path.of(args(0)), origin.get())
      Await.result(Promise[Unit]().future, Duration.Inf)
    } finally cleanup()
  }
}
