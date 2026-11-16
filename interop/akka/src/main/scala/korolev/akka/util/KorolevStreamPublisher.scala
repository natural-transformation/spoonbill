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

package spoonbill.akka.util

import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.akka.util.SpoonbillStreamPublisher.MultipleSubscribersProhibitedException
import spoonbill.effect.{Effect, Hub, Reporter, Stream}
import spoonbill.effect.syntax._
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

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
    private val stopped = new AtomicBoolean(false)

    private def loop(): F[Unit] =
      for {
        _         <- countdown.decOrLock()
        maybeItem <- if (stopped.get()) Effect[F].none[T] else stream.pull()
        _ <- if (stopped.get()) Effect[F].unit else maybeItem match {
               case Some(item) =>
                 subscriber.onNext(item)
                 loop()
               case None =>
                 if (stopped.compareAndSet(false, true)) subscriber.onComplete()
                 Effect[F].unit
             }
      } yield ()

    loop().runAsync {
      case Left(error) => if (stopped.compareAndSet(false, true)) subscriber.onError(error)
      case Right(_)    => ()
    }

    def request(n: Long): Unit =
      if (!stopped.get()) countdown.unsafeAdd(n)

    def cancel(): Unit =
      if (stopped.compareAndSet(false, true)) {
        // Release a loop waiting for demand; it observes stopped before pulling.
        countdown.unsafeAdd(1)
        stream.cancel().runAsyncForget
      }
  }

  def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
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
