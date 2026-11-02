package spoonbill.internal

import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import spoonbill.Metrics
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.SessionAccessDenied

/**
 * A valid browser can send an event stamped with the revision it last applied
 * while a newer render is already published. Such an event is discarded on the
 * same connection; invalid revisions and failed authority still close it.
 */
class ObsoleteBrowserEventSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter     = Reporter.PrintReporter
  private val connection                      = ConnectionId.fromUuid(UUID.fromString("22222222-2222-4222-8222-222222222222"))

  private val timer = Executors.newSingleThreadScheduledExecutor { (run: Runnable) =>
    val thread = new Thread(run, "obsolete-browser-event-test-timeout")
    thread.setDaemon(true)
    thread
  }

  // A connection wrongly closed by a regression leaves awaited promises pending.
  // Fail the workflow after a bound instead of leaving the suite waiting.
  private def bounded[A](future: Future[A]): Future[A] = {
    val result = Promise[A]()
    val timeout = timer.schedule(
      new Runnable {
        def run(): Unit = { result.tryFailure(new TimeoutException("Obsolete browser event test timed out")); () }
      },
      10,
      TimeUnit.SECONDS
    )
    future.onComplete { outcome =>
      timeout.cancel(false); result.tryComplete(outcome); ()
    }
    result.future
  }

  private def browser(incoming: Queue[Future, String], authorize: () => Future[Unit] = () => Future.unit) =
    new Frontend[Future](
      incoming.stream,
      Some(2),
      connectionId = Some(connection),
      authorize = Some(authorize),
      viewRecoveryEpoch = Some(ViewOwnershipEpoch.initial)
    )

  "Obsolete browser events" should "discard a queued old event and keep current events and heartbeats alive" in {
    val incoming           = Queue[Future, String]()
    val frontend           = browser(incoming)
    val entered            = Promise[Unit]()
    val release            = Promise[Unit]()
    val oldInvocations     = new AtomicInteger()
    val currentInvocations = new AtomicInteger()
    val discardsBefore     = Metrics.ObsoleteBrowserEvents.get
    bounded(for {
      _         <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _         <- frontend.outgoingMessages.pull()
      blocker    = frontend.runUserAction { entered.trySuccess(()); release.future }
      _         <- entered.future
      obsolete   = frontend.runBrowserAction(Future.successful { oldInvocations.incrementAndGet(); () }, Some(0L), 0L)
      _         <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      _         <- frontend.outgoingMessages.pull()
      _          = release.success(())
      _         <- blocker
      _         <- obsolete
      _         <- frontend.runBrowserAction(Future.successful { currentInvocations.incrementAndGet(); () }, Some(1L), 0L)
      _         <- incoming.enqueue("[6]")
      heartbeat <- frontend.outgoingMessages.pull()
      _         <- frontend.close()
    } yield {
      oldInvocations.get() shouldBe 0
      currentInvocations.get() shouldBe 1
      heartbeat shouldBe Some("[16]")
      // Other suites can only add to this process-wide counter.
      Metrics.ObsoleteBrowserEvents.get should be >= discardsBefore + 1
    })
  }

  // The queue's post-authorization check discards this event before its operation
  // runs. ObsoleteDomEventSelectionSpec covers the later handler-selection check.
  it should "discard at the queue's revision check when a render wins during its authorization" in {
    val pauseNext = new AtomicBoolean(false)
    val entered   = Promise[Unit]()
    val release   = Promise[Unit]()
    val incoming  = Queue[Future, String]()
    val frontend = browser(
      incoming,
      () =>
        if (pauseNext.compareAndSet(true, false)) {
          entered.trySuccess(()); release.future
        } else Future.unit
    )
    val selected = new AtomicInteger()
    bounded(for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      _  = pauseNext.set(true)
      obsolete = frontend.runBrowserAction(
                   frontend.selectFromPublishedBrowserView(Some(0L)) {
                     selected.incrementAndGet(); ()
                   },
                   Some(0L),
                   0L
                 )
      _         <- entered.future
      _         <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      _         <- frontend.outgoingMessages.pull()
      _          = release.success(())
      _         <- obsolete
      _         <- incoming.enqueue("[6]")
      heartbeat <- frontend.outgoingMessages.pull()
      _         <- frontend.close()
    } yield {
      selected.get() shouldBe 0
      heartbeat shouldBe Some("[16]")
    })
  }

  it should "discard an obsolete custom callback and run the next current one" in {
    val incoming = Queue[Future, String]()
    val frontend = browser(incoming)
    val received = new ConcurrentLinkedQueue[String]()
    val current  = Promise[Unit]()
    bounded(for {
      _ <- frontend.registerCustomCallback("counter") { value =>
             Future.successful { received.add(value); if (value == "current") current.trySuccess(()); () }
           }
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      _ <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      // Both callbacks join the same serialized action queue in arrival order.
      _         <- incoming.enqueue(s"""[7,"$connection:0:1:counter:old"]""")
      _         <- incoming.enqueue(s"""[7,"$connection:1:1:counter:current"]""")
      _         <- current.future
      _         <- incoming.enqueue("[6]")
      heartbeat <- frontend.outgoingMessages.pull()
      _         <- frontend.close()
    } yield {
      received.asScala.toVector shouldBe Vector("current")
      heartbeat shouldBe Some("[16]")
    })
  }

  it should "still close on a future revision without running its operation" in {
    val frontend = browser(Queue[Future, String]())
    val invoked  = new AtomicBoolean(false)
    bounded(for {
      _       <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _       <- frontend.outgoingMessages.pull()
      failure <- frontend.runBrowserAction(Future.successful { invoked.set(true); () }, Some(1L), 0L).failed
      ended   <- frontend.outgoingMessages.pull()
    } yield {
      failure shouldBe a[SessionAccessDenied]
      invoked.get() shouldBe false
      ended shouldBe None
    })
  }

  it should "still close on revoked authority at the current revision" in {
    val revoked = new AtomicBoolean(false)
    val frontend =
      browser(Queue[Future, String](), () => if (revoked.get()) Future.failed(new SessionAccessDenied) else Future.unit)
    val invoked = new AtomicBoolean(false)
    bounded(for {
      _       <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _       <- frontend.outgoingMessages.pull()
      _        = revoked.set(true)
      failure <- frontend.runBrowserAction(Future.successful { invoked.set(true); () }, Some(0L), 0L).failed
      ended   <- frontend.outgoingMessages.pull()
    } yield {
      failure shouldBe a[SessionAccessDenied]
      invoked.get() shouldBe false
      ended shouldBe None
    })
  }

  it should "observe revoked authority even when the browser event is obsolete" in {
    val revoked = new AtomicBoolean(false)
    val frontend =
      browser(Queue[Future, String](), () => if (revoked.get()) Future.failed(new SessionAccessDenied) else Future.unit)
    val invoked = new AtomicBoolean(false)
    bounded(for {
      _       <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _       <- frontend.outgoingMessages.pull()
      _       <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      _       <- frontend.outgoingMessages.pull()
      _        = revoked.set(true)
      failure <- frontend.runBrowserAction(Future.successful { invoked.set(true); () }, Some(0L), 0L).failed
      ended   <- frontend.outgoingMessages.pull()
    } yield {
      failure shouldBe a[SessionAccessDenied]
      invoked.get() shouldBe false
      ended shouldBe None
    })
  }
}
