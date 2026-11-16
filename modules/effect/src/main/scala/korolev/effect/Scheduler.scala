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
      var canceled                                    = false
      var cb: Either[Throwable, Option[Unit]] => Unit = _
      var task: ScheduledFuture[?]                    = _
      def pull(): F[Option[Unit]] = Effect[F].promise { cb =>
        if (canceled) cb(Right(None))
        else {
          this.cb = cb
          this.task = after(delay)(cb(Right(Some(()))))
        }
      }
      def cancel(): F[Unit] = Effect[F].delay {
        if (task != null) {
          canceled = true
          task.cancel(false)
          task = null
          cb(Right(None))
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

      private val monitor   = new Object
      private var completed = Option.empty[Either[Throwable, T]]
      private var waiters   = List.empty[Promise[T]]

      private val task = after(delay) {
        Effect[F].delayAsync(job).runAsync { errorOrResult =>
          val callbacks = monitor.synchronized {
            if (completed.nonEmpty) Nil
            else {
              completed = Some(errorOrResult)
              val callbacks = waiters
              waiters = Nil
              callbacks
            }
          }
          callbacks.foreach(_(errorOrResult))
        }
      }

      def result: F[T] = Effect[F].promise { cb =>
        val ready = monitor.synchronized {
          completed match {
            case None  => waiters ::= cb; None
            case value => value
          }
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
