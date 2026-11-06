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
  private case class Issues(
    invalid: Int = 0,
    duplicate: Int = 0,
    overflow: Int = 0,
    samples: Vector[String] = Vector.empty
  )
  private val lock                 = new Object
  private var state                = State()
  private var descriptions         = Map.empty[Identity, Description]
  private val handles              = mutable.Map.empty[(Identity, Arm), () => Unit]
  private val records              = mutable.Map.empty[Execution, Record]
  private val candidates           = mutable.LinkedHashMap.empty[Identity, Option[Description]]
  private var issues               = Map.empty[Owner, Issues]
  private var staged               = Option.empty[Vector[(Identity, Description)]]
  private var pendingCommands      = Vector.empty[Command]
  private var pendingReports       = Vector.empty[String]
  private var suppression          = Map.empty[Owner, (Issues, Long)]
  private var enabled              = false
  private var exhausted            = false
  private var deferredWakes        = Map.empty[Identity, Event.Wake]
  private var retiringReservations = Set.empty[Identity]

  private final class Record(
    val id: Identity,
    val generation: Generation,
    val execution: Execution,
    initialTarget: Target[F]
  ) {
    private var recipient = Some(initialTarget): Option[Target[F]]
    def detach(): Unit    = recipient = None // lock held; queued dispatch retains metadata only
    var offered           = false            // protected by lock; dispatch also owns a retained entry before offer
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
                  offered = true; value
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
    if (exhausted && event.isInstanceOf[Event.Accept]) { descriptions = Map.empty; return Vector.empty }
    val (next, commands) =
      try TimerMachine.step(state, event, clock())
      catch {
        case _: ArithmeticException =>
          exhausted = true
          pendingReports :+= "Render timer token space exhausted; timers remain stopped until remount."
          val cancel = state.slots.toVector.flatMap { case (id, slot) => slot.arm.map(Command.Cancel(id, _)) }
          val failed = state.copy(slots = state.slots.map { case (id, slot) =>
            id -> slot.copy(phase = Phase.Failed, arm = None)
          })
          (failed, cancel)
      }
    state = next
    commands.foreach {
      case Command.Dispatch(id, generation, execution) =>
        descriptions.get(id).foreach { description =>
          records.put(execution, new Record(id, generation, execution, description.target))
        }
      case _ => ()
    }
    // Completed/failed markers retain identity/configuration, never application closures.
    val changed =
      if (exhausted) descriptions.keysIterator.toVector
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
        !state.slots
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
        case wake: Event.Wake if retiringReservations(wake.id) =>
          deferredWakes = deferredWakes.updated(wake.id, wake)
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
    require(staged.isEmpty, "Previous timer render has not settled")
    candidates.clear()
    issues = Map.empty
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
    if (candidates.contains(identity)) {
      if (candidates(identity).nonEmpty) {
        val previous = issues.getOrElse(target.owner, Issues())
        issues = issues.updated(
          target.owner,
          previous.copy(
            duplicate = previous.duplicate + 1,
            samples = (previous.samples :+ TimerId.diagnostic(id)).take(4)
          )
        )
      }
      candidates.put(identity, None)
    } else if (!TimerId.valid(id) || !RestartKey.valid(key)) {
      val previous = issues.getOrElse(target.owner, Issues())
      issues = issues.updated(target.owner, previous.copy(invalid = previous.invalid + 1))
      candidates.put(identity, None)
    } else
      candidates.put(
        identity,
        Some(Description(target, Configuration(nanos, periodic, key), update.asInstanceOf[Any => Option[Any]]))
      )
  }

  private def select(values: Vector[(Identity, Description)]): Vector[(Identity, Description)] = {
    val eligible  = values.filter(_._2.target.active)
    val present   = eligible.iterator.map(_._1).toSet
    val existing  = eligible.filter(x => state.slots.contains(x._1))
    val retired   = state.slots.count { case (id, slot) => !present(id) && slot.entry.nonEmpty }
    val remaining = math.max(0, settings.maxRetained - existing.size - retired)
    val fresh     = eligible.filterNot(x => state.slots.contains(x._1))
    fresh.drop(remaining).foreach { case (id, _) =>
      val previous = issues.getOrElse(id.owner, Issues())
      issues = issues.updated(id.owner, previous.copy(overflow = previous.overflow + 1))
    }
    existing ++ fresh.take(remaining)
  }

  /** Releases the unbounded traversal map before asynchronous publication. */
  def prepareRender(): Unit = lock.synchronized {
    val selected = select(candidates.iterator.collect { case (id, Some(value)) => id -> value }.toVector)
    staged = Some(selected)
    val present = selected.iterator.map(_._1).toSet
    retiringReservations = state.slots.keySet -- present
    candidates.clear()
  }

  /** Only metadata inside the frontend's publication critical section. */
  def acceptTimerRender(): Unit = lock.synchronized {
    staged.foreach { values =>
      val selected = select(values)
      descriptions = selected.toMap
      val commands = stepLocked(Event.Accept(selected.map { case (id, value) => id -> value.config }))
      if (enabled) pendingCommands ++= commands
      else {
        // Startup can accept several snapshots before consumers are ready.
        // No handles exist yet; keep only the latest bounded set of arms.
        pendingCommands = state.slots.toVector.flatMap { case (id, slot) =>
          slot.arm.map(token => Command.Schedule(id, slot.generation, token, slot.deadline))
        }
      }
      val now = clock()
      suppression = suppression.filter { case (owner, _) => issues.contains(owner) }
      issues.foreach { case (owner, issue) =>
        if (suppression.get(owner).forall { case (previous, time) => previous != issue && now - time >= 1000000000L }) {
          val retired = state.slots.count { case (_, slot) => slot.config.isEmpty && slot.entry.nonEmpty }
          pendingReports :+= s"Timer omissions owner=${owner.value}: invalid=${issue.invalid}, conflicts=${issue.duplicate}, " +
            s"overflow=${issue.overflow}, capacity=${settings.maxRetained}, retained=${state.slots.size}, " +
            s"retired-unsettled=$retired, admitted=${selected.size}, samples=${issue.samples.mkString(",")}. " +
            "Omitted timers require a later accepted render to retry."
          suppression = suppression.updated(owner, issue -> now)
        }
      }
    }
    staged = None
    issues = Map.empty
    retiringReservations = Set.empty
    deferredWakes = Map.empty
  }

  def abortRender(): Unit = {
    val wakes = lock.synchronized {
      candidates.clear(); staged = None; issues = Map.empty
      retiringReservations = Set.empty
      val wakes = deferredWakes.values.toVector
      deferredWakes = Map.empty
      wakes
    }
    wakes.foreach(advance)
  }

  /**
   * Startup enables this only after both transition and render consumers exist.
   */
  def flush(): Unit = {
    val (commands, reports) = lock.synchronized {
      val commands = if (enabled) { val xs = pendingCommands; pendingCommands = Vector.empty; xs }
      else Vector.empty
      val reports = pendingReports
      pendingReports = Vector.empty
      (commands, reports)
    }
    reports.foreach(reporter.warning)
    execute(commands)
  }
  def start(): Unit = { lock.synchronized { enabled = true }; flush() }

  def close(owner: Owner): Unit = {
    val (commands, unoffered) = lock.synchronized {
      suppression -= owner
      staged = staged.map(_.filterNot(_._1.owner == owner))
      candidates.keysIterator.filter(_.owner == owner).toVector.foreach(candidates.remove)
      retiringReservations = retiringReservations.filterNot(_.owner == owner)
      deferredWakes = deferredWakes.filterNot(_._1.owner == owner)
      val commands = stepLocked(Event.Close(owner))
      (commands, records.values.filter(r => r.id.owner == owner && !r.offered).toVector)
    }
    execute(commands)
    unoffered.foreach(_.entry.discard())
  }

  private[spoonbill] def snapshot: State = lock.synchronized(state)
  private[spoonbill] def retainedDescriptions: Int =
    lock.synchronized(descriptions.size + staged.fold(0)(_.size) + candidates.size)
}
