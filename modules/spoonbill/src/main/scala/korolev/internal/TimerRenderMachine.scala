package spoonbill.internal

import TimerMachine.{Command, Event, Identity, Owner, State as Timers}

/** Render publication/startup metadata. Reducers, targets and timer handles stay outside. */
private[spoonbill] object TimerRenderMachine {
  enum Gate { case Dormant, Running }
  enum Fence {
    case Unprepared
    case Prepared(retiring: Set[Identity], wakes: Map[Identity, Event.Wake])
  }
  enum Declaration { case Missing, Valid, Omitted }
  enum Collection { case Accept, Invalid, Conflict, Omit }
  enum Offer { case Pending, Offered, Detached }
  final case class Issues(invalid: Int = 0, duplicate: Int = 0, overflow: Int = 0, samples: Vector[String] = Vector.empty)
  final case class State(
    gate: Gate,
    fence: Fence,
    pending: Vector[Command],
    reports: Vector[String],
    issues: Map[Owner, Issues],
    suppression: Map[Owner, (Issues, Long)]
  )
  private val preparedEmpty: Fence = Fence.Prepared(Set.empty, Map.empty)
  val initial: State = State(Gate.Dormant, Fence.Unprepared, Vector.empty, Vector.empty, Map.empty, Map.empty)
  private val running = initial.copy(gate = Gate.Running)
  private val dormantPrepared = initial.copy(fence = preparedEmpty)
  private val runningPrepared = running.copy(fence = preparedEmpty)

  private def normalized(gate: Gate, fence: Fence, pending: Vector[Command], reports: Vector[String],
    issues: Map[Owner, Issues], suppression: Map[Owner, (Issues, Long)]): State =
    if (pending.isEmpty && reports.isEmpty && issues.isEmpty && suppression.isEmpty) {
      if (fence == Fence.Unprepared) { if (gate == Gate.Running) running else initial }
      else if (fence eq preparedEmpty) { if (gate == Gate.Running) runningPrepared else dormantPrepared }
      else State(gate, fence, pending, reports, issues, suppression)
    } else State(gate, fence, pending, reports, issues, suppression)

  private def prepared(retiring: Set[Identity], wakes: Map[Identity, Event.Wake]): Fence =
    if (retiring.isEmpty && wakes.isEmpty) preparedEmpty else Fence.Prepared(retiring, wakes)

  def canBegin(state: State): Boolean = state.fence == Fence.Unprepared
  def begun(state: State): State =
    if (state.issues.isEmpty) state
    else normalized(state.gate, state.fence, state.pending, state.reports, Map.empty, state.suppression)

  def collection(previous: Declaration, valid: Boolean): Collection = previous match {
    case Declaration.Valid => Collection.Conflict
    case Declaration.Omitted => Collection.Omit
    case Declaration.Missing => if (valid) Collection.Accept else Collection.Invalid
  }
  private def withIssue(state: State, owner: Owner, issue: Issues): State =
    normalized(state.gate, state.fence, state.pending, state.reports, state.issues.updated(owner, issue), state.suppression)
  def invalid(state: State, owner: Owner): State = {
    val issue = state.issues.getOrElse(owner, Issues())
    withIssue(state, owner, issue.copy(invalid = issue.invalid + 1))
  }
  def conflict(state: State, owner: Owner, sample: String): State = {
    val issue = state.issues.getOrElse(owner, Issues())
    withIssue(state, owner, issue.copy(duplicate = issue.duplicate + 1, samples = (issue.samples :+ sample).take(4)))
  }
  def overflow(state: State, owner: Owner): State = {
    val issue = state.issues.getOrElse(owner, Issues())
    withIssue(state, owner, issue.copy(overflow = issue.overflow + 1))
  }
  def freshBudget(timers: Timers, present: Set[Identity], existing: Int, limit: Int): Int = {
    val retired = timers.slots.count { case (id, slot) => !present(id) && slot.entry.nonEmpty }
    math.max(0, limit - existing - retired)
  }

  def reserve(state: State, retiring: Set[Identity]): State = {
    val wakes = state.fence match {
      case Fence.Prepared(_, values) => values
      case _ => Map.empty[Identity, Event.Wake]
    }
    normalized(state.gate, prepared(retiring, wakes), state.pending, state.reports, state.issues, state.suppression)
  }
  def reserved(state: State, id: Identity): Boolean = state.fence match {
    case Fence.Prepared(retiring, _) => retiring(id)
    case _ => false
  }
  def defer(state: State, wake: Event.Wake): State = state.fence match {
    case Fence.Prepared(retiring, wakes) if retiring(wake.id) =>
      normalized(state.gate, prepared(retiring, wakes.updated(wake.id, wake)), state.pending, state.reports,
        state.issues, state.suppression)
    case _ => state
  }
  def deferred(state: State): Vector[Event.Wake] = state.fence match {
    case Fence.Prepared(_, wakes) => wakes.values.toVector
    case _ => Vector.empty
  }
  def finishedRender(state: State): State =
    normalized(state.gate, Fence.Unprepared, state.pending, state.reports, Map.empty, state.suppression)

  def accepted(state: State, timers: Timers, commands: Vector[Command]): State = {
    val pending = if (state.gate == Gate.Running) state.pending ++ commands
    else timers.slots.toVector.flatMap { case (id, slot) =>
      slot.arm.map(token => Command.Schedule(id, slot.generation, token, slot.deadline))
    }
    if (pending eq state.pending) state
    else normalized(state.gate, state.fence, pending, state.reports, state.issues, state.suppression)
  }
  def report(state: State, text: String): State =
    normalized(state.gate, state.fence, state.pending, state.reports :+ text, state.issues, state.suppression)
  def retainCurrentSuppression(state: State): State =
    if (state.suppression.isEmpty) state
    else normalized(state.gate, state.fence, state.pending, state.reports, state.issues,
      state.suppression.filter { case (owner, _) => state.issues.contains(owner) })
  def reportDue(state: State, owner: Owner, issue: Issues, now: Long): Boolean =
    state.suppression.get(owner).forall { case (previous, time) => previous != issue && now - time >= 1000000000L }
  def reported(state: State, owner: Owner, issue: Issues, now: Long, text: String): State =
    normalized(state.gate, state.fence, state.pending, state.reports :+ text, state.issues,
      state.suppression.updated(owner, issue -> now))

  def commandsToRun(state: State): Vector[Command] = if (state.gate == Gate.Running) state.pending else Vector.empty
  def flushed(state: State): State =
    if (state.reports.isEmpty && (state.gate == Gate.Dormant || state.pending.isEmpty)) state
    else normalized(state.gate, state.fence, if (state.gate == Gate.Running) Vector.empty else state.pending,
      Vector.empty, state.issues, state.suppression)
  def started(state: State): State =
    if (state.gate == Gate.Running) state
    else normalized(Gate.Running, state.fence, state.pending, state.reports, state.issues, state.suppression)
  def ownerClosed(state: State, owner: Owner): State = {
    val fence = state.fence match {
      case Fence.Prepared(retiring, wakes) => prepared(retiring.filterNot(_.owner == owner), wakes.filterNot(_._1.owner == owner))
      case other => other
    }
    normalized(state.gate, fence, state.pending, state.reports, state.issues, state.suppression - owner)
  }

  def offered(state: Offer): Offer = if (state == Offer.Detached) state else Offer.Offered
  def unoffered(state: Offer): Boolean = state == Offer.Pending
  def detached(state: Offer): Offer = Offer.Detached
}
