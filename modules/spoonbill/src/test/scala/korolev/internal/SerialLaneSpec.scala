package spoonbill.internal

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
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
    val lane = new SerialLane[Future, Item](item => {
      handled.add(item.id)
      handle(item.id)
    })
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

  it should "keep going after a handler fails or throws" in {
    val f = new Fixture(id =>
      if (id == 1) Future.failed(new IllegalStateException("failed"))
      else if (id == 2) throw new IllegalStateException("thrown")
      else Future.unit
    )
    f.offer(1, 2, 3)
    f.taken() shouldBe List(1, 2, 3)
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

  it should "be idle at once when nothing runs" in {
    val f = new Fixture
    Await.result(f.lane.idle, 1.second)
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
