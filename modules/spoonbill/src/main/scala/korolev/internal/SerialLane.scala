package spoonbill.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.Effect

/**
 * Handles queued items one at a time, in the order they were added.
 *
 * Three properties matter for the callers:
 *
 *   - Adding an item is cheap and constant-time. It never runs the handler on
 *     the adding thread: a waiting lane is woken on the execution context.
 *   - The next item starts only when the handler of the previous one has
 *     completed. So two handlers never run at the same time.
 *   - The loop is stack-safe. A handler that completes at once does not deepen
 *     the stack, however many items wait. This matters for `Future`, whose
 *     effect instance runs callbacks on the completing thread.
 *
 * A handler that fails is ignored here; the handler reports its own failures. A
 * handler that never completes stalls the lane.
 */
private[internal] final class SerialLane[F[_]: Effect, A <: AnyRef](handle: A => F[Unit])(implicit
  ec: ExecutionContext
) {

  import SerialLane.*

  private val queue = new ConcurrentLinkedQueue[A]()
  // True while one thread owns the loop, also across an asynchronous handler.
  private val running = new AtomicBoolean(false)
  private val waiters = new AtomicReference(List.empty[Effect.Promise[Unit]])

  @volatile private var closed = false

  /** Adds an item without waking the lane. Call [[wake]] afterwards. */
  def enqueue(item: A): Unit = {
    if (!closed) queue.add(item)
    ()
  }

  /** Starts the loop on the execution context, unless it runs already. */
  def wake(): Unit =
    if (!closed && !queue.isEmpty && running.compareAndSet(false, true))
      try ec.execute(() => drain())
      catch {
        // The executor is gone, so nothing can be handled any more.
        case NonFatal(_) => release()
      }

  def offer(item: A): Unit = {
    enqueue(item)
    wake()
  }

  /**
   * No new item is handled, and the waiting items are dropped. The handler in
   * flight is not interrupted.
   */
  def close(): Unit = {
    closed = true
    queue.clear()
    if (!running.get()) signalIdle()
  }

  /** Completes when no handler runs. Use it after [[close]]. */
  def idle: F[Unit] = Effect[F].promise { callback =>
    waiters.updateAndGet(callback :: _)
    if (!running.get()) signalIdle()
  }

  /** The number of items that wait. For tests and diagnostics. */
  def size: Int = queue.size()

  private def signalIdle(): Unit =
    waiters.getAndSet(Nil).foreach(_(Right(())))

  private def release(): Unit = {
    running.set(false)
    signalIdle()
  }

  /** Runs on one thread at a time: the owner of `running`. */
  private def drain(): Unit = {
    var continue = true
    while (continue) {
      val item = if (closed) null.asInstanceOf[A] else queue.poll()
      if (item == null) {
        release()
        // An item can arrive between the last poll and the release.
        continue = !closed && !queue.isEmpty && running.compareAndSet(false, true)
      } else {
        val step = new AtomicInteger(Started)
        Effect[F].runAsync(guarded(item)) { _ =>
          // The handler completed after this loop returned: resume from here.
          if (step.getAndSet(Completed) == Returned) drain()
        }
        // The handler completed already: take the next item in this loop, not
        // on a deeper stack.
        continue = step.getAndSet(Returned) == Completed
      }
    }
  }

  private def guarded(item: A): F[Unit] =
    Effect[F].recover(Effect[F].delayAsync(handle(item))) { case _ => () }
}

private[internal] object SerialLane {
  private final val Started   = 0
  private final val Returned  = 1
  private final val Completed = 2
}
