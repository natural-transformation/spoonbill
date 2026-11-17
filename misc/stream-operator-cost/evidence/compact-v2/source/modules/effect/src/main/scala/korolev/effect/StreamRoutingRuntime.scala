package spoonbill.effect

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.syntax.*

/**
 * Pure scalar ownership decisions govern interpreter-owned FIFO data/handle
 * stores. Native effects retain one persistent reader fiber. Future has no
 * native cancellation/fiber locals; its independent callback pump uses the same
 * credit and terminal decisions without building a per-item effect loop.
 */
private[effect] final class StreamRoutingRuntime[F[_], A](
  upstream: Stream[F, A],
  mode: StreamRoutingMachine.Mode,
  capacity: Int,
  fixed: Vector[StreamRoutingMachine.Lane] = Vector.empty,
  route: Option[A => Int] = None
)(using F: Effect[F]) {
  import StreamRoutingMachine.*
  require(capacity > 0, "Stream buffer capacity must be positive")
  private type Callback = Effect.Promise[Option[A]]
  private enum CleanupStart { case Waiting, Running, Abandoned }
  private final class LaneData {
    val values = mutable.ArrayDeque.empty[A]
    val pulls  = mutable.ArrayDeque.empty[Callback]
  }

  /**
   * Derived storage counts, updated with their queues under the same monitor.
   */
  private final class Storage {
    val lanes = mutable.LinkedHashMap.empty[Lane, LaneData]
    fixed.foreach(lane => lanes.put(lane, new LaneData))
    var demanding             = 0
    var full                  = 0
    var single: LaneData      = if (lanes.size == 1) lanes.valuesIterator.next() else null
    def refreshSingle(): Unit = single = if (lanes.size == 1) lanes.valuesIterator.next() else null
  }
  private val monitor                                              = new Object
  private val storage                                              = new Storage
  private val fixedData                                            = fixed.map(storage.lanes)
  private var control                                              = idleControl
  private var dispatch                                             = Dispatch.Idle
  private var reader: Effect.Promise[Boolean]                      = null
  private val commands                                             = mutable.ArrayDeque.empty[() => Unit]
  private val endToken: Either[Throwable, Option[A]]               = Right(None)
  private val futureSemantics                                      = F.isInstanceOf[Effect.FutureEffect]
  private val readCommand: () => Unit                              = () => readFuture()
  private val futureReceiver: Either[Throwable, Option[A]] => Unit = completeFuture

  private def report(error: Throwable): Unit =
    Reporter.PrintReporter.error("Stream operator background work failed", error)
  private def enqueue(callback: Callback, result: Either[Throwable, Option[A]]): Unit =
    commands.append(() => callback(result))
  private def canReadNow: Boolean =
    readAllowed(mode, control.phase, control.reader, storage.lanes.nonEmpty, storage.demanding > 0, storage.full == 0)
  private def wakeReader(): Unit =
    if (futureSemantics) {
      if (canReadNow) { control = readingControl; commands.append(readCommand) }
    } else if (reader != null) {
      if (control.phase != Phase.Open) {
        val callback = reader; reader = null
        commands.append(() => callback(Right(false)))
      } else if (canReadNow) {
        control = readingControl
        val callback = reader; reader = null
        commands.append(() => callback(Right(monitor.synchronized(control.phase == Phase.Open))))
      }
    }
  private def claimDrainer(): Boolean = {
    val next    = claimDispatch(dispatch, commands.nonEmpty)
    val claimed = next != dispatch
    dispatch = next
    claimed
  }
  private inline def change[B](inline body: B): B = {
    var run    = false
    val result = monitor.synchronized { val value = body; run = claimDrainer(); value }
    if (run) drainCommands()
    result
  }
  private def drainCommands(): Unit = {
    var continue = true
    while (continue) {
      val action: () => Unit = monitor.synchronized {
        if (dispatchPaused(dispatch) || commands.isEmpty) { dispatch = releaseDispatch(dispatch); null }
        else commands.removeHead()
      }
      if (action == null) continue = false
      else
        try action()
        catch { case NonFatal(error) => report(error) }
    }
  }
  private def discardValues(data: LaneData): Unit = {
    if (data.values.size == capacity) storage.full -= 1
    data.values.clear()
  }
  private def completePulls(data: LaneData, result: Either[Throwable, Option[A]]): Unit = {
    if (data.pulls.nonEmpty) storage.demanding -= 1
    while (data.pulls.nonEmpty) enqueue(data.pulls.removeHead(), result)
  }
  private def deliver(data: LaneData, value: A, result: Either[Throwable, Option[A]]): Unit =
    recipient(data.pulls.nonEmpty, data.values.size, capacity) match {
      case Recipient.Deliver =>
        val callback = data.pulls.removeHead()
        if (data.pulls.isEmpty) storage.demanding -= 1
        enqueue(callback, result)
      case Recipient.Store =>
        data.values.append(value)
        if (data.values.size == capacity) storage.full += 1
      case Recipient.Overflow => throw new IllegalStateException("Stream read credit was exceeded")
    }
  private def fail(error: Throwable): Boolean = {
    val open    = control.phase == Phase.Open
    val cleanup = failureCleanup(mode, control.phase, control.cleanupClaimed)
    control = failControl(control, mode, error)
    if (cleanup) dispatch = pauseDispatch(dispatch)
    if (open) {
      val result: Either[Throwable, Option[A]] = Left(error)
      storage.lanes.valuesIterator.foreach { data =>
        if (mode != Mode.Buffered) discardValues(data)
        completePulls(data, result)
      }
    }
    wakeReader()
    cleanup
  }
  private def end(): Boolean = {
    val open = control.phase == Phase.Open
    control = endControl(control)
    if (open) storage.lanes.valuesIterator.foreach(completePulls(_, endToken))
    wakeReader()
    false
  }
  private def complete(result: Either[Throwable, Option[A]]): Boolean = result match {
    case Right(Some(value)) =>
      if (!monitor.synchronized(control.phase == Phase.Open)) change { control = settleControl(control); false }
      else {
        val index = route.fold(-1)(_(value))
        if (route.nonEmpty && (index < 0 || index >= fixedData.size))
          throw new IllegalArgumentException(s"Invalid stream rack index: $index")
        change {
          if (control.phase != Phase.Open) { control = settleControl(control); false }
          else if (
            index >= 0 && recipient(
              fixedData(index).pulls.nonEmpty,
              fixedData(index).values.size,
              capacity
            ) == Recipient.Overflow
          ) fail(StreamSortOverflowException(index, capacity))
          else {
            control = settleControl(control)
            if (index >= 0) deliver(fixedData(index), value, result)
            else if (storage.single != null) deliver(storage.single, value, result)
            else storage.lanes.valuesIterator.foreach(deliver(_, value, result))
            wakeReader()
            false
          }
        }
      }
    case Right(None) => change(end())
    case Left(error) => change(fail(error))
  }
  private def completeFuture(result: Either[Throwable, Option[A]]): Unit = {
    val needsCleanup =
      try complete(result)
      catch {
        case error: Throwable =>
          // Normalize failed Future construction once, including interruption,
          // without successful per-item map/recover wrappers.
          if (result.isLeft) throw error
          F.runAsync(F.fail[Option[A]](error))(futureReceiver)
          false
      }
    if (needsCleanup) {
      try F.runAsync(cleanup())(_ => ())
      catch { case NonFatal(error) => report(error) }
    }
  }
  private def readFuture(): Unit =
    if (monitor.synchronized(control.phase == Phase.Open)) {
      val read =
        try upstream.pull()
        catch { case error: Throwable => F.fail[Option[A]](error) }
      try F.runAsync(read)(futureReceiver)
      catch { case NonFatal(error) => completeFuture(Left(error)) }
    } else change { control = settleControl(control) }

  private def cleanup(): F[Unit] = {
    val start = new AtomicReference(CleanupStart.Waiting)
    def task = F.delay(start.compareAndSet(CleanupStart.Waiting, CleanupStart.Running)).flatMap {
      case false => F.unit
      case true  => F.delayAsync(upstream.cancel()).recover { case error => report(error); () }
    }
    val launched = F
      .delayAsync(F.start(task)(ExecutionContext.global))
      .flatMap { fiber =>
        F.delay(F.runAsync(fiber.join()) { case Left(error) => report(error); case _ => () })
      }
      .recoverF { case error =>
        F.delay { start.compareAndSet(CleanupStart.Waiting, CleanupStart.Abandoned); report(error) }
          .flatMap(_ => F.fail(error))
      }
    F.guarantee(launched)(F.delay(change {
      // Claim reset and dispatch release are atomic, so an old failure cannot
      // unpause a newer cleanup attempt.
      if (start.get() == CleanupStart.Abandoned) control = control.copy(cleanupClaimed = false)
      dispatch = resumeDispatch(dispatch)
    }))
  }
  private def awaitRead(): F[Boolean] = F.promise(callback => change { reader = callback; wakeReader() })
  private def loop(): F[Unit] = awaitRead().flatMap {
    case false => F.unit
    case true =>
      F.delayAsync(if (monitor.synchronized(control.phase == Phase.Open)) upstream.pull() else F.none[A])
        .map[Either[Throwable, Option[A]]](Right(_))
        .recover { case error => Left(error) }
        .flatMap(result => F.delay(complete(result)).recover { case error => change(fail(error)) })
        .flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
        .flatMap(_ => F.fork(loop())(ExecutionContext.parasitic))
  }
  private def readerExited(): F[Unit] = F.uncancelable {
    F.delay(change {
      if (control.phase == Phase.Open) fail(new CancellationException("Shared upstream reader canceled"))
      else { control = settleControl(control); false }
    }).flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
  }
  private def launchReader(): Unit =
    if (futureSemantics) change(wakeReader())
    else {
      val program = F.delayAsync(loop()).recoverF { case error =>
        F.delay(change(fail(error))).flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
      }
      F.runAsync(F.guarantee(program)(readerExited())) {
        case Left(error) if monitor.synchronized(control.phase == Phase.Open) => report(error)
        case _                                                                => ()
      }
    }
  launchReader()

  def newLane(): Lane = change {
    if (control.phase != Phase.Open) throw new IllegalStateException("Hub is closed")
    val lane = new Lane
    storage.lanes.put(lane, new LaneData)
    storage.refreshSingle()
    lane
  }
  def stream(lane: Lane): Stream[F, A] = new Stream[F, A] {
    def pull(): F[Option[A]] = StreamRoutingRuntime.protect {
      F.promise[Option[A]] { callback =>
        change {
          storage.lanes.get(lane) match {
            case None => enqueue(callback, endToken)
            case Some(data) if data.values.nonEmpty =>
              if (data.values.size == capacity) storage.full -= 1
              enqueue(callback, Right(Some(data.values.removeHead())))
            case Some(data) =>
              control.phase match {
                case Phase.Open =>
                  if (data.pulls.isEmpty) storage.demanding += 1
                  data.pulls.append(callback)
                case Phase.Failed(error) => enqueue(callback, Left(error))
                case _                   => enqueue(callback, endToken)
              }
          }
          wakeReader()
        }
      }
    }(cancel())
    def cancel(): F[Unit] = cancelLane(Some(lane))
  }
  private def cancelLane(lane: Option[Lane]): F[Unit] = F.uncancelable {
    F.delay(change {
      val needsCleanup = if (mode == Mode.Fanout && lane.nonEmpty) {
        storage.lanes.remove(lane.get).foreach { data =>
          discardValues(data); completePulls(data, endToken)
        }
        storage.refreshSingle()
        false
      } else {
        val start = !control.cleanupClaimed
        control = cancelControl(control, mode)
        if (start) dispatch = pauseDispatch(dispatch)
        val result = control.phase match { case Phase.Failed(error) => Left(error); case _ => endToken }
        storage.lanes.valuesIterator.foreach { data =>
          discardValues(data); completePulls(data, result)
        }
        start
      }
      wakeReader()
      needsCleanup
    }).flatMap(start => if (start) cleanup() else F.unit)
  }
  def cancel(): F[Unit] = cancelLane(None)
  def drainDeferred(lane: Lane): (Vector[A], () => Unit) = {
    var run = false
    val values = monitor.synchronized {
      val values = storage.lanes.get(lane).fold(Vector.empty[A]) { data =>
        val values = data.values.toVector
        discardValues(data)
        values
      }
      wakeReader()
      run = claimDrainer()
      values
    }
    (values, () => if (run) drainCommands())
  }
}

private[effect] object StreamRoutingRuntime {

  /**
   * Scala Future cannot be natively canceled. Other effects keep the protected
   * pull's success/error boundary, not a promise of irrevocable outer delivery.
   */
  def protect[F[_], A](task: => F[A])(canceled: => F[Unit])(using F: Effect[F]): F[A] =
    if (F.isInstanceOf[Effect.FutureEffect]) {
      try task
      catch { case error: Throwable => F.fail(error) }
    } else
      F.delay(new AtomicBoolean(false)).flatMap { settled =>
        F.guarantee(
          F.delayAsync(task)
            .map { value =>
              settled.set(true); value
            }
            .recoverF { case error =>
              settled.set(true); F.fail(error)
            }
        )(if (settled.get()) F.unit else canceled)
      }
}
