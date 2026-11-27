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

package spoonbill.effect

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.{Queue => IQueue}
import spoonbill.effect.Effect.Promise
import spoonbill.effect.syntax._

/**
 * Nonblocking, concurrent, asynchronous queue.
 */
class Queue[F[_]: Effect, T](maxSize: Int) {

  import Queue._

  private val state = new AtomicReference(Queue.State[T]())

  /** Distinguishes a rejected offer after close from temporary backpressure. */
  def isClosed: Boolean = QueueMachine.isClosed(state.get().lifecycle)

  private final class QueueStream extends Stream[F, T] {

    def pull(): F[Option[T]] = Effect[F].promise { cb =>
      @tailrec
      def aux(): Unit = {
        val ref = state.get()
        QueueMachine.pull(ref.lifecycle, ref.queue.nonEmpty) match {
          case QueueMachine.Pull.Failed(error) => cb(Left(error))
          case QueueMachine.Pull.End           => cb(noneToken)
          case QueueMachine.Pull.Buffered =>
            val (value, updatedQueue) = ref.queue.dequeue
            val canOfferCallback      = ref.canOfferCallbacks.headOption
            val newValue = ref.copy(
              queue = updatedQueue,
              canOfferCallbacks = ref.canOfferCallbacks.drop(1)
            )
            if (state.compareAndSet(ref, newValue)) {
              cb(Right(Option(value)))
              canOfferCallback.foreach(_(unitToken))
            } else {
              aux()
            }
          case QueueMachine.Pull.Wait =>
            val updatedPc = cb :: ref.pullCallbacks
            val newValue  = ref.copy(pullCallbacks = updatedPc)
            if (!state.compareAndSet(ref, newValue)) {
              aux()
            }
        }
      }
      aux()
    }

    def cancel(): F[Unit] = Effect[F].delay {
      unsafeClose()
      @tailrec def aux(): Unit = {
        val ref                  = state.get
        val (lifecycle, publish) = QueueMachine.cancel(ref.lifecycle)
        val newValue             = ref.copy(lifecycle = lifecycle, cancelCallbacks = Nil)
        if (state.compareAndSet(ref, newValue)) {
          if (publish) ref.cancelCallbacks.foreach(cb => cb(unitToken))
        } else {
          aux()
        }
      }
      aux()
    }
  }

  /**
   * Strict version of [[offer]]. Still thread safe. A producer that had to wait
   * uses `offerAfterWait` instead; keep the two in step.
   */
  def offerUnsafe(item: T): Boolean = {
    @tailrec
    def aux(): Boolean = {
      val ref = state.get()
      if (QueueMachine.mayOffer(ref.lifecycle)) {
        // Pull callbacks can be nonempty
        // if queue was empty when pull was ran.
        if (ref.pullCallbacks.nonEmpty) {
          // A prior dequeue may have left other capacity waiters behind. Direct
          // handoff keeps the buffer free, so pass that hint to one more waiter.
          val canOfferCallback = ref.canOfferCallbacks.headOption
          val newValue         = ref.copy(pullCallbacks = Nil, canOfferCallbacks = ref.canOfferCallbacks.drop(1))
          if (state.compareAndSet(ref, newValue)) {
            val token = Right(Some(item))
            ref.pullCallbacks.foreach(cb => cb(token))
            canOfferCallback.foreach(cb => cb(unitToken))
            true
          } else {
            aux()
          }
        } else if (ref.queue.size < maxSize) {
          val updatedQueue = ref.queue.enqueue(item)
          val newValue     = ref.copy(queue = updatedQueue)
          if (state.compareAndSet(ref, newValue)) {
            true
          } else {
            aux()
          }
        } else {
          false
        }
      } else {
        false
      }
    }
    aux()
  }

  /**
   * The attempt of a producer that had to wait. It does what [[offerUnsafe]]
   * does, by the rule that let the producer wait. The rule is judged on the
   * snapshot that takes the item, so no stop, close or failure can slip between
   * the rule and the commit.
   *
   * It has a loop of its own: [[offerUnsafe]] is a measured hot path and keeps
   * its code. Keep the two in step.
   */
  private def offerAfterWait(item: T): Boolean = {
    @tailrec
    def aux(): Boolean = {
      val ref = state.get()
      if (!QueueMachine.mayCommitAfterWait(ref.lifecycle)) {
        false
      } else if (ref.pullCallbacks.nonEmpty) {
        // Direct handoff, with the capacity hint for one more waiter.
        val canOfferCallback = ref.canOfferCallbacks.headOption
        val newValue         = ref.copy(pullCallbacks = Nil, canOfferCallbacks = ref.canOfferCallbacks.drop(1))
        if (state.compareAndSet(ref, newValue)) {
          val token = Right(Some(item))
          ref.pullCallbacks.foreach(cb => cb(token))
          canOfferCallback.foreach(cb => cb(unitToken))
          true
        } else {
          aux()
        }
      } else if (ref.queue.size < maxSize) {
        if (state.compareAndSet(ref, ref.copy(queue = ref.queue.enqueue(item)))) true else aux()
      } else {
        false
      }
    }
    aux()
  }

  def unsafeStop(): Unit = stopUnsafe(None)

  /**
   * The stop behind [[stop]] and [[stopWithFailure]]. With a failure, the pulls
   * that wait are told that failure, as every later pull is.
   */
  private def stopUnsafe(failure: Option[Throwable]): Unit = {
    @tailrec def aux(): Unit = {
      val ref                  = state.get
      val (lifecycle, command) = QueueMachine.stop(ref.lifecycle, ref.queue.nonEmpty, failure)
      val settlePulls          = command != QueueMachine.StopCommand.ReleaseProducers
      val next = ref.copy(
        lifecycle = lifecycle,
        canOfferCallbacks = Nil,
        pullCallbacks = if (settlePulls) Nil else ref.pullCallbacks
      )
      if (state.compareAndSet(ref, next)) {
        // Detach before notification: callbacks may reenter stop or admission.
        ref.canOfferCallbacks.foreach(cb => cb(unitToken))
        command match {
          case QueueMachine.StopCommand.ReleaseProducers            => ()
          case QueueMachine.StopCommand.ReleaseProducersAndEndPulls => ref.pullCallbacks.foreach(cb => cb(noneToken))
          case QueueMachine.StopCommand.ReleaseProducersAndFailPulls(error) =>
            val failed = Left(error)
            ref.pullCallbacks.foreach(cb => cb(failed))
        }
      } else {
        aux()
      }
    }
    aux()
  }

  def unsafeClose(): Unit = {
    @tailrec
    def aux(): Unit = {
      val ref = state.get
      if (!QueueMachine.isClosed(ref.lifecycle)) {
        val newValue =
          ref.copy(canOfferCallbacks = Nil, pullCallbacks = Nil, lifecycle = QueueMachine.close(ref.lifecycle))
        if (state.compareAndSet(ref, newValue)) {
          ref.canOfferCallbacks.foreach(cb => cb(unitToken))
          ref.pullCallbacks.foreach(cb => cb(noneToken))
        } else {
          aux()
        }
      }
    }
    aux()
  }

  def dequeueAll(): F[Seq[T]] = Effect[F].delay {
    def aux(): Seq[T] = {
      val s = state.get()
      if (state.compareAndSet(s, s.copy(queue = IQueue.empty, canOfferCallbacks = Nil))) {
        // A bulk drain frees capacity just like individual pulls. Wake all
        // registered capacity waiters; enqueue rechecks available space.
        s.canOfferCallbacks.foreach(_(unitToken))
        s.queue
      } else {
        aux()
      }
    }
    aux()
  }

  /**
   * Signals that queue size became less than [[maxSize]].
   * @example
   *   {{{ def aux(): F[Unit] = queue.offer(o).flatMap { case false =>
   *   queue.canOffer *> aux() case true => Effect[F].unit } aux() }}}
   */
  def canOffer: F[Unit] = awaitCapacity(QueueMachine.mayOffer)

  /**
   * Completes when a producer should look again: there is room, or `mayWait`
   * says that waiting cannot help any more. The check and the registration use
   * one snapshot, so no stop, close or failure can slip between them.
   */
  private def awaitCapacity(mayWait: QueueMachine.State => Boolean): F[Unit] = Effect[F].promise { cb =>
    @tailrec
    def aux(): Unit = {
      val ref = state.get
      if (!mayWait(ref.lifecycle) || ref.queue.size < maxSize) {
        cb(unitToken)
      } else {
        val newValue = ref.copy(canOfferCallbacks = cb :: ref.canOfferCallbacks)
        if (!state.compareAndSet(ref, newValue)) {
          aux()
        }
      }
    }
    aux()
  }

  /**
   * Strict version of [[fail]]. Still thread safe.
   */
  def failUnsafe(e: Throwable): Unit = {
    @tailrec
    def aux(): Unit = {
      val ref = state.get
      val newValue =
        ref.copy(canOfferCallbacks = Nil, pullCallbacks = Nil, lifecycle = QueueMachine.fail(ref.lifecycle, e))
      if (state.compareAndSet(ref, newValue)) {
        ref.canOfferCallbacks.foreach(cb => cb(unitToken))
        ref.pullCallbacks.foreach(cb => cb(Left(e)))
      } else {
        aux()
      }
    }
    aux()
  }

  /**
   * Offers `item` to the queue.
   * @return
   *   true is ok and false if [[maxSize]] reached or queue is stopped.
   */
  def offer(item: T): F[Boolean] =
    Effect[F].delay(offerUnsafe(item))

  /**
   * Enqueue item. If [[maxSize]] reached waits until queue will decrease.
   *
   * The first attempt is an [[offer]]. An item that it could not place waits
   * for room, and is accepted later only while the queue is neither stopped,
   * closed nor failed. When one of these happens, the wait ends and the item is
   * not accepted, also if a drain makes room afterwards. The effect completes
   * normally in every case.
   */
  def enqueue(item: T): F[Unit] = {
    def settle(accepted: Boolean): F[Unit] =
      if (accepted) Effect[F].unit
      // Judge the state after the attempt ran. A lazy effect builds this loop
      // long before it runs, so a snapshot taken earlier can be out of date.
      else if (!QueueMachine.mayAwaitCapacity(state.get.lifecycle)) Effect[F].unit
      else awaitCapacity(QueueMachine.mayAwaitCapacity) *> retry()
    def retry(): F[Unit] = Effect[F].delay(offerAfterWait(item)).flatMap(settle)
    offer(item).flatMap(settle)
  }

  /**
   * Disallow to offer new items. Stream ends with last item.
   */
  def stop(): F[Unit] =
    Effect[F].delay(unsafeStop())

  /**
   * Like [[stop]], but the stream ends with `error` and not with the last item.
   * A pull takes the accepted items first. When none is left it fails with
   * `error`, now and later.
   *
   * The stop that ends admission decides how the stream ends. So this changes
   * nothing on a queue that is stopped or closed already. [[close]] and
   * `stream.cancel` still end the stream without a failure, and [[fail]] still
   * fails every pull at once.
   *
   * `error` is evaluated when the effect runs.
   */
  private[spoonbill] def stopWithFailure(error: => Throwable): F[Unit] =
    Effect[F].delay(stopUnsafe(Some(error)))

  /**
   * Immediately stop offering and pulling items from the queue.
   * @return
   */
  def close(): F[Unit] =
    Effect[F].delay(unsafeClose())

  /**
   * Fails every pull with `e`, now and later, until another `fail` replaces the
   * error. An [[enqueue]] that had to wait and has not placed its item yet is
   * released: its item is not accepted, also when a drain makes room
   * afterwards. Admission stays open until [[stop]] or [[close]]: [[offer]] and
   * a new [[enqueue]] still take items while there is room, and only
   * [[dequeueAll]] can read them.
   */
  def fail(e: Throwable): F[Unit] =
    Effect[F].delay(failUnsafe(e))

  /**
   * Resolves once `stream.cancel` ran, including for listeners registered
   * later. Closing the queue without explicit stream cancellation does not
   * signal it.
   * @return
   */
  def cancelSignal: F[Unit] = Effect[F].promise { cb =>
    @tailrec def aux(): Unit = {
      val ref = state.get
      if (QueueMachine.cancellationReplays(ref.lifecycle)) cb(unitToken)
      else {
        val newValue = ref.copy(cancelCallbacks = cb :: ref.cancelCallbacks)
        if (!state.compareAndSet(ref, newValue)) aux()
      }
    }
    aux()
  }

  /**
   * Returns the size of the queue. This property takes into account
   * canOfferCallbacks and pullCallbacks to determine the real size of the queue
   * after all of them are resolved.
   * @return
   */
  def size(): F[Int] =
    Effect[F].delay {
      val s = state.get()
      s.queue.size + s.canOfferCallbacks.size - s.pullCallbacks.length
    }

  val stream: Stream[F, T] = new QueueStream()
}

object Queue {

  private case class State[T](
    lifecycle: QueueMachine.State = QueueMachine.initial,
    pullCallbacks: List[Promise[Option[T]]] = Nil,
    canOfferCallbacks: List[Promise[Unit]] = Nil,
    cancelCallbacks: List[Promise[Unit]] = Nil,
    queue: IQueue[T] = IQueue.empty
  )

  private final val unitToken = Right(())
  private final val noneToken = Right(None)

  def apply[F[_]: Effect, T](maxSize: Int = Int.MaxValue): Queue[F, T] =
    new Queue[F, T](maxSize)
}
