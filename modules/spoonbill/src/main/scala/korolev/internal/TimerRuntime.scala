package spoonbill.internal

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.effect.{Effect, Reporter, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.timer.{RestartKey, TimerId, TimerSettings}

private[spoonbill] object TimerRuntime {
  // Keep the borrowed driver independent of ApplicationInstance: a retired
  // dispatch may remain queued on its executor after its presentation is gone.
  def deadlineDriver[F[_]: Effect](scheduler: Scheduler[F]): Frontend.RpcDeadlineScheduler =
    new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(wake: () => Unit): () => Unit = {
        val job = scheduler.unsafeScheduleOnce(delay)(Effect[F].delay(wake()))
        () => job.unsafeCancel()
      }
    }

  trait Target[F[_]] {
    def owner: TimerMachine.Owner
    def active: Boolean
    def offer(entry: ComponentLaneEntry[F]): Boolean
    def deliver(select: () => Option[Any => Option[Any]]): F[Unit]
  }
}

/**
 * One interpreter per live application; RPC schedulers retain their separate
 * ownership.
 */
private[spoonbill] final class TimerRuntime[F[_]: Effect](
  private[spoonbill] val deadlines: Frontend.RpcDeadlineScheduler,
  clock: () => Long,
  executionContext: ExecutionContext,
  reporter: Reporter,
  settings: TimerSettings
) {
  import TimerMachine.*
  import TimerRuntime.*

  private case class Description(target: Target[F], config: Configuration, update: Any => Option[Any])
  private val lock                 = new Object
  private var state                = State()
  private var descriptions         = Map.empty[Identity, Description]
  private val handles              = mutable.Map.empty[(Identity, Arm), () => Unit]
  private val records              = mutable.Map.empty[Execution, Record]
  private val candidates           = mutable.LinkedHashMap.empty[Identity, Option[Description]]
  private var staged               = Option.empty[Vector[(Identity, Description)]]
  private var rendering            = TimerRenderMachine.initial

  private final class Record(
    val id: Identity,
    val generation: Generation,
    val execution: Execution,
    initialTarget: Target[F]
  ) {
    private var recipient = Some(initialTarget): Option[Target[F]]
    private var offerState = TimerRenderMachine.Offer.Pending
    def detach(): Unit = {
      recipient = None // lock held; queued dispatch retains metadata only
      offerState = TimerRenderMachine.detached(offerState)
    }
    def unoffered: Boolean = TimerRenderMachine.unoffered(offerState)
    val entry             = new ComponentLaneEntry[F](() => run(), () => settle(this, failed = false))
    def run(): F[Unit] = {
      advance(Event.Start(id, execution))
      Effect[F]
        .fork(Effect[F].delayAsync {
          val target = lock.synchronized(if (currentLocked(this)) recipient else None)
          target.fold(Effect[F].unit)(
            _.deliver(() =>
              lock.synchronized {
                if (currentLocked(this)) descriptions.get(id).map(_.update) else None
              }
            )
          )
        })(executionContext)
        .flatMap(_ => Effect[F].delay(settle(this, failed = false)))
        .recover { case error =>
          settle(this, failed = true)
          reportFailure(error)
        }
    }
    def dispatch(): Unit =
      try
        Effect[F]
          .fork(Effect[F].delay {
            val target = lock.synchronized {
              if (!records.get(execution).contains(this)) None
              else
                recipient.filter(_.active).map { value =>
                  offerState = TimerRenderMachine.offered(offerState); value
                }
            }
            if (!target.exists(_.offer(entry))) entry.discard()
          })(executionContext)
          .runAsync {
            case Left(error) => settle(this, failed = true); reportFailure(error)
            case Right(_)    => ()
          }
      catch { case NonFatal(error) => settle(this, failed = true); reportFailure(error) }
  }

  private def currentLocked(record: Record): Boolean =
    state.slots.get(record.id).exists { slot =>
      slot.config.nonEmpty && slot.generation == record.generation && slot.phase != Phase.Failed &&
      slot.entry.exists(_.execution == record.execution)
    }

  private def reportFailure(error: Throwable): Unit = error match {
    case _: spoonbill.server.SessionAccessDenied => ()
    case _                                       => reporter.error("Render timer failed", error)
  }

  private def stepLocked(event: Event): Vector[Command] = {
    val (next, commands) = TimerMachine.step(state, event, clock())
    state = next
    commands.foreach {
      case Command.Exhausted =>
        rendering = TimerRenderMachine.report(rendering, "Render timer token space exhausted; timers remain stopped until remount.")
      case Command.Dispatch(id, generation, execution) =>
        descriptions.get(id).foreach { description =>
          records.put(execution, new Record(id, generation, execution, description.target))
        }
      case _ => ()
    }
    // Completed/failed markers retain identity/configuration, never application closures.
    val changed =
      if (state.exhausted) descriptions.keysIterator.toVector
      else
        event match {
          case Event.Accept(_)        => descriptions.keysIterator.toVector
          case Event.Close(owner)     => descriptions.keysIterator.filter(_.owner == owner).toVector
          case Event.Settle(id, _, _) => Vector(id)
          case Event.Fail(id, _)      => Vector(id)
          case _                      => Vector.empty
        }
    changed.foreach { id =>
      if (
        // A queued execution can Start after exhaustion. Its execution marker
        // must never let a later accepted render reinstall application reducers.
        state.exhausted || !state.slots
          .get(id)
          .exists(slot => slot.config.nonEmpty && slot.phase != Phase.Completed && slot.phase != Phase.Failed)
      ) descriptions -= id
    }
    commands
  }

  private def advance(event: Event): Unit = {
    val commands = lock.synchronized {
      event match {
        // Reserve a retiring idle slot for its selected replacement. Its due
        // wake waits for commit/abort, without changing the installed machine.
        case wake: Event.Wake if TimerRenderMachine.reserved(rendering, wake.id) =>
          rendering = TimerRenderMachine.defer(rendering, wake)
          Vector.empty
        case _ => stepLocked(event)
      }
    }
    execute(commands)
  }

  private def settle(record: Record, failed: Boolean): Unit = {
    val commands = lock.synchronized {
      if (!records.get(record.execution).contains(record)) Vector.empty
      else {
        records.remove(record.execution)
        record.detach()
        stepLocked(Event.Settle(record.id, record.execution, failed))
      }
    }
    execute(commands)
  }

  private def execute(commands: Vector[Command]): Unit = commands.foreach {
    case Command.Exhausted => () // Queued for flush together with render diagnostics.
    case Command.Cancel(id, token) =>
      val cancel = lock.synchronized(handles.remove(id -> token))
      cancel.foreach { run =>
        try run()
        catch { case NonFatal(error) => reportFailure(error) }
      }
    case Command.Dispatch(_, _, token) => lock.synchronized(records.get(token)).foreach(_.dispatch())
    case Command.Schedule(id, generation, token, deadline) =>
      def currentArm: Boolean =
        lock.synchronized(state.slots.get(id).exists(s => s.generation == generation && s.arm.contains(token)))
      if (currentArm) {
        try {
          val cancel = deadlines.schedule(TimerMachine.remainingMillis(clock(), deadline).millis) { () =>
            lock.synchronized(handles.remove(id -> token))
            advance(Event.Wake(id, generation, token))
          }
          val installed = lock.synchronized {
            if (currentArm) { handles.put(id -> token, cancel); true }
            else false
          }
          if (!installed) cancel()
        } catch { case NonFatal(error) => advance(Event.Fail(id, generation)); reportFailure(error) }
      }
  }

  def beginRender(): Unit = lock.synchronized {
    require(TimerRenderMachine.canBegin(rendering), "Previous timer render has not settled")
    candidates.clear()
    rendering = TimerRenderMachine.begun(rendering)
    staged = None
  }

  def collect[S](
    target: Target[F],
    id: TimerId[S],
    nanos: Long,
    periodic: Boolean,
    key: RestartKey,
    update: S => Option[S]
  ): Unit = lock.synchronized {
    val identity = Identity(target.owner, id)
    val previous = candidates.get(identity) match {
      case None => TimerRenderMachine.Declaration.Missing
      case Some(Some(_)) => TimerRenderMachine.Declaration.Valid
      case Some(None) => TimerRenderMachine.Declaration.Omitted
    }
    val valid = previous != TimerRenderMachine.Declaration.Missing || (TimerId.valid(id) && RestartKey.valid(key))
    TimerRenderMachine.collection(previous, valid) match {
      case TimerRenderMachine.Collection.Accept =>
        candidates.put(identity, Some(Description(target, Configuration(nanos, periodic, key), update.asInstanceOf[Any => Option[Any]])))
      case TimerRenderMachine.Collection.Invalid =>
        rendering = TimerRenderMachine.invalid(rendering, target.owner)
        candidates.put(identity, None)
      case TimerRenderMachine.Collection.Conflict =>
        rendering = TimerRenderMachine.conflict(rendering, target.owner, TimerId.diagnostic(id))
        candidates.put(identity, None)
      case TimerRenderMachine.Collection.Omit => candidates.put(identity, None)
    }
  }

  private def select(values: Vector[(Identity, Description)]): Vector[(Identity, Description)] = {
    val eligible  = values.filter(_._2.target.active)
    val present   = eligible.iterator.map(_._1).toSet
    val existing  = eligible.filter(x => state.slots.contains(x._1))
    val remaining = TimerRenderMachine.freshBudget(state, present, existing.size, settings.maxRetained)
    val fresh     = eligible.filterNot(x => state.slots.contains(x._1))
    fresh.drop(remaining).foreach { case (id, _) =>
      rendering = TimerRenderMachine.overflow(rendering, id.owner)
    }
    existing ++ fresh.take(remaining)
  }

  /** Releases the unbounded traversal map before asynchronous publication. */
  def prepareRender(): Unit = lock.synchronized {
    val selected = select(candidates.iterator.collect { case (id, Some(value)) => id -> value }.toVector)
    staged = Some(selected)
    val present = selected.iterator.map(_._1).toSet
    rendering = TimerRenderMachine.reserve(rendering, state.slots.keySet -- present)
    candidates.clear()
  }

  /** Only metadata inside the frontend's publication critical section. */
  def acceptTimerRender(): Unit = lock.synchronized {
    staged.foreach { values =>
      val selected = select(values)
      descriptions = selected.toMap
      val commands = stepLocked(Event.Accept(selected.map { case (id, value) => id -> value.config }))
      rendering = TimerRenderMachine.accepted(rendering, state, commands)
      val now = clock()
      rendering = TimerRenderMachine.retainCurrentSuppression(rendering)
      rendering.issues.foreach { case (owner, issue) =>
        if (TimerRenderMachine.reportDue(rendering, owner, issue, now)) {
          val retired = state.slots.count { case (_, slot) => slot.config.isEmpty && slot.entry.nonEmpty }
          val report = s"Timer omissions owner=${owner.value}: invalid=${issue.invalid}, conflicts=${issue.duplicate}, " +
            s"overflow=${issue.overflow}, capacity=${settings.maxRetained}, retained=${state.slots.size}, " +
            s"retired-unsettled=$retired, admitted=${selected.size}, samples=${issue.samples.mkString(",")}. " +
            "Omitted timers require a later accepted render to retry."
          rendering = TimerRenderMachine.reported(rendering, owner, issue, now, report)
        }
      }
    }
    staged = None
    rendering = TimerRenderMachine.finishedRender(rendering)
  }

  def abortRender(): Unit = {
    val wakes = lock.synchronized {
      candidates.clear(); staged = None
      val wakes = TimerRenderMachine.deferred(rendering)
      rendering = TimerRenderMachine.finishedRender(rendering)
      wakes
    }
    wakes.foreach(advance)
  }

  /**
   * Startup enables this only after both transition and render consumers exist.
   */
  def flush(): Unit = {
    val (commands, reports) = lock.synchronized {
      val commands = TimerRenderMachine.commandsToRun(rendering)
      val reports = rendering.reports
      rendering = TimerRenderMachine.flushed(rendering)
      (commands, reports)
    }
    reports.foreach(reporter.warning)
    execute(commands)
  }
  def start(): Unit = { lock.synchronized { rendering = TimerRenderMachine.started(rendering) }; flush() }

  def close(owner: Owner): Unit = {
    val (commands, unoffered) = lock.synchronized {
      rendering = TimerRenderMachine.ownerClosed(rendering, owner)
      staged = staged.map(_.filterNot(_._1.owner == owner))
      candidates.keysIterator.filter(_.owner == owner).toVector.foreach(candidates.remove)
      val commands = stepLocked(Event.Close(owner))
      (commands, records.values.filter(r => r.id.owner == owner && r.unoffered).toVector)
    }
    execute(commands)
    unoffered.foreach(_.entry.discard())
  }

  private[spoonbill] def snapshot: State = lock.synchronized(state)
  private[spoonbill] def retainedDescriptions: Int =
    lock.synchronized(descriptions.size + staged.fold(0)(_.size) + candidates.size)
}
