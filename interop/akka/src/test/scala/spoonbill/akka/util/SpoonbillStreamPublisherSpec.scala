package spoonbill.akka.util

import org.reactivestreams.{Subscriber, Subscription}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.effect.{Effect, Stream}

class SpoonbillStreamPublisherSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global

  private class Probe extends Subscriber[Int] {
    var subscription = Option.empty[Subscription]
    var values = Vector.empty[Int]
    var errors = Vector.empty[Throwable]
    var completed = 0
    def onSubscribe(value: Subscription): Unit = subscription = Some(value)
    def onNext(value: Int): Unit = values :+= value
    def onError(error: Throwable): Unit = errors :+= error
    def onComplete(): Unit = completed += 1
  }

  "A stream publisher" should "pull only on demand and complete once after the terminal pull" in {
    var pending = List(1, 2)
    var pulls = 0
    val stream = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = {
        pulls += 1
        val value = pending.headOption
        pending = pending.drop(1)
        Future.successful(value)
      }
      def cancel(): Future[Unit] = Future.unit
    }
    val publisher = new SpoonbillStreamPublisher(stream, fanout = false)
    val subscriber = new Probe
    publisher.subscribe(subscriber)
    pulls shouldBe 0
    subscriber.subscription.get.request(2)
    pulls shouldBe 2
    subscriber.values shouldBe Vector(1, 2)
    subscriber.completed shouldBe 0
    subscriber.subscription.get.request(1)
    subscriber.completed shouldBe 1
    subscriber.subscription.get.request(3)
    pulls shouldBe 3
    subscriber.completed shouldBe 1
    subscriber.errors shouldBe empty
  }

  it should "report an upstream failure once and stop pulling" in {
    val error = new IllegalStateException("upstream")
    var pulls = 0
    val stream = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = { pulls += 1; Future.failed(error) }
      def cancel(): Future[Unit] = Future.unit
    }
    val publisher = new SpoonbillStreamPublisher(stream, fanout = false)
    val subscriber = new Probe
    publisher.subscribe(subscriber)
    subscriber.subscription.get.request(1)
    subscriber.subscription.get.request(1)
    pulls shouldBe 1
    subscriber.errors shouldBe Vector(error)
    subscriber.completed shouldBe 0
  }

  it should "cancel the underlying stream even while waiting for demand" in {
    var canceled = 0
    val stream = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = fail("No pull without demand")
      def cancel(): Future[Unit] = { canceled += 1; Future.unit }
    }
    val publisher = new SpoonbillStreamPublisher(stream, fanout = false)
    val subscriber = new Probe
    publisher.subscribe(subscriber)
    subscriber.subscription.get.cancel()
    canceled shouldBe 1
    subscriber.values shouldBe empty
  }

  it should "reject an additional subscriber after the first subscribe returns" in {
    val publisher = new SpoonbillStreamPublisher(Stream.empty[Future, Int], fanout = false)
    publisher.subscribe(new Probe)
    intercept[SpoonbillStreamPublisher.MultipleSubscribersProhibitedException](publisher.subscribe(new Probe))
  }

  it should "claim its single subscriber before invoking onSubscribe" in {
    val publisher = new SpoonbillStreamPublisher(Stream.empty[Future, Int], fanout = false)
    val nested = new Probe
    val first = new Probe {
      override def onSubscribe(value: Subscription): Unit = {
        super.onSubscribe(value)
        intercept[SpoonbillStreamPublisher.MultipleSubscribersProhibitedException](publisher.subscribe(nested))
      }
    }
    publisher.subscribe(first)
    first.subscription should not be empty
    nested.subscription shouldBe empty
  }
}
