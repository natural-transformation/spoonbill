package spoonbill.effect

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import spoonbill.effect.syntax.*

/**
 * Pure scalar ownership decisions govern interpreter-owned FIFO data/handle
 * stores. Native effects retain one persistent reader fiber. Future has no
 * native cancellation/fiber locals; its independent callback pump uses the same
 * credit and terminal decisions without building a per-item effect loop. Each
 * of the two is a subclass that names its own pull and outcome types.
 */
private[effect] abstract class StreamRoutingRuntime[F[_], A](
  upstream: Stream[F, A],
  mode: StreamRoutingMachine.Mode,
  capacity: Int,
  fixed: Vector[StreamRoutingMachine.Lane],
  route: Option[A => Int]
)(using F: Effect[F]) {
  import StreamRoutingMachine.*
  require(capacity > 0, "Stream buffer capacity must be positive")

  /**
   * A waiting pull and a settled read, each in the form its effect hands on.
   */
  protected type Pull >: Null <: AnyRef
  protected type Outcome >: Null <: AnyRef
  protected def succeeded(value: Option[A]): Outcome
  protected def failed(error: Throwable): Outcome
  protected def settle(pull: Pull, outcome: Outcome): Unit

  /**
   * Called only while owning monitor. Whatever reads upstream goes on when
   * credit allows; its start is queued as a command.
   */
  protected def wakeReader(): Unit
  def stream(lane: Lane): Stream[F, A]

  private enum CleanupStart { case Waiting, Running, Abandoned }
  protected final class LaneData {
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

  // Phase, read ownership and the cleanup claim are one value. It is written
  // under monitor and published, so that a terminal phase is seen without it.
  @volatile protected var control: Control = idleControl

  // Dispatch ownership is one value. One owner at a time runs the commands of
  // the FIFO outside monitor. Every transition is made under monitor except
  // one: an owner with nothing queued releases by itself.
  private val dispatch          = new AtomicReference(Dispatch.Idle)
  protected val commands        = mutable.ArrayDeque.empty[() => Unit]
  private val endToken: Outcome = succeeded(None)

  protected def report(error: Throwable): Unit =
    Reporter.PrintReporter.error("Stream operator background work failed", error)
  private inline def notify(pull: Pull, outcome: Outcome): Unit =
    try settle(pull, outcome)
    catch { case NonFatal(error) => report(error) }
  private def enqueue(pull: Pull, outcome: Outcome): Unit = commands.append(() => settle(pull, outcome))
  protected def canReadNow: Boolean = {
    val current = control
    readAllowed(mode, current.phase, current.reader, storage.lanes.nonEmpty, storage.demanding > 0, storage.full == 0)
  }

  /**
   * Called only while owning monitor. An owner's release is the only change
   * made elsewhere, so a transition that loses to it is applied to the released
   * dispatcher. Returns the state it replaced.
   */
  private inline def moveDispatch(inline transition: Dispatch => Dispatch): Dispatch = {
    var before = dispatch.get()
    var after  = transition(before)
    while ((after ne before) && !dispatch.compareAndSet(before, after)) {
      before = dispatch.get()
      after = transition(before)
    }
    before
  }

  /**
   * Called only while owning monitor, as the last step of its section.
   * Reservation invokes no effect. The first command is returned to the thread
   * that becomes the owner by taking it. A running owner is told instead: its
   * release and this claim change the same value, so one of them sees the
   * other.
   */
  protected def claimFirst(): () => Unit = {
    val queued = commands.size
    if (queued > 0 && dispatchClaimable(moveDispatch(claimDispatch(_, queued)))) commands.removeHead() else null
  }

  /**
   * Called only while owning monitor. A command that would be the whole FIFO of
   * a claimable dispatcher needs no entry in it: its thread becomes the owner
   * and holds the command itself. Whatever the section queues afterwards is
   * behind it. Until the section ends only this thread reads the claim.
   */
  protected inline def claimDirect(): Boolean = commands.isEmpty && {
    val state = dispatch.get()
    dispatchClaimable(state) && { dispatch.lazySet(claimDispatch(state, 1)); true }
  }

  /** Called only while owning monitor, as the last step of its section. */
  protected def wakeAndClaim(): () => Unit = { wakeReader(); claimFirst() }

  /**
   * Called only by the owner, outside monitor. With nothing queued it releases
   * by itself, unless a claim or a pause changes the dispatcher first. It then
   * decides under monitor.
   */
  private inline def next(): () => Unit = {
    val state = dispatch.get()
    if (dispatchReleasable(state) && dispatch.compareAndSet(state, advanceDispatch(state, 0))) null
    else monitor.synchronized(takeNext())
  }

  /** Called only by the owner while owning monitor: nothing else moves now. */
  private def takeNext(): () => Unit = {
    val state = advanceDispatch(dispatch.get(), commands.size)
    dispatch.set(state)
    if (dispatchContinues(state)) commands.removeHead() else null
  }
  private def drain(first: () => Unit): Unit = {
    var action = first
    while (action != null) {
      try action()
      catch { case NonFatal(error) => report(error) }
      action = next()
    }
  }
  protected inline def change[B](inline body: B): B = {
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
  protected def fail(error: Throwable): Boolean = {
    val open    = control.phase == Phase.Open
    val cleanup = failureCleanup(mode, control.phase, control.cleanupClaimed)
    control = failControl(control, mode, error)
    if (cleanup) moveDispatch(pauseDispatch)
    if (open) {
      val outcome = failed(error)
      storage.lanes.valuesIterator.foreach { data =>
        if (mode != Mode.Buffered) discardValues(data)
        completePulls(data, outcome)
      }
    }
    wakeReader()
    cleanup
  }
  protected def end(): Boolean = {
    val open = control.phase == Phase.Open
    control = endControl(control)
    if (open) storage.lanes.valuesIterator.foreach(completePulls(_, endToken))
    wakeReader()
    false
  }
  protected def settleLate(): Boolean = change { control = settleControl(control); false }

  /**
   * Routes one value. Its only waiting pull is settled by this thread as the
   * command it holds itself when dispatch can be claimed for it.
   */
  protected def completeValue(value: A, outcome: Outcome): Boolean =
    if (!(control.phase eq Phase.Open)) settleLate()
    else {
      val index = if (router == null) -1 else router(value)
      if (router != null && (index < 0 || index >= fixedData.length))
        throw new IllegalArgumentException(s"Invalid stream rack index: $index")
      var direct: Pull      = null
      var first: () => Unit = null
      val needsCleanup = monitor.synchronized {
        var cleanup = false
        if (!(control.phase eq Phase.Open)) { control = settleControl(control); first = claimFirst() }
        else {
          val target = if (index >= 0) fixedData(index) else storage.single
          if (target == null) {
            control = settleControl(control)
            storage.lanes.valuesIterator.foreach { data =>
              val decision = decide(data)
              if (decision eq Recipient.Deliver) enqueue(takePull(data), outcome) else store(data, decision, value)
            }
            first = wakeAndClaim()
          } else {
            val decision = decide(target)
            if (index >= 0 && (decision eq Recipient.Overflow)) {
              cleanup = fail(StreamSortOverflowException(index, capacity))
              first = claimFirst()
            } else {
              control = settleControl(control)
              if (decision eq Recipient.Deliver) {
                val pull = takePull(target)
                if (claimDirect()) direct = pull else enqueue(pull, outcome)
              } else store(target, decision, value)
              first = wakeAndClaim()
            }
          }
        }
        cleanup
      }
      if (direct != null) { notify(direct, outcome); first = next() }
      if (first != null) drain(first)
      needsCleanup
    }

  protected def cleanup(): F[Unit] = {
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
      moveDispatch(resumeDispatch)
      ()
    }))
  }

  def newLane(): Lane = change {
    if (control.phase != Phase.Open) throw new IllegalStateException("Hub is closed")
    val lane = new Lane
    storage.lanes.put(lane, new LaneData)
    storage.refreshSingle()
    lane
  }

  /**
   * Partition/buffer lanes are never removed. Hub membership remains dynamic.
   */
  protected def stableData(lane: Lane): LaneData =
    if (mode == Mode.Fanout) null else monitor.synchronized(storage.lanes.getOrElse(lane, null))

  /**
   * Queued data or a terminal phase settles a pull at once, by this thread as
   * the command it holds itself when dispatch can be claimed for it. Otherwise
   * the pull waits in its lane.
   */
  protected def register(stable: LaneData, lane: Lane, pull: Pull): Unit = {
    var direct: Outcome   = null
    var first: () => Unit = null
    monitor.synchronized {
      val data           = if (stable != null) stable else storage.lanes.getOrElse(lane, null)
      var ready: Outcome = null
      if (data == null) ready = endToken
      else if (data.values.nonEmpty) {
        if (data.values.size == capacity) storage.full -= 1
        ready = succeeded(Some(data.values.removeHead()))
      } else {
        control.phase match {
          case Phase.Open =>
            if (!data.hasPull) storage.demanding += 1
            data.appendPull(pull)
          case Phase.Failed(error) => ready = failed(error)
          case _                   => ready = endToken
        }
      }
      if (ready != null) { if (claimDirect()) direct = ready else enqueue(pull, ready) }
      first = wakeAndClaim()
    }
    if (direct != null) { notify(pull, direct); first = next() }
    if (first != null) drain(first)
  }
  protected def cancelLane(lane: Option[Lane]): F[Unit] = F.uncancelable {
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
        if (start) moveDispatch(pauseDispatch)
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
      first = wakeAndClaim()
      values
    }
    (values, () => if (first != null) drain(first))
  }
}

private[effect] object StreamRoutingRuntime {
  import StreamRoutingMachine.*

  /**
   * Every built-in Future instance is served by the callback pump, which calls
   * neither `promise` nor `runAsync` for a pull or a read. Every other effect
   * is served by a reader fiber.
   */
  def apply[F[_], A](
    upstream: Stream[F, A],
    mode: Mode,
    capacity: Int,
    fixed: Vector[Lane] = Vector.empty,
    route: Option[A => Int] = None
  )(using F: Effect[F]): StreamRoutingRuntime[F, A] = F match {
    case future: Effect.BuiltinFutureEffect => new CallbackPump(upstream, mode, capacity, fixed, route)(using future)
    case _                                  => new ReaderFiber(upstream, mode, capacity, fixed, route)
  }

  /**
   * Future has no native cancellation or fiber locals. A read and its result
   * are callbacks of the Futures themselves: a pull is its Promise, and it is
   * completed with the Try that the read was observed with.
   */
  final class CallbackPump[A](
    upstream: Stream[Future, A],
    mode: Mode,
    capacity: Int,
    fixed: Vector[Lane],
    route: Option[A => Int]
  )(using F: Effect.BuiltinFutureEffect)
      extends StreamRoutingRuntime[Future, A](upstream, mode, capacity, fixed, route) {
    protected type Pull    = Promise[Option[A]]
    protected type Outcome = Try[Option[A]]
    protected def succeeded(value: Option[A]): Try[Option[A]]                     = Success(value)
    protected def failed(error: Throwable): Try[Option[A]]                        = Failure(error)
    protected def settle(pull: Promise[Option[A]], outcome: Try[Option[A]]): Unit = { pull.complete(outcome); () }
    private val readCommand: () => Unit                                           = () => read()
    private val receiver: Try[Option[A]] => Unit                                  = received

    protected def wakeReader(): Unit =
      if (canReadNow) { control = readingControl; commands.append(readCommand) }

    /** A read that would be the whole FIFO is held by its thread. */
    override protected def wakeAndClaim(): () => Unit =
      if (canReadNow) {
        control = readingControl
        if (claimDirect()) readCommand else { commands.append(readCommand); claimFirst() }
      } else claimFirst()
    private def read(): Unit =
      if (control.phase eq Phase.Open) {
        val result =
          try upstream.pull()
          catch { case error: Throwable => F.fail[Option[A]](error) }
        try result.onComplete(receiver)(Effect.BuiltinFutureEffect.immediate)
        catch { case NonFatal(error) => received(Failure(error)) }
      } else { settleLate(); () }
    private def complete(result: Try[Option[A]]): Boolean = result match {
      case Success(Some(value)) => completeValue(value, result)
      case Success(None)        => change(end())
      case Failure(error)       => change(fail(error))
    }
    private def received(result: Try[Option[A]]): Unit = {
      val needsCleanup =
        try complete(result)
        catch {
          case error: Throwable =>
            // Normalize failed Future construction once, including interruption,
            // without successful per-item map/recover wrappers.
            if (result.isFailure) throw error
            F.fail[Option[A]](error).onComplete(receiver)(Effect.BuiltinFutureEffect.immediate)
            false
        }
      if (needsCleanup) {
        try F.runAsync(cleanup())(_ => ())
        catch { case NonFatal(error) => report(error) }
      }
    }
    def stream(lane: Lane): Stream[Future, A] = {
      val stable = stableData(lane)
      new Stream[Future, A] {
        def pull(): Future[Option[A]] = {
          val pull = Promise[Option[A]]()
          try { register(stable, lane, pull); pull.future }
          catch { case error: Throwable => Future.failed(error) }
        }
        def cancel(): Future[Unit] = cancelLane(Some(lane))
      }
    }
    change(wakeReader())
  }

  /**
   * A native effect keeps one persistent reader fiber, with its cancellation
   * and its locals. Pulls and the reader's turns are callbacks of the effect's
   * own `promise`.
   */
  final class ReaderFiber[F[_], A](
    upstream: Stream[F, A],
    mode: Mode,
    capacity: Int,
    fixed: Vector[Lane],
    route: Option[A => Int]
  )(using F: Effect[F])
      extends StreamRoutingRuntime[F, A](upstream, mode, capacity, fixed, route) {
    protected type Pull    = Effect.Promise[Option[A]]
    protected type Outcome = Either[Throwable, Option[A]]
    protected def succeeded(value: Option[A]): Either[Throwable, Option[A]]                            = Right(value)
    protected def failed(error: Throwable): Either[Throwable, Option[A]]                               = Left(error)
    protected def settle(pull: Effect.Promise[Option[A]], outcome: Either[Throwable, Option[A]]): Unit = pull(outcome)
    private var reader: Effect.Promise[Boolean]                                                        = null

    protected def wakeReader(): Unit =
      if (reader != null) {
        if (control.phase != Phase.Open) {
          val callback = reader; reader = null
          commands.append(() => callback(Right(false)))
        } else if (canReadNow) {
          control = readingControl
          val callback = reader; reader = null
          commands.append(() => callback(Right(control.phase eq Phase.Open)))
        }
      }
    private def complete(result: Either[Throwable, Option[A]]): Boolean = result match {
      case Right(Some(value)) => completeValue(value, result)
      case Right(None)        => change(end())
      case Left(error)        => change(fail(error))
    }
    private def awaitRead(): F[Boolean] = F.promise(callback => change { reader = callback; wakeReader() })
    private def loop(): F[Unit] = awaitRead().flatMap {
      case false => F.unit
      case true =>
        F.delayAsync(if (control.phase eq Phase.Open) upstream.pull() else F.none[A])
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
    def stream(lane: Lane): Stream[F, A] = {
      val stable = stableData(lane)
      new Stream[F, A] {
        private val registration: Effect.Promise[Option[A]] => Unit = pull => register(stable, lane, pull)
        def pull(): F[Option[A]]                                    = protect(F.promise[Option[A]](registration))(cancel())
        def cancel(): F[Unit]                                       = cancelLane(Some(lane))
      }
    }
    private def launchReader(): Unit = {
      val program = F.delayAsync(loop()).recoverF { case error =>
        F.delay(change(fail(error))).flatMap(needsCleanup => if (needsCleanup) cleanup() else F.unit)
      }
      F.runAsync(F.guarantee(program)(readerExited())) {
        case Left(error) if control.phase eq Phase.Open => report(error)
        case _                                          => ()
      }
    }
    launchReader()
  }

  /**
   * Scala Future cannot be natively canceled. Other effects keep the protected
   * pull's success/error boundary, not a promise of irrevocable outer delivery.
   */
  def protect[F[_], A](task: => F[A])(canceled: => F[Unit])(using F: Effect[F]): F[A] =
    if (F.isInstanceOf[Effect.BuiltinFutureEffect]) {
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
