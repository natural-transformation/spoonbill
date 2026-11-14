package spoonbill.internal

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.Reports

/**
 * The extension lane under load, on a real thread pool.
 *
 * The time limits are generous. They do not measure speed. They fail when
 * adding or delivering becomes quadratic, overflows the stack or stops. Each
 * limit covers the whole test: adding the notifications and delivering them.
 */
class ExtensionVolumeSpec extends AnyFlatSpec with Matchers {

  private given Effect[Future] = Effect.futureEffect

  private val unbounded = new ExtensionRuntime.Bound[Future] {
    def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
  }

  /**
   * Counts what it receives and notes any state or message that arrives out of
   * order.
   */
  private final class Counter(expected: Int, onFirstState: () => Future[Unit] = () => Future.unit) {
    val states              = new AtomicInteger(0)
    val messages            = new AtomicInteger(0)
    val disordered          = new AtomicBoolean(false)
    val overlapped          = new AtomicBoolean(false)
    val complete            = new CountDownLatch(1)
    private val running     = new AtomicInteger(0)
    private var lastState   = -1
    private var lastMessage = 0

    private def counted(body: => Future[Unit]): Future[Unit] = {
      if (running.incrementAndGet() != 1) overlapped.set(true)
      val result = body
      running.decrementAndGet()
      if (states.get() == expected + 1 && messages.get() == expected) complete.countDown()
      result
    }

    val extension: Extension[Future, Int, String] = Extension.pure[Future, Int, String]("counter") { _ =>
      Extension.Handlers[Future, Int, String](
        onState = state =>
          counted {
            if (state <= lastState) disordered.set(true)
            lastState = state
            if (states.incrementAndGet() == 1) onFirstState() else Future.unit
          },
        onMessage = message =>
          counted {
            if (message.toInt != lastMessage + 1) disordered.set(true)
            lastMessage = message.toInt
            messages.incrementAndGet()
            Future.unit
          }
      )
    }
  }

  private val limit = 30.seconds

  /** The time since `started` must stay under the limit. */
  private def withinLimit(started: Long): Unit =
    (System.nanoTime() - started).nanos should be < limit

  private def withPool[A](run: ExecutionContext => A): A = {
    val pool = Executors.newFixedThreadPool(4)
    try run(ExecutionContext.fromExecutorService(pool))
    finally pool.shutdownNow()
  }

  private def start(counter: Counter)(using ExecutionContext): ExtensionRuntime[Future, Int, String] = {
    val idle = Extension.pure[Future, Int, String]("idle")(_ => Extension.Handlers[Future, Int, String]())
    val runtime =
      new ExtensionRuntime[Future, Int, String](List(counter.extension, idle), new Reports, unbounded, None)
    Await.result(runtime.setup(null, 0, () => Future.unit).flatMap(_ => runtime.start()), 5.seconds)
    runtime
  }

  "The extension lane" should "deliver a long burst once, in order, and keep nothing queued" in withPool { pool =>
    given ExecutionContext = pool
    val total              = 50000
    val counter            = new Counter(total)
    val runtime            = start(counter)
    val started            = System.nanoTime()
    (1 to total).foreach { index =>
      runtime.state(index)
      runtime.message(index.toString)
    }
    counter.complete.await(limit.toSeconds, TimeUnit.SECONDS) shouldBe true
    withinLimit(started)
    counter.states.get() shouldBe total + 1 // The attach state, then every write.
    counter.messages.get() shouldBe total
    counter.disordered.get() shouldBe false
    counter.overlapped.get() shouldBe false
    runtime.pending shouldBe 0
  }

  it should "drain a large backlog without overflowing the stack or slowing down" in withPool { pool =>
    given ExecutionContext = pool
    val total              = 100000
    val gate               = Promise[Unit]()
    val counter            = new Counter(total, onFirstState = () => gate.future)
    val runtime            = start(counter)
    // The first callback is pending, so everything below waits in the lane.
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (counter.states.get() < 1 && System.nanoTime() < deadline) Thread.sleep(1)
    counter.states.get() shouldBe 1
    val started = System.nanoTime()
    (1 to total).foreach { index =>
      runtime.state(index)
      runtime.message(index.toString)
    }
    // One check and one call per extension wait for each notification. The
    // call of the second extension for the attach state waits too.
    runtime.pending shouldBe total * 2 * 3 + 1
    gate.success(())
    counter.complete.await(limit.toSeconds, TimeUnit.SECONDS) shouldBe true
    withinLimit(started)
    counter.disordered.get() shouldBe false
    counter.overlapped.get() shouldBe false
    runtime.pending shouldBe 0
  }

  it should "not grow while it records states before delivery starts" in withPool { pool =>
    given ExecutionContext = pool
    val runtime            = new ExtensionRuntime[Future, Int, String](Nil, new Reports, unbounded, None)
    (1 to 100000).foreach(runtime.state)
    // Only the newest state is kept, and the lane is not used yet.
    runtime.recorded shouldBe 1
    runtime.pending shouldBe 0
  }
}
