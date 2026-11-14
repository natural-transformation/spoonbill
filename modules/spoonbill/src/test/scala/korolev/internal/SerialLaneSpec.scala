package spoonbill.internal

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.Worker

class SerialLaneSpec extends AnyFlatSpec with Matchers {

  private given Effect[Future] = Effect.futureEffect

  private final class Item(val id: Int)

  /** A lane on a manual worker. It records the handled ids. */
  private final class Fixture(handle: Int => Future[Unit] = _ => Future.unit) {
    val worker                     = new Worker
    private given ExecutionContext = worker
    val handled                    = new ConcurrentLinkedQueue[Int]()
    val failures                   = new ConcurrentLinkedQueue[(Int, Throwable)]()
    val lane = new SerialLane[Future, Item](
      item => {
        handled.add(item.id)
        handle(item.id)
      },
      (item, error) => { failures.add(item.id -> error); () }
    )
    def offer(ids: Int*): Unit = ids.foreach(id => lane.offer(new Item(id)))
    def taken(): List[Int] = {
      worker.drain()
      val result = handled.asScala.toList
      handled.clear()
      result
    }
  }

  "A serial lane" should "handle items in the order they were added" in {
    val f = new Fixture
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1, 2, 3)
    f.lane.size shouldBe 0
  }

  it should "not run the handler on the adding thread" in {
    val f = new Fixture
    f.offer(1, 2)
    f.handled.isEmpty shouldBe true
    // One wake-up serves both items.
    f.worker.pending shouldBe 1
    f.taken() shouldBe List(1, 2)
  }

  it should "start the next item only when the previous handler has completed" in {
    val gate = Promise[Unit]()
    val f    = new Fixture(id => if (id == 1) gate.future else Future.unit)
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1)
    gate.success(())
    f.taken() shouldBe List(2, 3)
  }

  it should "go on in a new task after a handler that completed later" in {
    val gate = Promise[Unit]()
    val f    = new Fixture(id => if (id == 1) gate.future else Future.unit)
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1)
    // This thread completes the handler. It must not run the next ones.
    gate.success(())
    f.handled.isEmpty shouldBe true
    f.worker.pending shouldBe 1
    f.taken() shouldBe List(2, 3)
  }

  it should "pass a handler that fails or throws to the failure hook, and keep going" in {
    val failed = new IllegalStateException("failed")
    val thrown = new IllegalStateException("thrown")
    val f = new Fixture(id =>
      if (id == 1) Future.failed(failed)
      else if (id == 2) throw thrown
      else Future.unit
    )
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1, 2, 3)
    f.failures.asScala.toList shouldBe List(1 -> failed, 2 -> thrown)
  }

  it should "keep going when the failure hook itself throws" in {
    val worker             = new Worker
    given ExecutionContext = worker
    val handled            = new ConcurrentLinkedQueue[Int]()
    val lane = new SerialLane[Future, Item](
      item => { handled.add(item.id); Future.failed(new IllegalStateException("failed")) },
      (_, _) => throw new IllegalStateException("hook")
    )
    lane.offer(new Item(1))
    lane.offer(new Item(2))
    worker.drain()
    handled.asScala.toList shouldBe List(1, 2)
  }

  it should "not deepen the stack when many handlers complete at once" in {
    val f = new Fixture
    // The Future effect runs callbacks on the completing thread. A recursive
    // loop would overflow the stack long before this many items.
    f.offer((1 to 200000)*)
    f.taken().size shouldBe 200000
  }

  it should "drop the waiting items when it is closed, and finish the handler in flight" in {
    val gate = Promise[Unit]()
    val f    = new Fixture(id => if (id == 1) gate.future else Future.unit)
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1)
    f.lane.close()
    val idle = f.lane.idle
    idle.isCompleted shouldBe false
    gate.success(())
    f.taken() shouldBe Nil
    Await.result(idle, 1.second)
    f.offer(4)
    f.taken() shouldBe Nil
  }

  it should "be idle at once when it is closed and nothing runs" in {
    val f    = new Fixture
    val idle = f.lane.idle
    // An open lane can still start a handler.
    idle.isCompleted shouldBe false
    f.lane.close()
    Await.result(idle, 1.second)
    Await.result(f.lane.idle, 1.second)
  }

  it should "be idle at once when it is closed before a scheduled loop has started" in {
    val f = new Fixture
    f.offer(1)
    // The loop is submitted, but the worker has not run it.
    f.worker.pending shouldBe 1
    f.lane.close()
    Await.result(f.lane.idle, 1.second)
    // The stale task finds the lane closed.
    f.taken() shouldBe Nil
  }

  it should "be idle at once when it is closed after a handler completed and before the next task ran" in {
    val gate = Promise[Unit]()
    val f    = new Fixture(id => if (id == 1) gate.future else Future.unit)
    f.offer(1, 2)
    f.taken() shouldBe List(1)
    gate.success(())
    f.lane.close()
    Await.result(f.lane.idle, 1.second)
    f.taken() shouldBe Nil
  }

  it should "keep the items when the execution context refuses the loop, and handle them at the next wake-up" in {
    val refuse  = new AtomicBoolean(true)
    val worker  = new Worker
    val refused = new RejectedExecutionException("refused")
    given ExecutionContext = new ExecutionContext {
      def execute(runnable: Runnable): Unit     = if (refuse.get()) throw refused else worker.execute(runnable)
      def reportFailure(cause: Throwable): Unit = worker.reportFailure(cause)
    }
    val handled  = new ConcurrentLinkedQueue[Int]()
    val rejected = new ConcurrentLinkedQueue[Throwable]()
    val lane = new SerialLane[Future, Item](
      item => { handled.add(item.id); Future.unit },
      rejected = error => { rejected.add(error); () }
    )
    lane.offer(new Item(1))
    rejected.asScala.toList shouldBe List(refused)
    lane.size shouldBe 1
    refuse.set(false)
    lane.offer(new Item(2))
    worker.drain()
    handled.asScala.toList shouldBe List(1, 2)
  }

  it should "never report idle while a handler runs, under racing writers and closes" in {
    val pool               = Executors.newFixedThreadPool(4)
    given ExecutionContext = ExecutionContext.fromExecutorService(pool)
    try
      (1 to 3000).foreach { round =>
        val inFlight = new AtomicInteger(0)
        val lane = new SerialLane[Future, Item](_ => {
          inFlight.incrementAndGet()
          // Settles on another thread, so the loop gives the lane up in between.
          Future { inFlight.decrementAndGet(); () }
        })
        val writer = new Thread(() => (1 to 20).foreach(id => lane.offer(new Item(id))))
        writer.start()
        if (round % 2 == 0) Thread.`yield`()
        lane.close()
        Await.result(lane.idle, 10.seconds)
        // Closed and idle: no handler runs, and none starts afterwards.
        inFlight.get() shouldBe 0
        writer.join()
        inFlight.get() shouldBe 0
      }
    finally pool.shutdownNow()
  }

  it should "handle every item once when many threads add at the same time" in {
    val pool               = Executors.newFixedThreadPool(8)
    given ExecutionContext = ExecutionContext.fromExecutorService(pool)
    val producers          = 8
    val perThread          = 5000
    val handled            = new ConcurrentLinkedQueue[Int]()
    val running            = new AtomicInteger(0)
    val overlap            = new AtomicInteger(0)
    val done               = new CountDownLatch(producers * perThread)
    val lane = new SerialLane[Future, Item](item => {
      if (running.incrementAndGet() != 1) overlap.incrementAndGet()
      handled.add(item.id)
      running.decrementAndGet()
      done.countDown()
      Future.unit
    })
    try {
      val threads = (0 until producers).map { producer =>
        new Thread(() => (0 until perThread).foreach(index => lane.offer(new Item(producer * perThread + index))))
      }
      threads.foreach(_.start())
      threads.foreach(_.join())
      done.await(30, TimeUnit.SECONDS) shouldBe true
      overlap.get() shouldBe 0
      val ids = handled.asScala.toVector
      ids.size shouldBe producers * perThread
      ids.distinct.size shouldBe ids.size
      // Each producer's own items keep their order.
      (0 until producers).foreach { producer =>
        val own = ids.filter(_ / perThread == producer)
        own shouldBe own.sorted
      }
    } finally pool.shutdownNow()
  }
}
