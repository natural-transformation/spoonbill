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

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import spoonbill.effect.Effect.Promise
import spoonbill.effect.syntax._

final class Scheduler[F[_]: Effect] {

  import Scheduler._

  // One timer thread, like java.util.Timer, but a canceled task leaves the queue
  // at once. Frequent short-lived deadlines then retain neither queue slots nor
  // the callbacks they close over until their original due time.
  private val timer = {
    val executor = new ScheduledThreadPoolExecutor(1, Scheduler.timerThreads)
    executor.setRemoveOnCancelPolicy(true)
    executor
  }

  private def after(delay: FiniteDuration)(run: => Unit): ScheduledFuture[?] =
    timer.schedule((() => run): Runnable, delay.toMillis, TimeUnit.MILLISECONDS)

  def schedule(delay: FiniteDuration): F[Stream[F, Unit]] = Effect[F].delay {
    new Stream[F, Unit] {
      private final class Tick(val callback: Promise[Option[Unit]]) {
        var task = Option.empty[ScheduledFuture[?]]
      }
      private val monitor  = new Object
      private var canceled = false
      private var pending  = Set.empty[Tick]

      private def take(tick: Tick): Boolean = monitor.synchronized {
        val owned = pending.contains(tick)
        pending -= tick
        owned
      }

      def pull(): F[Option[Unit]] = Effect[F].promise { cb =>
        val tick = new Tick(cb)
        val admitted = monitor.synchronized {
          if (canceled) false
          else { pending += tick; true }
        }
        if (!admitted) cb(Right(None))
        else
          try {
            val task = after(delay)(if (take(tick)) cb(Right(Some(()))))
            val installed = monitor.synchronized {
              if (pending.contains(tick)) { tick.task = Some(task); true }
              else false
            }
            // A zero-delay tick or cancellation can win before handle installation.
            if (!installed) task.cancel(false)
          } catch { case NonFatal(error) => if (take(tick)) cb(Left(error)) }
      }

      def cancel(): F[Unit] = Effect[F].delay {
        val ticks = monitor.synchronized {
          canceled = true
          val ticks = pending
          pending = Set.empty
          ticks
        }
        ticks.foreach { tick =>
          tick.task.foreach(_.cancel(false))
          tick.callback(Right(None))
        }
      }
    }
  }

  def sleep(delay: FiniteDuration): F[Unit] = Effect[F].promise { cb =>
    after(delay)(cb(Right(())))
    ()
  }

  def scheduleOnce[T](delay: FiniteDuration)(job: => F[T]): F[JobHandler[F, T]] =
    Effect[F].delay(unsafeScheduleOnce(delay)(job))

  def unsafeScheduleOnce[T](delay: FiniteDuration)(job: => F[T]): JobHandler[F, T] =
    new JobHandler[F, T] {

      private case class Completion(
        state: CompletionLatchMachine.State,
        result: Option[Either[Throwable, T]] = None,
        waiters: List[Promise[T]] = Nil
      )
      private val monitor    = new Object
      private var completion = Completion(CompletionLatchMachine.State.Pending)

      private val task = after(delay) {
        Effect[F].delayAsync(job).runAsync { errorOrResult =>
          val callbacks = monitor.synchronized {
            val current         = completion
            val (next, command) = CompletionLatchMachine.step(current.state, CompletionLatchMachine.Event.Complete)
            if (command == CompletionLatchMachine.Command.Publish) {
              completion = Completion(next, Some(errorOrResult))
              val callbacks = current.waiters
              callbacks
            } else Nil
          }
          callbacks.foreach(_(errorOrResult))
        }
      }

      def result: F[T] = Effect[F].promise { cb =>
        val ready = monitor.synchronized {
          val current         = completion
          val (next, command) = CompletionLatchMachine.step(current.state, CompletionLatchMachine.Event.Observe)
          if (command == CompletionLatchMachine.Command.Register) {
            completion = current.copy(state = next, waiters = cb :: current.waiters)
            None
          } else current.result
        }
        ready.foreach(cb)
      }

      def cancel(): F[Unit] = Effect[F].delay(unsafeCancel())

      def unsafeCancel(): Unit = {
        task.cancel(false)
        ()
      }
    }
}

object Scheduler {

  private val cache = TrieMap.empty[AnyRef, Scheduler[List]]

  // Non-daemon, as the java.util.Timer thread it replaces.
  private val timerThreads: ThreadFactory = new ThreadFactory {
    private val count                     = new AtomicInteger(0)
    def newThread(task: Runnable): Thread = new Thread(task, s"spoonbill-scheduler-${count.incrementAndGet()}")
  }

  implicit def schedulerF[F[_]: Effect]: Scheduler[F] = cache
    .getOrElseUpdate(Effect[F], new Scheduler[F].asInstanceOf[Scheduler[List]])
    .asInstanceOf[Scheduler[F]]

  def apply[F[_]: Scheduler]: Scheduler[F] =
    implicitly[Scheduler[F]]

  trait JobHandler[F[_], T] {
    def unsafeCancel(): Unit
    def cancel(): F[Unit]
    def result: F[T]
  }
}
