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
  private type Outcome  = Either[Throwable, Option[A]]
  private enum CleanupStart { case Waiting, Running, Abandoned }
  private final class LaneData {
    val values = mutable.ArrayDeque.empty[A]
    // Keep the usual single waiter directly; overlapping pulls retain FIFO.
    private var firstPull: Callback                      = null
    private var laterPulls: mutable.ArrayDeque[Callback] = null
    def hasPull: Boolean                                 = firstPull != null
    def appendPull(callback: Callback): Unit =
      if (firstPull == null) firstPull = callback
      else {
        if (laterPulls == null) laterPulls = mutable.ArrayDeque.empty[Callback]
        laterPulls.append(callback)
      }
    def removePull(): Callback = {
      val callback = firstPull
      if (callback == null) throw new NoSuchElementException("No pending stream pull")
      if (laterPulls != null && laterPulls.nonEmpty) firstPull = laterPulls.removeHead()
      else firstPull = null
      callback
    }
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
  private val monitor                     = new Object
  private val storage                     = new Storage
  private val fixedData: Array[LaneData]  = fixed.map(storage.lanes).toArray
  private val router: A => Int            = route.orNull
  private var owned                       = idleControl
  // Monotonic publication of `owned.phase eq Open`, written with it under the
  // monitor. A stale true is rechecked under the monitor; false is final.
  @volatile private var open              = true
  private var dispatch                    = Dispatch.Idle
  private var reader: Effect.Promise[Boolean] = null
  // The command FIFO is one staged notification, then one staged read, then
  // `commands`. Staged slots are used only while `commands` is empty.
  private var stagedCallback: Callback    = null
  private var stagedOutcome: Outcome      = null
  private var stagedRead                  = false
  private val commands                    = mutable.ArrayDeque.empty[() => Unit]
  // The dispatch owner's current command. Written under the monitor by the
  // thread that owns dispatch and read by that same thread outside it.
  private var handCallback: Callback      = null
  private var handOutcome: Outcome        = null
  private var handRead                    = false
  private var handAction: () => Unit      = null
  private val endToken: Outcome           = Right(None)
  private val futureSemantics             = F.isInstanceOf[Effect.FutureEffect]
  private val readCommand: () => Unit     = () => readFuture()
  private val futureReceiver: Outcome => Unit = completeFuture

  private def control: Control = owned
  private def control_=(next: Control): Unit = {
    owned = next
    if (!(next.phase eq Phase.Open) && open) open = false
  }

  private def report(error: Throwable): Unit =
    Reporter.PrintReporter.error("Stream operator background work failed", error)
  private def flushStaged(): Unit = {
    if (stagedCallback != null) {
      val callback = stagedCallback
      val outcome  = stagedOutcome
      stagedCallback = null
      stagedOutcome = null
      commands.append(() => callback(outcome))
    }
    if (stagedRead) { stagedRead = false; commands.append(readCommand) }
  }
  private def enqueue(callback: Callback, result: Outcome): Unit =
    if (stagedCallback == null && !stagedRead && commands.isEmpty) { stagedCallback = callback; stagedOutcome = result }
    else { flushStaged(); commands.append(() => callback(result)) }
  private def enqueueRead(): Unit =
    if (!stagedRead && commands.isEmpty) stagedRead = true
    else { flushStaged(); commands.append(readCommand) }
  private def enqueueAction(action: () => Unit): Unit = { flushStaged(); commands.append(action) }
  private def canReadNow: Boolean =
    readAllowed(mode, control.phase, control.reader, storage.lanes.nonEmpty, storage.demanding > 0, storage.full == 0)
  private def wakeReader(): Unit =
    if (futureSemantics) {
      if (canReadNow) { control = readingControl; enqueueRead() }
    } else if (reader != null) {
      if (control.phase != Phase.Open) {
        val callback = reader; reader = null
        enqueueAction(() => callback(Right(false)))
      } else if (canReadNow) {
        control = readingControl
        val callback = reader; reader = null
        enqueueAction(() => callback(Right(open)))
      }
    }

  /** Called only while owning monitor. Moves the FIFO head into the owner's hand. */
  private def takeHead(): Boolean =
    if (stagedCallback != null) {
      handCallback = stagedCallback; handOutcome = stagedOutcome
      stagedCallback = null; stagedOutcome = null
      true
    } else if (stagedRead) { stagedRead = false; handRead = true; true }
    else if (commands.nonEmpty) { handAction = commands.removeHead(); true }
    else false

  /** Called only while owning monitor. Reservation invokes no effect. */
  private def claimFirst(): Boolean = {
    val next = claimDispatch(dispatch, stagedCallback != null || stagedRead || commands.nonEmpty)
    if (next eq dispatch) false
    else { dispatch = next; takeHead() }
  }
  private def takeNext(): Boolean =
    if (dispatchPaused(dispatch) || !takeHead()) { dispatch = releaseDispatch(dispatch); false }
    else true
  private inline def change[B](inline body: B): B = {
    var claimed = false
    val result = monitor.synchronized {
      val value = body
      claimed = claimFirst()
      value
    }
    if (claimed) drain()
    result
  }

  /** Runs the claimed command and its successors outside the monitor. */
  private def drain(): Unit = {
    var more = true
    while (more) {
      try {
        val callback = handCallback
        if (callback != null) {
          val outcome = handOutcome
          handCallback = null; handOutcome = null
          callback(outcome)
        } else if (handRead) { handRead = false; readFuture() }
        else {
          val action = handAction
          handAction = null
          action()
        }
      } catch { case NonFatal(error) => report(error) }
      more = monitor.synchronized(takeNext())
    }
  }
  private def discardValues(data: LaneData): Unit = {
    if (data.values.size == capacity) storage.full -= 1
    data.values.clear()
  }
  private def completePulls(data: LaneData, result: Outcome): Unit = {
    if (data.hasPull) storage.demanding -= 1
    while (data.hasPull) enqueue(data.removePull(), result)
  }
  private def place(data: LaneData, decision: Recipient, value: A, result: Outcome): Unit =
    if (decision eq Recipient.Deliver) {
      val callback = data.removePull()
      if (!data.hasPull) storage.demanding -= 1
      enqueue(callback, result)
    } else if (decision eq Recipient.Store) {
      data.values.append(value)
      if (data.values.size == capacity) storage.full += 1
    } else throw new IllegalStateException("Stream read credit was exceeded")
  private def decide(data: LaneData): Recipient = recipient(data.hasPull, data.values.size, capacity)
  private def fail(error: Throwable): Boolean = {
    val wasOpen = control.phase == Phase.Open
    val cleanup = failureCleanup(mode, control.phase, control.cleanupClaimed)
    control = failControl(control, mode, error)
    if (cleanup) dispatch = pauseDispatch(dispatch)
    if (wasOpen) {
      val result: Outcome = Left(error)
      storage.lanes.valuesIterator.foreach { data =>
        if (mode != Mode.Buffered) discardValues(data)
        completePulls(data, result)
      }
    }
    wakeReader()
    cleanup
  }
  private def end(): Boolean = {
    val wasOpen = control.phase == Phase.Open
    control = endControl(control)
    if (wasOpen) storage.lanes.valuesIterator.foreach(completePulls(_, endToken))
    wakeReader()
    false
  }
  private def settleLate(): Boolean = change { control = settleControl(control); false }
  private def completeValue(value: A, result: Outcome): Boolean =
    if (!open) settleLate()
    else {
      val index = if (router == null) -1 else router(value)
      if (router != null && (index < 0 || index >= fixedData.length))
        throw new IllegalArgumentException(s"Invalid stream rack index: $index")
      change {
        if (!(control.phase eq Phase.Open)) { control = settleControl(control); false }
        else {
          val target = if (index >= 0) fixedData(index) else storage.single
          if (target != null) {
            val decision = decide(target)
            if (index >= 0 && (decision eq Recipient.Overflow)) fail(StreamSortOverflowException(index, capacity))
            else {
              control = settleControl(control)
              place(target, decision, value, result)
              wakeReader()
              false
            }
          } else {
            control = settleControl(control)
            storage.lanes.valuesIterator.foreach(data => place(data, decide(data), value, result))
            wakeReader()
            false
          }
        }
      }
    }
  private def completeTerminal(result: Outcome): Boolean = result match {
    case Left(error) => change(fail(error))
    case _           => change(end())
  }
  private def complete(result: Outcome): Boolean = result match {
    case Right(Some(value)) => completeValue(value, result)
    case _                  => completeTerminal(result)
  }
  private def completeFuture(result: Outcome): Unit = {
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
    if (needsCleanup) startCleanup()
  }
  private def startCleanup(): Unit =
    try F.runAsync(cleanup())(_ => ())
    catch { case NonFatal(error) => report(error) }
  private def readFuture(): Unit =
    if (open) {
      val read =
        try upstream.pull()
        catch { case error: Throwable => F.fail[Option[A]](error) }
      try F.runAsync(read)(futureReceiver)
      catch { case NonFatal(error) => completeFuture(Left(error)) }
    } else settleLate()

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
      F.delayAsync(if (open) upstream.pull() else F.none[A])
        .map[Outcome](Right(_))
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
        case Left(error) if open => report(error)
        case _                   => ()
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
  private def register(stable: LaneData, lane: Lane, callback: Callback): Unit = change {
    val data = if (stable != null) stable else storage.lanes.getOrElse(lane, null)
    if (data == null) enqueue(callback, endToken)
    else if (data.values.nonEmpty) {
      if (data.values.size == capacity) storage.full -= 1
      enqueue(callback, Right(Some(data.values.removeHead())))
    } else {
      val phase = control.phase
      if (phase eq Phase.Open) {
        if (!data.hasPull) storage.demanding += 1
        data.appendPull(callback)
      } else
        phase match {
          case Phase.Failed(error) => enqueue(callback, Left(error))
          case _                   => enqueue(callback, endToken)
        }
    }
    wakeReader()
  }
  def stream(lane: Lane): Stream[F, A] = {
    // Partition/buffer lanes are never removed. Hub membership remains dynamic.
    val stable = if (mode == Mode.Fanout) null else monitor.synchronized(storage.lanes.getOrElse(lane, null))
    new Stream[F, A] {
      private val registration: Callback => Unit = callback => register(stable, lane, callback)
      def pull(): F[Option[A]] =
        if (futureSemantics) {
          try F.promise[Option[A]](registration)
          catch { case error: Throwable => F.fail(error) }
        } else StreamRoutingRuntime.protect(F.promise[Option[A]](registration))(cancel())
      def cancel(): F[Unit] = cancelLane(Some(lane))
    }
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
    var claimed = false
    val values = monitor.synchronized {
      val values = storage.lanes.get(lane).fold(Vector.empty[A]) { data =>
        val values = data.values.toVector
        discardValues(data)
        values
      }
      wakeReader()
      claimed = claimFirst()
      values
    }
    (values, () => if (claimed) drain())
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
