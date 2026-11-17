package spoonbill.pekko.util

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.reactivestreams.{Subscriber, Subscription}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import spoonbill.effect.{Effect, Stream}

class PublisherInvalidDemandSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global

  private class Probe extends Subscriber[Int] {
    var subscription: Subscription = _
    var action: Int => Unit = _ => ()
    val values = new ConcurrentLinkedQueue[Int]()
    val errors = new ConcurrentLinkedQueue[Throwable]()
    val inNext = new AtomicInteger()
    val overlappingError = new AtomicInteger()
    val completed = new AtomicInteger()
    def onSubscribe(value: Subscription): Unit = subscription = value
    def onNext(value: Int): Unit = {
      inNext.incrementAndGet()
      try { values.add(value); action(value) }
      finally inNext.decrementAndGet()
    }
    def onError(error: Throwable): Unit = {
      if (inNext.get() != 0) overlappingError.incrementAndGet()
      errors.add(error)
      ()
    }
    def onComplete(): Unit = { completed.incrementAndGet(); () }
  }

  private class Fixture(
    read: () => Future[Option[Int]] = () => Future.successful(Some(1)),
    scheduleOn: ExecutionContext = ExecutionContext.global
  ) {
    val pulls = new AtomicInteger()
    val cancellations = new AtomicInteger()
    val canceled = new CountDownLatch(1)
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = { pulls.incrementAndGet(); read() }
      def cancel(): Future[Unit] = {
        cancellations.incrementAndGet()
        canceled.countDown()
        Future.unit
      }
    }
    val publisher = {
      given ExecutionContext = scheduleOn
      new SpoonbillStreamPublisher(source, fanout = false)
    }
    val probe = new Probe
    def subscribe(): Unit = publisher.subscribe(probe)
    def assertRejected(): Unit = {
      probe.errors.asScala.toVector.size shouldBe 1
      probe.errors.peek() shouldBe a[IllegalArgumentException]
      probe.errors.peek().getMessage should include("positive")
      probe.overlappingError.get() shouldBe 0
      probe.completed.get() shouldBe 0
      canceled.await(5, TimeUnit.SECONDS) shouldBe true
      cancellations.get() shouldBe 1
    }
  }

  for (demand <- List(0L, -1L)) {
    "An active publisher" should s"reject demand $demand once and stop further input" in {
      val f = new Fixture
      f.subscribe()
      try {
        f.probe.subscription.request(demand)
        f.assertRejected()
        f.probe.subscription.request(1)
        f.probe.subscription.request(demand)
        f.probe.subscription.cancel()
        f.pulls.get() shouldBe 0
        f.probe.errors.size() shouldBe 1
        f.cancellations.get() shouldBe 1
      } finally f.probe.subscription.cancel()
    }
  }

  it should "defer rejection requested inside onNext until that signal returns" in {
    val f = new Fixture
    val observedInside = new AtomicInteger(-1)
    f.probe.action = _ => {
      f.probe.subscription.request(0)
      observedInside.set(f.probe.errors.size())
    }
    f.subscribe()
    try {
      f.probe.subscription.request(1)
      observedInside.get() shouldBe 0
      f.assertRejected()
      f.probe.values.asScala.toVector shouldBe Vector(1)
      f.pulls.get() shouldBe 1
    } finally f.probe.subscription.cancel()
  }

  it should "serialize a concurrent rejection with a blocked onNext without blocking request" in {
    val f = new Fixture
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val rejected = new CountDownLatch(1)
    f.probe.action = _ => { entered.countDown(); assert(release.await(5, TimeUnit.SECONDS)); () }
    f.subscribe()
    val sending = new Thread(() => f.probe.subscription.request(1))
    val rejecting = new Thread(() => {
      try { f.probe.subscription.request(0); f.probe.subscription.cancel() }
      finally rejected.countDown()
    })
    try {
      sending.start()
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      rejecting.start()
      rejected.await(1, TimeUnit.SECONDS) shouldBe true
      f.probe.errors.isEmpty shouldBe true
      release.countDown()
      sending.join(5000)
      rejecting.join(5000)
      sending.isAlive shouldBe false
      rejecting.isAlive shouldBe false
      f.assertRejected()
    } finally {
      release.countDown()
      sending.join(5000)
      if (rejecting.getState != Thread.State.NEW) rejecting.join(5000)
      f.probe.subscription.cancel()
    }
  }

  it should "report rejection without waiting for an outstanding pull" in {
    val pending = Promise[Option[Int]]()
    val f = new Fixture(() => pending.future)
    f.subscribe()
    try {
      f.probe.subscription.request(1)
      f.probe.subscription.request(0)
      f.assertRejected()
      pending.success(Some(1))
      f.probe.values.isEmpty shouldBe true
      f.probe.errors.size() shouldBe 1
    } finally { pending.trySuccess(None); f.probe.subscription.cancel() }
  }

  it should "release cleanup once when its scheduling task is refused" in {
    val attempts = new AtomicInteger()
    val rejecting = new ExecutionContext {
      def execute(task: Runnable): Unit = {
        attempts.incrementAndGet()
        throw new RejectedExecutionException("cleanup refused")
      }
      def reportFailure(error: Throwable): Unit = throw error
    }
    val f = new Fixture(scheduleOn = rejecting)
    f.subscribe()
    f.probe.subscription.request(0)
    f.assertRejected()
    attempts.get() shouldBe 1
    f.probe.subscription.cancel()
    f.cancellations.get() shouldBe 1
  }

  it should "claim cleanup once when cancellation races invalid demand" in {
    for (_ <- 1 to 50) {
      val f = new Fixture
      f.subscribe()
      val start = new CountDownLatch(1)
      val cancel = new Thread(() => { assert(start.await(5, TimeUnit.SECONDS)); f.probe.subscription.cancel() })
      val reject = new Thread(() => { assert(start.await(5, TimeUnit.SECONDS)); f.probe.subscription.request(0) })
      cancel.start()
      reject.start()
      start.countDown()
      cancel.join(5000)
      reject.join(5000)
      cancel.isAlive shouldBe false
      reject.isAlive shouldBe false
      f.canceled.await(5, TimeUnit.SECONDS) shouldBe true
      f.cancellations.get() shouldBe 1
      f.probe.errors.size() should be <= 1
      f.probe.values.isEmpty shouldBe true
      f.probe.completed.get() shouldBe 0
    }
  }
}
