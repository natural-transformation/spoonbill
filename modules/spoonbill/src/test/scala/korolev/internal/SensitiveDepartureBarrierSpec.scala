package spoonbill.internal

import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.SessionAccessDenied
import spoonbill.sensitive.*

class SensitiveDepartureBarrierSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter
  private val connection = ConnectionId.fromUuid(UUID.randomUUID())
  private val region = RegionId.parse("recovery").toOption.getOrElse(fail("region"))
  private val purpose = Purpose.parse("mfa.recovery").toOption.getOrElse(fail("purpose"))
  private val audience = Audience.fromUuid(UUID.randomUUID())
  private def payload = SensitivePayload.textList(Vector("synthetic-departure-code")).toOption.getOrElse(fail("payload"))
  private val deadlines = new Frontend.RpcDeadlineScheduler {
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = () => ()
  }

  private def fixture(authorize: () => Future[Unit] = () => Future.unit) = {
    val incoming = Queue[Future, String]()
    val frontend = new Frontend[Future](incoming.stream, Some(2), connectionId = Some(connection),
      authorize = Some(authorize), rpcDeadlineScheduler = Some(deadlines),
      captureSensitiveAuthorization = Some(_ => Future.successful(SensitiveAuthorization(audience, () => Future.unit))))
    (incoming, frontend)
  }

  // Time bounds make a control-rack deadlock fail deterministically. No browser
  // RPC/disclosure timer is allowed to rescue the protocol under test.
  private def bounded[A](frontend: Frontend[Future])(operation: => Future[A]): Future[A] = {
    val executor = Executors.newSingleThreadScheduledExecutor((run: Runnable) => {
      val thread = new Thread(run, "sensitive-departure-test-timeout")
      thread.setDaemon(true)
      thread
    })
    val result = Promise[A]()
    val timeout = executor.schedule(new Runnable {
      def run(): Unit = { result.tryFailure(new TimeoutException("Sensitive departure barrier stalled")); () }
    }, 5L, TimeUnit.SECONDS)
    operation.onComplete(value => { result.tryComplete(value); () })
    result.future.transformWith { outcome =>
      timeout.cancel(false)
      executor.shutdownNow()
      frontend.close().transformWith(_ => Future.fromTry(outcome))
    }
  }

  private def next(frontend: Frontend[Future]): Future[String] =
    frontend.outgoingMessages.pull().map(_.getOrElse(fail("Connection closed before expected frame")))

  "Sensitive departure recovery" should "release an unacknowledged disclosure and revalidate on the same binding" in {
    val authorizations = new AtomicInteger(0)
    val (incoming, frontend) = fixture(() => Future.successful { authorizations.incrementAndGet(); () })
    bounded(frontend) {
      val disclosure = frontend.runUserAction(frontend.presentSensitive(region, purpose, payload, 1.minute))
      for {
        shown <- next(frontend)
        _ = shown should startWith("[22,")
        checksBefore = authorizations.get()
        _ <- incoming.enqueue("[10,\"1\"]")
        cleared <- next(frontend)
        outcome <- disclosure
        barrier <- next(frontend)
      } yield {
        cleared should startWith("[23,")
        outcome shouldBe DisclosureOutcome.Uncertain
        barrier shouldBe "[25,1]"
        authorizations.get() should be > checksBefore
      }
    }
  }

  it should "keep ordinary RPC replies live and drop a queued custom action before its domain effect starts" in {
    val (incoming, frontend) = fixture()
    val invoked = new AtomicInteger(0)
    val fresh = Promise[Unit]()
    bounded(frontend) {
      for {
        _ <- frontend.registerCustomCallback("show") { value =>
          invoked.incrementAndGet()
          if (value == "fresh") fresh.trySuccess(())
          Future.unit
        }
        waiting = frontend.runUserAction(frontend.extractProperty(avocet.Id("1"), "value"))
        request <- next(frontend)
        _ = request should startWith("[3,\"0\",")
        _ <- incoming.enqueue("[1,\"show:old\"]")
        _ <- incoming.enqueue("[10,\"1\"]")
        _ <- incoming.enqueue("[6]")
        heartbeat <- next(frontend)
        _ = heartbeat shouldBe "[16]"
        _ = waiting.isCompleted shouldBe false
        // The recovery job is waiting behind this RPC-owning job. Its callback
        // must already have released the control rack to accept this response.
        _ <- incoming.enqueue("[2,\"0:0:reply\"]")
        reply <- waiting
        barrier <- next(frontend)
        _ = invoked.get() shouldBe 0
        _ <- incoming.enqueue("[1,\"show:fresh\"]")
        _ <- fresh.future
      } yield {
        reply shouldBe "reply"
        barrier shouldBe "[25,1]"
        invoked.get() shouldBe 1
      }
    }
  }

  it should "discard an unread predeparture DOM rack item before revision checks and admit fresh events" in {
    val (incoming, frontend) = fixture()
    val invoked = new AtomicInteger(0)
    bounded(frontend) {
      for {
        // Deliberately leave the DOM rack unread until recovery has completed.
        _ <- incoming.enqueue("[0,\"0:1:click\"]")
        _ <- incoming.enqueue("[10,\"1\"]")
        barrier <- next(frontend)
        old <- frontend.domEventMessages.pull().map(_.getOrElse(fail("Missing old event")))
        _ <- frontend.runBrowserAction(Future.successful { invoked.incrementAndGet(); () }, Some(99L), old.sensitiveDeparture)
        _ = invoked.get() shouldBe 0
        _ <- incoming.enqueue("[0,\"0:1:click\"]")
        current <- frontend.domEventMessages.pull().map(_.getOrElse(fail("Missing fresh event")))
        _ <- frontend.runBrowserAction(Future.successful { invoked.incrementAndGet(); () }, current.renderRevision, current.sensitiveDeparture)
      } yield {
        barrier shouldBe "[25,1]"
        old.sensitiveDeparture shouldBe 0L
        current.sensitiveDeparture shouldBe 1L
        invoked.get() shouldBe 1
      }
    }
  }

  it should "retain the separate history barrier when its rack was unread during recovery" in {
    val (incoming, frontend) = fixture()
    bounded(frontend) {
      for {
        _ <- incoming.enqueue("[3,\"/next\"]")
        _ <- incoming.enqueue("[10,\"1\"]")
        departure <- next(frontend)
        history <- frontend.browserHistoryMessages.pull().map(_.getOrElse(fail("Missing history")))
        _ <- frontend.runUserAction(frontend.completeSensitiveNavigation(history.sensitiveNavigation.getOrElse(fail("Missing barrier"))))
        navigation <- next(frontend)
      } yield {
        departure shouldBe "[25,1]"
        history.path shouldBe spoonbill.web.PathAndQuery.fromString("/next")
        navigation shouldBe "[24,1]"
      }
    }
  }

  it should "refuse to reopen after authorization is revoked while awaiting the serialized barrier" in {
    val allowed = new AtomicBoolean(true)
    val (incoming, frontend) = fixture(() =>
      if (allowed.get()) Future.unit else Future.failed(new SessionAccessDenied))
    bounded(frontend) {
      val waiting = frontend.runUserAction(frontend.extractProperty(avocet.Id("1"), "value"))
      for {
        _ <- next(frontend)
        _ <- incoming.enqueue("[10,\"1\"]")
        _ <- incoming.enqueue("[6]")
        heartbeat <- next(frontend)
        _ = heartbeat shouldBe "[16]"
        _ = allowed.set(false)
        _ <- incoming.enqueue("[2,\"0:0:reply\"]")
        _ <- waiting
        output <- frontend.outgoingMessages.pull()
      } yield output shouldBe None
    }
  }

  it should "accept increasing bounded counters and ignore repeats without another barrier" in {
    val (incoming, frontend) = fixture()
    bounded(frontend) {
      for {
        _ <- incoming.enqueue("[10,\"2\"]")
        barrier <- next(frontend)
        _ <- incoming.enqueue("[10,\"1\"]")
        _ <- incoming.enqueue("[10,\"2\"]")
        _ <- incoming.enqueue("[6]")
        heartbeat <- next(frontend)
        _ <- incoming.enqueue("[10,\"9007199254740992\"]")
        closed <- frontend.outgoingMessages.pull()
      } yield {
        barrier shouldBe "[25,2]"
        heartbeat shouldBe "[16]"
        closed shouldBe None
      }
    }
  }
}
