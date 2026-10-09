package spoonbill.akka.util

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import org.reactivestreams.Subscription
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.effect.Effect

class SpoonbillStreamSubscriberSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private class ProbeSubscription extends Subscription {
    val requested = new AtomicLong(0L)
    val cancellations = new AtomicInteger(0)
    val requestsAfterCancel = new AtomicInteger(0)
    def request(n: Long): Unit = {
      if (cancellations.get() > 0) requestsAfterCancel.incrementAndGet()
      requested.addAndGet(n)
      ()
    }
    def cancel(): Unit = { cancellations.incrementAndGet(); () }
  }

  "SpoonbillStreamSubscriber" should "remember cancel before onSubscribe and cancel the arriving subscription once" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    for {
      _ <- subscriber.cancel()
      _ <- subscriber.cancel()
      _ = subscriber.onSubscribe(subscription)
      _ <- subscriber.cancel()
      value <- subscriber.pull()
    } yield {
      value shouldBe None
      subscription.cancellations.get() shouldBe 1
      subscription.requested.get() shouldBe 0L
      subscription.requestsAfterCancel.get() shouldBe 0
    }
  }

  it should "complete pending demand on early cancellation without requesting it later" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    val pending = subscriber.pull()
    for {
      _ <- subscriber.cancel()
      value <- pending
      _ = subscriber.onSubscribe(subscription)
      _ = subscriber.onNext(99)
      next <- subscriber.pull()
    } yield {
      value shouldBe None
      next shouldBe None
      subscription.cancellations.get() shouldBe 1
      subscription.requested.get() shouldBe 0L
    }
  }

  it should "forward deferred demand and deliver every requested frame in order before completion" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    val pending = Vector.fill(3)(subscriber.pull())
    subscriber.onSubscribe(subscription)
    subscription.requested.get() shouldBe 3L
    subscriber.onNext(10)
    subscriber.onNext(20)
    subscriber.onNext(30)
    subscriber.onComplete()
    for {
      values <- Future.sequence(pending)
      terminal <- subscriber.pull()
    } yield {
      values shouldBe Vector(Some(10), Some(20), Some(30))
      terminal shouldBe None
      subscription.requested.get() shouldBe 3L
      subscription.requestsAfterCancel.get() shouldBe 0
    }
  }

  it should "support a synchronous publisher response to demand" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val value = new AtomicInteger(0)
    val subscription = new ProbeSubscription {
      override def request(n: Long): Unit = {
        super.request(n)
        (0L until n).foreach(_ => subscriber.onNext(value.incrementAndGet()))
      }
    }
    subscriber.onSubscribe(subscription)
    for {
      first <- subscriber.pull()
      second <- subscriber.pull()
      _ = subscriber.onComplete()
      terminal <- subscriber.pull()
    } yield {
      first shouldBe Some(1)
      second shouldBe Some(2)
      terminal shouldBe None
      subscription.requested.get() shouldBe 2L
    }
  }

  it should "cancel an attached subscription once and ignore subsequent signals" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    subscriber.onSubscribe(subscription)
    val pending = subscriber.pull()
    for {
      _ <- subscriber.cancel()
      value <- pending
      _ = subscriber.onNext(99)
      _ = subscriber.onComplete()
      _ = subscriber.onError(new IllegalStateException("late error"))
      _ <- subscriber.cancel()
      terminal <- subscriber.pull()
    } yield {
      value shouldBe None
      terminal shouldBe None
      subscription.requested.get() shouldBe 1L
      subscription.cancellations.get() shouldBe 1
      subscription.requestsAfterCancel.get() shouldBe 0
    }
  }

  it should "retain an upstream error through later completion and cancellation" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    subscriber.onSubscribe(subscription)
    val pending = subscriber.pull()
    val error = new IllegalStateException("upstream failed")
    subscriber.onError(error)
    subscriber.onComplete()
    for {
      first <- pending.failed
      _ <- subscriber.cancel()
      next <- subscriber.pull().failed
    } yield {
      first shouldBe error
      next shouldBe error
      subscription.cancellations.get() shouldBe 1
      subscription.requested.get() shouldBe 1L
    }
  }

  it should "reject another subscription without replacing the active one" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val active = new ProbeSubscription
    val duplicate = new ProbeSubscription
    subscriber.onSubscribe(active)
    subscriber.onSubscribe(duplicate)
    val pending = subscriber.pull()
    subscriber.onNext(7)
    for {
      value <- pending
      _ <- subscriber.cancel()
    } yield {
      value shouldBe Some(7)
      active.requested.get() shouldBe 1L
      active.cancellations.get() shouldBe 1
      duplicate.requested.get() shouldBe 0L
      duplicate.cancellations.get() shouldBe 1
    }
  }

  it should "serialize a racing subscription and cancellation without demand after cancel" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    val pending = subscriber.pull()
    val start = Promise[Unit]()
    val subscribed = start.future.map(_ => subscriber.onSubscribe(subscription))(ExecutionContext.global)
    val canceled = start.future.flatMap(_ => subscriber.cancel())(ExecutionContext.global)
    start.success(())
    for {
      _ <- subscribed
      _ <- canceled
      value <- pending
      _ <- subscriber.cancel()
      terminal <- subscriber.pull()
    } yield {
      value shouldBe None
      terminal shouldBe None
      subscription.cancellations.get() shouldBe 1
      subscription.requestsAfterCancel.get() shouldBe 0
      Set(0L, 1L) should contain(subscription.requested.get())
    }
  }

  it should "settle an in-flight frame racing cancellation exactly once" in {
    val subscriber = new SpoonbillStreamSubscriber[Future, Int]
    val subscription = new ProbeSubscription
    subscriber.onSubscribe(subscription)
    val pending = subscriber.pull()
    val start = Promise[Unit]()
    val delivered = start.future.map(_ => subscriber.onNext(7))(ExecutionContext.global)
    val canceled = start.future.flatMap(_ => subscriber.cancel())(ExecutionContext.global)
    start.success(())
    for {
      _ <- delivered
      _ <- canceled
      value <- pending
      terminal <- subscriber.pull()
    } yield {
      (value.isEmpty || value.contains(7)) shouldBe true
      terminal shouldBe None
      subscription.cancellations.get() shouldBe 1
      subscription.requestsAfterCancel.get() shouldBe 0
    }
  }
}
