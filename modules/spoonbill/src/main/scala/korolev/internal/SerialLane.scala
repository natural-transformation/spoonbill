package spoonbill.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.Effect

/**
 * Handles queued items one at a time, in the order they were added.
 *
 * What the callers rely on:
 *
 *   - Adding an item is cheap and constant-time.
 *   - A handler always starts in a task of the execution context. It never
 *     starts inside the call that adds an item, and never on the thread that
 *     settled the previous handler.
 *   - The next item starts only when the handler of the previous one has
 *     settled. So two handlers never run at the same time.
 *   - Every outcome settles a handler: success, a failed effect, a thrown
 *     exception and, with a cancelable effect, an interruption. A handler that
 *     did not succeed is passed to `failed`, and the lane goes on.
 *   - The loop is stack-safe. A handler that settles at once does not deepen
 *     the stack, however many items wait. This matters for `Future`, whose
 *     effect instance runs callbacks on the completing thread.
 *
 * A handler that never settles stalls the lane.
 *
 * @param failed
 *   receives an item whose handler did not succeed
 * @param rejected
 *   receives the error when the execution context refuses the loop. The items
 *   stay queued, and the next wake-up tries again.
 */
private[internal] final class SerialLane[F[_]: Effect, A <: AnyRef](
  handle: A => F[Unit],
  failed: (A, Throwable) => Unit = (_: A, _: Throwable) => (),
  rejected: Throwable => Unit = (_: Throwable) => ()
)(implicit ec: ExecutionContext) {

  import SerialLane.*

  private val queue = new ConcurrentLinkedQueue[A]()
  // Who owns the lane. See the states in the companion.
  private val state = new AtomicInteger(Idle)
  // The callers of `idle`. None once the lane is closed and no handler runs.
  private val waiters = new AtomicReference(Option(List.empty[Effect.Promise[Unit]]))

  /** Adds an item without waking the lane. Call [[wake]] afterwards. */
  def enqueue(item: A): Unit =
    if (!closed) {
      queue.add(item)
      // `close` can clear the queue between the check and the add.
      if (closed) queue.clear()
    }

  /** Starts the loop on the execution context, unless it runs already. */
  def wake(): Unit =
    if (!queue.isEmpty && state.compareAndSet(Idle, Scheduled)) schedule()

  def offer(item: A): Unit = {
    enqueue(item)
    wake()
  }

  /**
   * No new item is handled, and the waiting items are dropped. The handler in
   * flight is not interrupted.
   */
  def close(): Unit = {
    @tailrec def aux(): Unit = state.get() match {
      // No handler runs, and none can start: the loop checks the state first.
      case current @ (Idle | Scheduled) => if (state.compareAndSet(current, Closed)) settle() else aux()
      // The owner of the loop finishes the close when its handler has settled.
      case Running => if (!state.compareAndSet(Running, Draining)) aux()
      case _       => ()
    }
    aux()
    queue.clear()
  }

  /** Completes once the lane is closed and no handler runs. */
  def idle: F[Unit] = Effect[F].promise { callback =>
    @tailrec def aux(): Unit = waiters.get() match {
      case None                    => callback(Right(()))
      case current @ Some(waiting) => if (!waiters.compareAndSet(current, Some(callback :: waiting))) aux()
    }
    aux()
  }

  /** The number of items that wait. For tests and diagnostics. */
  def size: Int = queue.size()

  private def closed: Boolean = state.get() >= Draining

  private def schedule(): Unit =
    try ec.execute(() => run())
    catch {
      case error: Throwable =>
        // No loop was started. A close in between has settled the lane already.
        state.compareAndSet(Scheduled, Idle)
        if (NonFatal(error)) guard(rejected(error)) else throw error
    }

  /** A task that was scheduled before a close finds the lane closed. */
  private def run(): Unit =
    if (state.compareAndSet(Scheduled, Running)) loop()

  /** Runs on one thread at a time: the owner of the `Running` state. */
  private def loop(): Unit = {
    var continue = true
    while (continue) {
      val item = if (state.get() == Running) queue.poll() else null.asInstanceOf[A]
      if (item == null) continue = release()
      else {
        val step = new AtomicInteger(Started)
        try
          Effect[F].runAsync(Effect[F].delayAsync(handle(item))) { result =>
            result.left.foreach(error => guard(failed(item, error)))
            // The handler settled after this loop returned. Go on in a new task,
            // not on the thread that settled it.
            if (step.getAndSet(Settled) == Returned) resume()
          }
        catch {
          // The effect could not be started at all.
          case NonFatal(error) =>
            guard(failed(item, error))
            step.set(Settled)
        }
        // The handler settled already: take the next item in this loop, not on
        // a deeper stack.
        continue = step.getAndSet(Returned) == Settled
      }
    }
  }

  /**
   * Gives the lane up when nothing waits. True when this thread took it again,
   * because an item arrived between the last poll and the release.
   */
  private def release(): Boolean =
    if (state.compareAndSet(Running, Idle)) !queue.isEmpty && state.compareAndSet(Idle, Running)
    else {
      finish()
      false
    }

  /** Called by the thread that settled a handler after the loop returned. */
  private def resume(): Unit =
    if (queue.isEmpty) {
      if (state.compareAndSet(Running, Idle)) wake() else finish()
    } else if (state.compareAndSet(Running, Scheduled)) schedule()
    else finish()

  /** The lane was closed while this thread owned it. */
  private def finish(): Unit = {
    state.set(Closed)
    settle()
  }

  private def settle(): Unit =
    waiters.getAndSet(None).foreach(_.foreach(_(Right(()))))

  private def guard(report: => Unit): Unit =
    try report
    catch { case NonFatal(_) => () }
}

private[internal] object SerialLane {

  // The lane: nobody owns it, a loop is submitted, a loop owns it, it was
  // closed while a loop owned it, and closed for good. Only the owner leaves
  // `Running` and `Draining`.
  private final val Idle      = 0
  private final val Scheduled = 1
  private final val Running   = 2
  private final val Draining  = 3
  private final val Closed    = 4

  // One handler call: started, the loop has returned, the handler has settled.
  private final val Started  = 0
  private final val Returned = 1
  private final val Settled  = 2
}
