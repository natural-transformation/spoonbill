package spoonbill.akka.util

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.reactivestreams.{Subscriber, Subscription}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import spoonbill.effect.{Effect, Stream}

final class StreamPublisherFanoutCapacitySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]   = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.parasitic

  private final class Probe extends Subscriber[Int] {
    private val ready                                 = new CountDownLatch(1)
    private val reachedCapacity                       = new CountDownLatch(128)
    private val terminal                              = new CountDownLatch(1)
    private val current                               = new AtomicReference[Subscription]()
    val values                                        = new ConcurrentLinkedQueue[Int]()
    val errors                                        = new ConcurrentLinkedQueue[Throwable]()
    val completions                                   = new AtomicInteger()
    def onSubscribe(subscription: Subscription): Unit = { current.set(subscription); ready.countDown() }
    def onNext(value: Int): Unit                      = { values.add(value); reachedCapacity.countDown() }
    def onError(error: Throwable): Unit               = { errors.add(error); terminal.countDown() }
    def onComplete(): Unit                            = { completions.incrementAndGet(); terminal.countDown() }
    def subscription: Subscription = {
      assert(ready.await(5, TimeUnit.SECONDS), "Subscriber was not registered")
      current.get()
    }
    def awaitCapacity(): Unit =
      assert(reachedCapacity.await(5, TimeUnit.SECONDS), "Expected the admitted 128-element prefix")
    def awaitCompletion(): Unit = {
      assert(terminal.await(5, TimeUnit.SECONDS), "Publisher did not terminate")
      errors.asScala.toVector shouldBe empty
      completions.get() shouldBe 1
    }
  }

  private final class Fixture(total: Int) {
    val reads         = new AtomicInteger()
    val cancellations = new AtomicInteger()
    private val input = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = {
        val next = reads.incrementAndGet()
        Future.successful(Option.when(next <= total)(next))
      }
      def cancel(): Future[Unit] = { cancellations.incrementAndGet(); Future.unit }
    }
    private val publisher = new SpoonbillStreamPublisher(input, fanout = true)
    val fast              = new Probe
    val slow              = new Probe
    publisher.subscribe(fast)
    publisher.subscribe(slow)
    fast.subscription
    slow.subscription
    def close(): Unit = { fast.subscription.cancel(); slow.subscription.cancel() }
    def assertBounded(): Unit = {
      fast.awaitCapacity()
      reads.get() shouldBe 128
      fast.values.asScala.toVector shouldBe (1 to 128).toVector
      slow.values.asScala.toVector shouldBe empty
      fast.errors.asScala.toVector shouldBe empty
      slow.errors.asScala.toVector shouldBe empty
      fast.completions.get() shouldBe 0
      slow.completions.get() shouldBe 0
    }
  }

  "A fanout publisher" should "stop at 128 ahead of a no-demand subscriber and resume losslessly when demand arrives" in {
    val fixture = new Fixture(130)
    try {
      fixture.reads.get() shouldBe 0
      fixture.fast.subscription.request(130)
      fixture.assertBounded()
      fixture.slow.subscription.request(130)
      fixture.fast.subscription.request(1)
      fixture.slow.subscription.request(1)
      fixture.fast.awaitCompletion()
      fixture.slow.awaitCompletion()
      fixture.fast.values.asScala.toVector shouldBe (1 to 130).toVector
      fixture.slow.values.asScala.toVector shouldBe (1 to 130).toVector
      fixture.reads.get() shouldBe 131
      fixture.cancellations.get() shouldBe 0
    } finally fixture.close()
  }

  it should "release the full slow-subscriber buffer on cancellation without canceling or losing the fast stream" in {
    val fixture = new Fixture(260)
    try {
      fixture.fast.subscription.request(Long.MaxValue)
      fixture.assertBounded()
      fixture.slow.subscription.cancel()
      fixture.slow.subscription.cancel()
      fixture.fast.awaitCompletion()
      fixture.fast.values.asScala.toVector shouldBe (1 to 260).toVector
      fixture.slow.values.asScala.toVector shouldBe empty
      fixture.slow.errors.asScala.toVector shouldBe empty
      fixture.slow.completions.get() shouldBe 0
      fixture.reads.get() shouldBe 261
      fixture.cancellations.get() shouldBe 0
    } finally fixture.close()
  }
}
