package spoonbill.effect

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
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
  private type Settled  = scala.concurrent.Promise[Option[A]]

  /** A waiting pull and its result, in the form its effect settles directly. */
  private type Pull    = Callback | Settled
  private type Outcome = Either[Throwable, Option[A]] | Try[Option[A]]
  private enum CleanupStart { case Waiting, Running, Abandoned }
  private final class LaneData {
    val values = mutable.ArrayDeque.empty[A]
    // Keep the usual single waiter directly; overlapping pulls retain FIFO.
    private var firstPull: Pull                      = null
    private var laterPulls: mutable.ArrayDeque[Pull] = null
    def hasPull: Boolean                             = firstPull != null
    def appendPull(pull: Pull): Unit =
      if (firstPull == null) firstPull = pull
      else {
        if (laterPulls == null) laterPulls = mutable.ArrayDeque.empty[Pull]
        laterPulls.append(pull)
      }
    def removePull(): Pull = {
      val pull = firstPull
      if (pull == null) throw new NoSuchElementException("No pending stream pull")
      firstPull = if (laterPulls != null && laterPulls.nonEmpty) laterPulls.removeHead() else null
      pull
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
  private val monitor                    = new Object
  private val storage                    = new Storage
  private val fixedData: Array[LaneData] = fixed.map(storage.lanes).toArray
  private val router: A => Int           = route.orNull
  private val futureSemantics            = F.isInstanceOf[Effect.FutureEffect]
  // A FutureEffect that keeps its own callbacks is served by their plain
  // equivalents: a pull's Promise is its own handle and a read is observed as
  // a Try. Outcomes are then Try values; every other effect uses Either.
  private val plain = Effect.FutureEffect.keepsCallbacks(F)

  // Phase is the sole published terminal authority, written under monitor.
  // Reader and cleanup ownership are read and written only under monitor.
  @volatile private var phase: Phase = Phase.Open
  private var reading                = false
  private var cleanupClaimed         = false
  private def control: Control = {
    val current = phase
    if ((current eq Phase.Open) && !cleanupClaimed) { if (reading) readingControl else idleControl }
    else Control(current, if (reading) Reader.Reading else Reader.Waiting, cleanupClaimed)
  }
  private def control_=(next: Control): Unit = {
    reading = next.reader eq Reader.Reading
    cleanupClaimed = next.cleanupClaimed
    if (!(phase eq next.phase)) phase = next.phase
  }

  // One owner at a time runs commands outside monitor. `running` is set under
  // monitor by the thread that takes ownership and cleared only by that owner.
  // The FIFO is one staged read, then `commands`; `backlog` republishes that it
  // is not empty, so that an owner can release without taking monitor.
  private val running                                              = new AtomicBoolean(false)
  private var paused                                               = false
  @volatile private var backlog                                    = false
  private var stagedRead                                           = false
  private val commands                                             = mutable.ArrayDeque.empty[() => Unit]
  private var reader: Effect.Promise[Boolean]                      = null
  private val endToken: Outcome                                    = if (plain) Success(None) else Right(None)
  private val readCommand: () => Unit                              = () => readFuture()
  private val futureReceiver: Either[Throwable, Option[A]] => Unit = completeFuture
  private val plainReceiver: Try[Option[A]] => Unit                = completePlain

  private def report(error: Throwable): Unit =
    Reporter.PrintReporter.error("Stream operator background work failed", error)
  private def valued(value: A): Outcome         = if (plain) Success(Some(value)) else Right(Some(value))
  private def failed(error: Throwable): Outcome = if (plain) Failure(error) else Left(error)
  private def settle(pull: Pull, outcome: Outcome): Unit =
    if (plain) pull.asInstanceOf[Settled].complete(outcome.asInstanceOf[Try[Option[A]]])
    else pull.asInstanceOf[Callback](outcome.asInstanceOf[Either[Throwable, Option[A]]])
  private def notify(pull: Pull, outcome: Outcome): Unit =
    try settle(pull, outcome)
    catch { case NonFatal(error) => report(error) }

  private def hasCommands: Boolean = stagedRead || commands.nonEmpty
  private def publishBacklog(): Unit = {
    val pending = hasCommands
    if (backlog != pending) backlog = pending
  }
  private def enqueue(pull: Pull, outcome: Outcome): Unit = commands.append(() => settle(pull, outcome))
  private def enqueueRead(): Unit =
    if (!stagedRead && commands.isEmpty) stagedRead = true else commands.append(readCommand)
  private def canReadNow: Boolean =
    readAllowed(
      mode,
      phase,
      if (reading) Reader.Reading else Reader.Waiting,
      storage.lanes.nonEmpty,
      storage.demanding > 0,
      storage.full == 0
    )
  private def wakeReader(): Unit =
    if (futureSemantics) {
      if (canReadNow) { control = readingControl; enqueueRead() }
    } else if (reader != null) {
      if (control.phase != Phase.Open) {
        val callback = reader; reader = null
        commands.append(() => callback(Right(false)))
      } else if (canReadNow) {
        control = readingControl
        val callback = reader; reader = null
        commands.append(() => callback(Right(phase eq Phase.Open)))
      }
    }

  /** Called only while owning monitor, by the thread that takes ownership. */
  private def takeHead(): () => Unit = {
    val staged = stagedRead
    if (staged) stagedRead = false
    val head = if (staged) readCommand else commands.removeHead()
    publishBacklog()
    head
  }

  /**
   * Called only while owning monitor. Reservation invokes no effect. A running
   * owner is told about the FIFO before its ownership is read again: either its
   * release observes `backlog`, or this caller observes the release.
   */
  private def claimFirst(): () => Unit = {
    val pending = hasCommands
    if (running.get()) {
      if (!pending) return null
      if (!backlog) backlog = true
      if (running.get()) return null
    }
    if (dispatchClaimable(running = false, paused, pending)) { running.lazySet(true); takeHead() }
    else null
  }

  /**
   * Called only while owning monitor. A notification that would be the whole
   * FIFO of an idle dispatcher needs no entry in it: its thread owns dispatch
   * for it. The claim itself is the last step of that monitor section.
   */
  private def directAllowed: Boolean = !hasCommands && dispatchClaimable(running.get(), paused, pending = true)
  private def claimDirect(): Unit    = { publishBacklog(); running.lazySet(true) }

  /**
   * Called only by the owner, outside monitor. An owner with a backlog keeps
   * ownership for it. Otherwise it releases with a volatile write that precedes
   * its reread of `backlog`; `running` is otherwise read only under monitor.
   */
  private def next(): () => Unit = {
    val owned = backlog
    if (!owned) {
      running.set(false)
      if (!backlog) return null
    }
    monitor.synchronized(takeNext(owned))
  }

  /**
   * Called only while owning monitor. An owner continues or releases; a
   * released owner claims again unless another thread did.
   */
  private def takeNext(owned: Boolean): () => Unit =
    if (dispatchClaimable(!owned && running.get(), paused, hasCommands)) {
      if (!owned) running.lazySet(true)
      takeHead()
    } else {
      if (owned) running.set(false)
      null
    }
  private def drain(first: () => Unit): Unit = {
    var action = first
    while (action != null) {
      try action()
      catch { case NonFatal(error) => report(error) }
      action = next()
    }
  }
  private inline def change[B](inline body: B): B = {
    var first: () => Unit = null
    val result = monitor.synchronized {
      val value = body
      first = claimFirst()
      value
    }
    if (first != null) drain(first)
    result
  }
  private def discardValues(data: LaneData): Unit = {
    if (data.values.size == capacity) storage.full -= 1
    data.values.clear()
  }
  private def completePulls(data: LaneData, outcome: Outcome): Unit = {
    if (data.hasPull) storage.demanding -= 1
    while (data.hasPull) enqueue(data.removePull(), outcome)
  }
  private def decide(data: LaneData): Recipient = recipient(data.hasPull, data.values.size, capacity)
  private def takePull(data: LaneData): Pull = {
    val pull = data.removePull()
    if (!data.hasPull) storage.demanding -= 1
    pull
  }
  private def store(data: LaneData, decision: Recipient, value: A): Unit =
    if (decision eq Recipient.Store) {
      data.values.append(value)
      if (data.values.size == capacity) storage.full += 1
    } else throw new IllegalStateException("Stream read credit was exceeded")
  private def fail(error: Throwable): Boolean = {
    val wasOpen = control.phase == Phase.Open
    val cleanup = failureCleanup(mode, control.phase, control.cleanupClaimed)
    control = failControl(control, mode, error)
    if (cleanup) paused = true
    if (wasOpen) {
      val outcome = failed(error)
      storage.lanes.valuesIterator.foreach { data =>
        if (mode != Mode.Buffered) discardValues(data)
        completePulls(data, outcome)
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

  /**
   * Routes one value. Its only waiting pull is settled as this thread's own
   * first command when dispatch is idle; every other shape uses the FIFO.
   */
  private def completeValue(value: A, outcome: Outcome): Boolean =
    if (!(phase eq Phase.Open)) settleLate()
    else {
      val index = if (router == null) -1 else router(value)
      if (router != null && (index < 0 || index >= fixedData.length))
        throw new IllegalArgumentException(s"Invalid stream rack index: $index")
      var direct: Pull      = null
      var first: () => Unit = null
      val needsCleanup = monitor.synchronized {
        var cleanup = false
        if (!(phase eq Phase.Open)) control = settleControl(control)
        else {
          val target = if (index >= 0) fixedData(index) else storage.single
          if (target == null) {
            control = settleControl(control)
            storage.lanes.valuesIterator.foreach { data =>
              val decision = decide(data)
              if (decision eq Recipient.Deliver) enqueue(takePull(data), outcome) else store(data, decision, value)
            }
            wakeReader()
          } else {
            val decision = decide(target)
            if (index >= 0 && (decision eq Recipient.Overflow))
              cleanup = fail(StreamSortOverflowException(index, capacity))
            else {
              control = settleControl(control)
              if (decision eq Recipient.Deliver) {
                val pull = takePull(target)
                if (directAllowed) direct = pull else enqueue(pull, outcome)
              } else store(target, decision, value)
              wakeReader()
            }
          }
        }
        if (direct == null) first = claimFirst() else claimDirect()
        cleanup
      }
      if (direct != null) { notify(direct, outcome); first = next() }
      if (first != null) drain(first)
      needsCleanup
    }
  private def complete(result: Either[Throwable, Option[A]]): Boolean = result match {
    case Right(Some(value)) => completeValue(value, result)
    case Right(None)        => change(end())
    case Left(error)        => change(fail(error))
  }
  private def completeTry(result: Try[Option[A]]): Boolean = result match {
    case Success(Some(value)) => completeValue(value, result)
    case Success(None)        => change(end())
    case Failure(error)       => change(fail(error))
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
    if (needsCleanup) startCleanup()
  }
  private def completePlain(result: Try[Option[A]]): Unit = {
    val needsCleanup =
      try completeTry(result)
      catch {
        case error: Throwable =>
          if (result.isFailure) throw error
          Future.failed[Option[A]](error).onComplete(plainReceiver)(Effect.FutureEffect.immediate)
          false
      }
    if (needsCleanup) startCleanup()
  }
  private def startCleanup(): Unit =
    try F.runAsync(cleanup())(_ => ())
    catch { case NonFatal(error) => report(error) }
  private def readFuture(): Unit =
    if (!(phase eq Phase.Open)) settleLate()
    else if (plain) {
      val read =
        try upstream.pull().asInstanceOf[Future[Option[A]]]
        catch { case error: Throwable => Future.failed[Option[A]](error) }
      try read.onComplete(plainReceiver)(Effect.FutureEffect.immediate)
      catch { case NonFatal(error) => completePlain(Failure(error)) }
    } else {
      val read =
        try upstream.pull()
        catch { case error: Throwable => F.fail[Option[A]](error) }
      try F.runAsync(read)(futureReceiver)
      catch { case NonFatal(error) => completeFuture(Left(error)) }
    }

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
      // Claim reset and dispatch resumption are atomic, so an old failure
      // cannot unpause a newer cleanup attempt.
      if (start.get() == CleanupStart.Abandoned) control = control.copy(cleanupClaimed = false)
      paused = false
    }))
  }
  private def awaitRead(): F[Boolean] = F.promise(callback => change { reader = callback; wakeReader() })
  private def loop(): F[Unit] = awaitRead().flatMap {
    case false => F.unit
    case true =>
      F.delayAsync(if (phase eq Phase.Open) upstream.pull() else F.none[A])
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
        case Left(error) if phase eq Phase.Open => report(error)
        case _                                  => ()
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

  /**
   * Queued data or a terminal phase settles a pull at once, as this thread's
   * own first command when dispatch is idle. Otherwise it waits in its lane.
   */
  private def register(stable: LaneData, lane: Lane, pull: Pull): Unit = {
    var direct: Outcome   = null
    var first: () => Unit = null
    monitor.synchronized {
      val data           = if (stable != null) stable else storage.lanes.getOrElse(lane, null)
      var ready: Outcome = null
      if (data == null) ready = endToken
      else if (data.values.nonEmpty) {
        if (data.values.size == capacity) storage.full -= 1
        ready = valued(data.values.removeHead())
      } else {
        val current = phase
        if (current eq Phase.Open) {
          if (!data.hasPull) storage.demanding += 1
          data.appendPull(pull)
        } else
          ready = current match {
            case Phase.Failed(error) => failed(error)
            case _                   => endToken
          }
      }
      if (ready != null) { if (directAllowed) direct = ready else enqueue(pull, ready) }
      wakeReader()
      if (direct == null) first = claimFirst() else claimDirect()
    }
    if (direct != null) { notify(pull, direct); first = next() }
    if (first != null) drain(first)
  }
  def stream(lane: Lane): Stream[F, A] = {
    // Partition/buffer lanes are never removed. Hub membership remains dynamic.
    val stable = if (mode == Mode.Fanout) null else monitor.synchronized(storage.lanes.getOrElse(lane, null))
    new Stream[F, A] {
      private val registration: Callback => Unit = callback => register(stable, lane, callback)
      def pull(): F[Option[A]] =
        if (plain) {
          val promise = scala.concurrent.Promise[Option[A]]()
          try { register(stable, lane, promise); promise.future.asInstanceOf[F[Option[A]]] }
          catch { case error: Throwable => F.fail(error) }
        } else if (futureSemantics) {
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
        if (start) paused = true
        val outcome = control.phase match { case Phase.Failed(error) => failed(error); case _ => endToken }
        storage.lanes.valuesIterator.foreach { data =>
          discardValues(data); completePulls(data, outcome)
        }
        start
      }
      wakeReader()
      needsCleanup
    }).flatMap(start => if (start) cleanup() else F.unit)
  }
  def cancel(): F[Unit] = cancelLane(None)
  def drainDeferred(lane: Lane): (Vector[A], () => Unit) = {
    var first: () => Unit = null
    val values = monitor.synchronized {
      val values = storage.lanes.get(lane).fold(Vector.empty[A]) { data =>
        val values = data.values.toVector
        discardValues(data)
        values
      }
      wakeReader()
      first = claimFirst()
      values
    }
    (values, () => if (first != null) drain(first))
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
