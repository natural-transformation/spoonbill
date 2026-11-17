/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.pekko.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.Objects
import spoonbill.effect.{Effect, Hub, Reporter, StateCell, Stream}
import spoonbill.internal.StreamPublisherMachine
import spoonbill.effect.syntax._
import spoonbill.pekko.util.SpoonbillStreamPublisher.MultipleSubscribersProhibitedException
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

final class SpoonbillStreamPublisher[F[_]: Effect, T](stream: Stream[F, T], fanout: Boolean)(implicit
  ec: ExecutionContext
) extends Publisher[T] {

  private implicit val reporter: Reporter = Reporter.PrintReporter

  private val hasActualSubscriber = new AtomicBoolean(false)

  private val hub =
    if (fanout) Hub(stream)
    else null

  private final class StreamSubscription(stream: Stream[F, T], subscriber: Subscriber[_ >: T]) extends Subscription {

    private val countdown = new Countdown[F]()
    import StreamPublisherMachine.{Command, Event, Signal}
    private val lifecycle = new StateCell(StreamPublisherMachine.initial)
    @volatile private var signal = Signal.Idle
    private def live: Boolean = StreamPublisherMachine.live(lifecycle.get)
    private def decide(event: Event): Command =
      if (!live) Command.None else lifecycle.modify(StreamPublisherMachine.step(_, event))

    private def reportRejectedDemand(): Unit = {
      val observed = signal
      if (StreamPublisherMachine.canReportDemand(lifecycle.get, observed)) {
        val event = Event.ReportDemand(observed)
        if (lifecycle.modify(StreamPublisherMachine.step(_, event)) == Command.ReportDemand) {
          try subscriber.onError(new IllegalArgumentException("Reactive Streams demand must be positive"))
          catch { case NonFatal(error) => ec.reportFailure(error) }
        }
      }
    }

    private def emit(value: T): Unit = {
      // Publish intent before rereading lifecycle. A rejecting request either
      // sees this marker or fences the call before application code starts.
      signal = Signal.Entering
      try { if (live) subscriber.onNext(value) }
      finally { signal = Signal.Idle; reportRejectedDemand() }
    }

    private def cancelRejected(): Unit = {
      val claimed = new AtomicBoolean(false)
      val task = new Runnable {
        def run(): Unit = if (claimed.compareAndSet(false, true)) {
          try {
            try countdown.unsafeAdd(1)
            finally Effect[F].delayAsync(stream.cancel()).runAsyncForget
          } catch { case NonFatal(error) => ec.reportFailure(error) }
        }
      }
      // A request made inside onNext cannot wait for cleanup which may itself
      // await that signal's completion. Refused scheduling still releases once.
      try ec.execute(task)
      catch { case NonFatal(_) => task.run() }
    }

    private def loop(): F[Unit] =
      for {
        _         <- countdown.decOrLock()
        maybeItem <- if (!live) Effect[F].none[T] else stream.pull()
        _ <- if (!live) Effect[F].unit else maybeItem match {
               case Some(item) =>
                 emit(item)
                 loop()
               case None =>
                 if (decide(Event.Complete) == Command.Complete) subscriber.onComplete()
                 Effect[F].unit
             }
      } yield ()

    loop().runAsync {
      case Left(error) => if (decide(Event.Fail) == Command.Fail) subscriber.onError(error)
      case Right(_)    => ()
    }

    def request(n: Long): Unit =
      if (n > 0) { if (live) countdown.unsafeAdd(n) }
      else if (decide(Event.RejectDemand) == Command.RejectDemand) {
        try reportRejectedDemand()
        finally cancelRejected()
      }

    def cancel(): Unit =
      if (decide(Event.Cancel) == Command.CancelSource) {
        // Release a loop waiting for demand; it observes stopped before pulling.
        countdown.unsafeAdd(1)
        stream.cancel().runAsyncForget
      }
  }

  def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
    Objects.requireNonNull(subscriber, "subscriber")
    if (hub != null) {
      hub.newStream().runAsyncSuccess { newStream =>
        val subscription = new StreamSubscription(newStream, subscriber)
        subscriber.onSubscribe(subscription)
      }
    } else {
      // Claim before application code can reenter subscribe, and atomically
      // against another thread attempting the same single-subscriber slot.
      if (!hasActualSubscriber.compareAndSet(false, true))
        throw MultipleSubscribersProhibitedException()
      subscriber.onSubscribe(new StreamSubscription(stream, subscriber))
    }
  }
}

object SpoonbillStreamPublisher {
  final case class MultipleSubscribersProhibitedException()
      extends Exception("Multiple subscribers prohibited for this SpoonbillStreamPublisher")
}
