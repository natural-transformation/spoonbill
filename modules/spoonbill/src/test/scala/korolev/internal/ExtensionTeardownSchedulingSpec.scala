package spoonbill.internal

import java.util.concurrent.{CountDownLatch, Executors, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.Reports

/**
 * Teardown on real executors: on which thread `onDestroy` runs, and that its
 * wait for a worker is part of its time bound.
 *
 * The other runtime specs use a manual worker. It always runs a task after the
 * caller has gone on, so it cannot show what happens when a worker is occupied
 * or when it is faster than the caller.
 */
class ExtensionTeardownSchedulingSpec extends AnyFlatSpec with Matchers with Eventually {

  private given Effect[Future] = Effect.futureEffect

  private given PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(10, Millis))

  private val unbounded = new ExtensionRuntime.Bound[Future] {
    def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
  }

  /** One extension that notes the thread of its `onDestroy` call. */
  private def noting(destroyedOn: AtomicReference[Thread]): Extension[Future, Int, String] =
    Extension.pure[Future, Int, String]("noting") { _ =>
      Extension.Handlers[Future, Int, String](onDestroy = _ => {
        destroyedOn.set(Thread.currentThread())
        Future.unit
      })
    }

  /**
   * A runtime on one worker, with one extension. Its `onState` call holds the
   * worker until `release` is opened. A bound expires when the test says so.
   */
  private final class Occupied(onDestroy: () => Future[Unit]) {
    private val pool               = Executors.newSingleThreadExecutor()
    private given ExecutionContext = ExecutionContext.fromExecutorService(pool)
    private val occupied           = new CountDownLatch(1)
    val release                    = new CountDownLatch(1)
    val manual                     = new ExtensionRuntimeFixture.ManualBound
    // How many calls have entered a bound.
    val entered = new AtomicInteger(0)
    private val bound = new ExtensionRuntime.Bound[Future] {
      def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = {
        entered.incrementAndGet()
        manual(operation, late)
      }
    }
    private val occupying = Extension.pure[Future, Int, String]("occupying") { _ =>
      Extension.Handlers[Future, Int, String](
        onState = _ => {
          occupied.countDown()
          release.await(30, TimeUnit.SECONDS)
          Future.unit
        },
        onDestroy = _ => onDestroy()
      )
    }
    val reports = new Reports
    val runtime = new ExtensionRuntime[Future, Int, String](List(occupying), reports, bound, None)

    /** Attaches, and waits until the `onState` call holds the worker. */
    def occupy(): Unit = {
      Await.result(runtime.setup(null, 0, () => Future.unit), 5.seconds)
      Await.result(runtime.start(), 5.seconds)
      occupied.await(5, TimeUnit.SECONDS) shouldBe true
    }

    /** Frees the worker and ends it, also after a failed assertion. */
    def close(): Unit = {
      release.countDown()
      pool.shutdown()
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) { pool.shutdownNow(); () }
    }
  }

  "Teardown" should "keep the wait for a worker inside the bound of onDestroy" in {
    val destroyed = new CountDownLatch(1)
    val f = new Occupied(() => {
      destroyed.countDown()
      Future.unit
    })
    try {
      f.occupy()
      f.entered.get() shouldBe 1 // The setup.
      val done = f.runtime.destroy()
      f.entered.get() shouldBe 2 // The wait for the callback in flight.
      done.isCompleted shouldBe false

      // That wait runs out. No worker is free, and still the destructor is
      // inside its own bound: the teardown waits nowhere without one.
      f.manual.expire()
      f.entered.get() shouldBe 3
      done.isCompleted shouldBe false

      // The bound of the destructor runs out too, and the teardown ends.
      f.manual.expire()
      Await.result(done, 5.seconds)
      f.reports.errors.map(_._1) shouldBe Vector(
        "An extension callback was still running at teardown",
        "Extension occupying: onDestroy failed"
      )
      f.reports.errors.last._2 shouldBe a[ExtensionRuntime.TimedOut]

      // The destructor is not lost. It runs when the worker is free again.
      f.release.countDown()
      destroyed.await(5, TimeUnit.SECONDS) shouldBe true
    } finally f.close()
  }

  it should "report an onDestroy that fails after its bound has expired" in {
    val failure = new IllegalStateException("release failed")
    val f       = new Occupied(() => Future.failed(failure))
    try {
      f.occupy()
      val done = f.runtime.destroy()
      f.manual.expire() // The wait for the callback in flight.
      f.manual.expire() // The bound of the destructor. Its task is still queued.
      Await.result(done, 5.seconds)
      f.reports.errors should have size 2
      f.reports.errors.last._2 shouldBe a[ExtensionRuntime.TimedOut]

      // The worker is free again, so the call is made now, and it fails. The
      // bound has given up on it. The reason of the failure is reported all
      // the same: what the extension held may not be released.
      f.release.countDown()
      eventually(f.reports.errors should have size 3)
      f.reports.errors.last shouldBe ("Extension occupying: onDestroy failed" -> failure)
    } finally f.close()
  }

  it should "call onDestroy inside the task, also when the worker is faster than the caller" in {
    // Every task runs on a thread of its own, and has finished when `execute`
    // returns. A thread pool can win that race too. With `Future`, code behind
    // a promise that is already complete runs on the thread that asks for it,
    // so a task that only completed a promise would leave the call on the
    // caller.
    val threads = new AtomicInteger(0)
    given ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit = {
        val worker = new Thread(task, s"faster-worker-${threads.incrementAndGet()}")
        worker.setDaemon(true)
        worker.start()
        worker.join(5000)
      }
      def reportFailure(error: Throwable): Unit = throw error
    }
    val destroyedOn = new AtomicReference[Thread]()
    val runtime     = new ExtensionRuntime[Future, Int, String](List(noting(destroyedOn)), new Reports, unbounded, None)
    Await.result(runtime.setup(null, 0, () => Future.unit), 5.seconds)
    Await.result(runtime.destroy(), 5.seconds)
    destroyedOn.get() should not be theSameInstanceAs(Thread.currentThread())
    destroyedOn.get().getName should startWith("faster-worker-")
  }

  it should "still call onDestroy when the execution context refuses the task" in {
    given ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit         = throw new RejectedExecutionException("refused")
      def reportFailure(error: Throwable): Unit = throw error
    }
    val destroyedOn = new AtomicReference[Thread]()
    // How many calls have entered a bound, now and when onDestroy was called.
    val entered       = new AtomicInteger(0)
    val enteredAtCall = new AtomicInteger(0)
    val counting = new ExtensionRuntime.Bound[Future] {
      def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = {
        entered.incrementAndGet()
        operation
      }
    }
    val extension = Extension.pure[Future, Int, String]("noting") { _ =>
      Extension.Handlers[Future, Int, String](onDestroy = _ => {
        destroyedOn.set(Thread.currentThread())
        enteredAtCall.set(entered.get())
        Future.unit
      })
    }
    val reports = new Reports
    val runtime = new ExtensionRuntime[Future, Int, String](List(extension), reports, counting, None)
    Await.result(runtime.setup(null, 0, () => Future.unit), 5.seconds)
    Await.result(runtime.destroy(), 5.seconds)
    // What an extension acquired must be released. Without a worker the call
    // is made here.
    destroyedOn.get() should be theSameInstanceAs Thread.currentThread()
    // Also here the call is inside its bound, which is the third one after
    // the setup and the wait for a callback in flight. So a destructor that
    // blocks the calling thread still runs out of time.
    enteredAtCall.get() shouldBe 3
    reports.errors shouldBe empty
  }
}
