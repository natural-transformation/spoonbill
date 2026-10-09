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

import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Queue
import spoonbill.effect.{Effect, Stream}
import org.reactivestreams.{Subscriber, Subscription}

final class SpoonbillStreamSubscriber[F[_]: Effect, T] extends Stream[F, T] with Subscriber[T] {
  private type Result = Either[Throwable, Option[T]]
  private type Callback = Result => Unit
  private case class State(
    subscription: Option[Subscription] = None,
    waiting: Queue[Callback] = Queue.empty,
    terminal: Option[Result] = None,
    canceled: Boolean = false,
    cancellationIssued: Boolean = false
  )

  private val signalLock = new Object
  private val state = new AtomicReference(State())

  def onSubscribe(incoming: Subscription): Unit = {
    Objects.requireNonNull(incoming, "subscription")
    val reject = signalLock.synchronized {
      val before = state.get()
      before.subscription match {
        case Some(existing) if existing eq incoming => None
        case Some(_) => Some(incoming)
        case None =>
          val stop = before.canceled || before.terminal.isDefined
          state.set(before.copy(subscription = Some(incoming), cancellationIssued = stop))
          // Reactive Streams request is non-blocking and may synchronously call
          // onNext. Serialize demand with the cancellation decision so a late
          // subscription or pull can never request after cancellation.
          if (!stop && before.waiting.nonEmpty) incoming.request(before.waiting.size.toLong)
          if (stop) Some(incoming) else None
      }
    }
    reject.foreach(_.cancel())
  }

  def onNext(value: T): Unit = {
    val callback = signalLock.synchronized {
      val before = state.get()
      if (before.terminal.isDefined) None
      else before.waiting.dequeueOption.map { case (callback, rest) =>
        state.set(before.copy(waiting = rest))
        callback
      }
    }
    callback.foreach(_(Right(Some(value))))
  }

  def onError(error: Throwable): Unit = completeWith(Left(error))
  def onComplete(): Unit = completeWith(Right(None))

  private def completeWith(result: Result): Unit = {
    val callbacks = signalLock.synchronized {
      val before = state.get()
      if (before.terminal.isDefined) Queue.empty[Callback]
      else {
        state.set(before.copy(terminal = Some(result), waiting = Queue.empty))
        before.waiting
      }
    }
    callbacks.foreach(_(result))
  }

  def pull(): F[Option[T]] = Effect[F].promise { callback =>
    val completed = signalLock.synchronized {
      val before = state.get()
      before.terminal match {
        case some @ Some(_) => some
        case None =>
          state.set(before.copy(waiting = before.waiting.enqueue(callback)))
          before.subscription.foreach(_.request(1L))
          None
      }
    }
    completed.foreach(callback)
  }

  def cancel(): F[Unit] = Effect[F].delay {
    val (subscription, callbacks, result) = signalLock.synchronized {
      val before = state.get()
      val result: Result = before.terminal.getOrElse(Right(None))
      val subscription = if (before.cancellationIssued) None else before.subscription
      state.set(before.copy(
        waiting = Queue.empty,
        terminal = Some(result),
        canceled = true,
        cancellationIssued = before.cancellationIssued || subscription.isDefined
      ))
      (subscription, before.waiting, result)
    }
    // Preserve a pending pull's terminal signal even if an upstream violates
    // the Subscription contract by throwing from cancel.
    try subscription.foreach(_.cancel())
    finally callbacks.foreach(_(result))
  }
}
