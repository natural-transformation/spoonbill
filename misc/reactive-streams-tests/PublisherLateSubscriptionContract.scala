package spoonbill.reactivestreams

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.effect.{Effect, Stream}

/**
 * Adapter-independent signal laws exercised through each public converter and
 * real stream materializer.
 */
abstract class PublisherLateSubscriptionContract extends AnyFlatSpec with Matchers {
  protected def fanout(input: Stream[Future, Int])(using Effect[Future], ExecutionContext): Publisher[Int]
  protected def materialize(publisher: Publisher[Int]): Future[Seq[Int]]
  private given Effect[Future] = Effect.futureEffect
  private val reported         = new ConcurrentLinkedQueue[Throwable]()
  private given ExecutionContext = new ExecutionContext {
    def execute(task: Runnable): Unit         = task.run()
    def reportFailure(error: Throwable): Unit = { reported.add(error); () }
  }
  protected final def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private class Probe extends Subscriber[Int] {
    var signals                    = Vector.empty[String]
    var subscription: Subscription = null
    var errors                     = Vector.empty[Throwable]
    def onSubscribe(value: Subscription): Unit = {
      signals :+= "subscribe"
      subscription = value
    }
    def onNext(value: Int): Unit        = signals :+= s"next:$value"
    def onError(error: Throwable): Unit = { signals :+= "error"; errors :+= error }
    def onComplete(): Unit              = signals :+= "complete"
  }

  private def endedPublisher(failed: Boolean) = {
    val original = new IllegalStateException("source failed")
    val reads    = new AtomicInteger()
    val input = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = {
        reads.incrementAndGet()
        if (failed) Future.failed(original) else Future.successful(None)
      }
      def cancel(): Future[Unit] = Future.unit
    }
    val publisher = fanout(input)
    val first     = new Probe
    publisher.subscribe(first)
    first.subscription.request(1)
    if (failed) first.errors shouldBe Vector(original)
    else first.signals shouldBe Vector("subscribe", "complete")
    (publisher, reads)
  }

  for (failed <- List(false, true)) {
    "A public fanout publisher" should s"signal subscription before rejecting a late subscriber (failed=$failed)" in {
      val (publisher, reads) = endedPublisher(failed)
      val late = new Probe {
        override def onSubscribe(value: Subscription): Unit = {
          super.onSubscribe(value)
          // The rejected subscription is inert even for reentrant requests.
          value.request(0)
          value.request(Long.MaxValue)
          value.cancel()
        }
      }
      publisher.subscribe(late)
      late.signals shouldBe Vector("subscribe", "error")
      late.errors should have size 1
      late.errors.head shouldBe a[IllegalStateException]
      late.errors.head.getMessage shouldBe "Hub is closed"
      late.subscription.request(1)
      late.subscription.cancel()
      late.signals shouldBe Vector("subscribe", "error")
      reads.get() shouldBe 1
    }

    it should s"terminate an actual late materialized sink instead of leaving it waiting (failed=$failed)" in {
      val original = new IllegalStateException("materialized source failed")
      val reads    = new AtomicInteger()
      val input = new Stream[Future, Int] {
        def pull(): Future[Option[Int]] = {
          val next = reads.getAndIncrement()
          if (failed) Future.failed(original)
          else Future.successful(if (next < 2) Some(next) else None)
        }
        def cancel(): Future[Unit] = Future.unit
      }
      val publisher = fanout(input)
      val first     = materialize(publisher)
      if (failed) await(first.failed) should be theSameInstanceAs original
      else await(first) shouldBe Seq(0, 1)
      val before    = reads.get()
      val late      = materialize(publisher)
      val rejection = await(late.failed)
      rejection shouldBe a[IllegalStateException]
      rejection.getMessage shouldBe "Hub is closed"
      reads.get() shouldBe before
    }
  }

  it should "report a throwing rejection onSubscribe without sending another signal" in {
    val (publisher, _) = endedPublisher(failed = false)
    val failure        = new IllegalStateException("onSubscribe threw")
    val late = new Probe {
      override def onSubscribe(value: Subscription): Unit = {
        super.onSubscribe(value)
        throw failure
      }
    }
    publisher.subscribe(late)
    late.signals shouldBe Vector("subscribe")
    reported.asScala.count(_ eq failure) shouldBe 1
  }

  it should "report a throwing rejection onError once" in {
    val (publisher, _) = endedPublisher(failed = false)
    val failure        = new IllegalStateException("onError threw")
    val late = new Probe {
      override def onError(error: Throwable): Unit = {
        super.onError(error)
        throw failure
      }
    }
    publisher.subscribe(late)
    late.signals shouldBe Vector("subscribe", "error")
    reported.asScala.count(_ eq failure) shouldBe 1
  }

  it should "reject null synchronously even after fanout has terminated" in {
    val (publisher, _) = endedPublisher(failed = false)
    intercept[NullPointerException](publisher.subscribe(null))
  }
}
