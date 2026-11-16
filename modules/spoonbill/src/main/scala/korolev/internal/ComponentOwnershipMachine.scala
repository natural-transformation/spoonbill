package spoonbill.internal

import avocet.Id

/**
 * Registry and per-owner decisions. Resource handles and failure causes stay in
 * the interpreter, under its existing ownership monitor.
 */
private[spoonbill] object ComponentOwnershipMachine {
  final class Version
  enum Admission { case Open, Closed }
  final case class Registry(
    version: Version,
    admission: Admission = Admission.Open,
    serial: Long = 0L,
    tails: Map[Id, Long] = Map.empty,
    live: Map[Long, Id] = Map.empty
  )
  enum Binding {
    case Closed, Exhausted
    case Assigned(incarnation: Long, previous: Option[Long])
  }
  def closed(registry: Registry): Boolean = registry.admission == Admission.Closed
  def close(registry: Registry): Registry = registry.copy(admission = Admission.Closed)
  def bind(registry: Registry, address: Id): (Registry, Binding) =
    if (closed(registry)) (registry, Binding.Closed)
    else if (registry.serial == Long.MaxValue) (registry, Binding.Exhausted)
    else {
      val incarnation = registry.serial + 1L
      val previous    = registry.tails.get(address)
      (
        registry.copy(
          serial = incarnation,
          tails = registry.tails.updated(address, incarnation),
          live = registry.live.updated(incarnation, address)
        ),
        Binding.Assigned(incarnation, previous)
      )
    }

  def retired(
    registry: Registry,
    address: Id,
    incarnation: Long,
    successful: Boolean,
    deleted: Boolean,
    nextVersion: Version
  ): Registry =
    if (!successful || !registry.live.get(incarnation).contains(address)) registry
    else
      registry.copy(
        version = if (deleted) nextVersion else registry.version,
        tails = if (registry.tails.get(address).contains(incarnation)) registry.tails - address else registry.tails,
        live = registry.live - incarnation
      )

  enum Predecessor { case Succeeded, Failed }
  enum Phase {
    case Gated, Open, Blocked, RetiredBeforePredecessor
    case Draining(predecessor: Predecessor)
    case Cleaning(predecessor: Predecessor)
    case Cleaned, CleanupFailed
  }
  enum BaselineState {
    def revision: Long
    case Unestablished(revision: Long)
    case Established(revision: Long)
  }
  final case class OwnerState(
    phase: Phase = Phase.Gated,
    admitted: Int = 0,
    baseline: BaselineState = BaselineState.Unestablished(0L)
  )
  final case class Baseline(incarnation: Long, revision: Long)
  enum Cleanup { case PropagateFailure, DeleteNode, SkipDelete }

  def active(state: OwnerState): Boolean = state.phase match {
    case Phase.Gated | Phase.Open | Phase.Blocked => true
    case _                                        => false
  }
  def open(state: OwnerState): Boolean                              = state.phase == Phase.Open
  def canAdmit(state: OwnerState, registryClosed: Boolean): Boolean = !registryClosed && open(state)
  def admitted(state: OwnerState): OwnerState =
    if (open(state)) state.copy(admitted = state.admitted + 1) else state
  def released(state: OwnerState): OwnerState =
    if (state.admitted > 0) state.copy(admitted = state.admitted - 1) else state

  def predecessorCompleted(state: OwnerState, successful: Boolean, reused: Boolean): OwnerState = {
    val predecessor = if (successful) Predecessor.Succeeded else Predecessor.Failed
    val phase = state.phase match {
      case Phase.Gated | Phase.Open | Phase.Blocked           => if (successful) Phase.Open else Phase.Blocked
      case Phase.RetiredBeforePredecessor | Phase.Draining(_) => Phase.Draining(predecessor)
      case Phase.Cleaning(_)                                  => Phase.Cleaning(predecessor)
      case other                                              => other
    }
    val baseline = if (successful && reused) state.baseline match {
      case BaselineState.Unestablished(_) => BaselineState.Unestablished(1L)
      case BaselineState.Established(_)   => BaselineState.Established(1L)
    }
    else state.baseline
    state.copy(phase = phase, baseline = baseline)
  }

  def retire(state: OwnerState): OwnerState = {
    val phase = state.phase match {
      case Phase.Gated   => Phase.RetiredBeforePredecessor
      case Phase.Open    => Phase.Draining(Predecessor.Succeeded)
      case Phase.Blocked => Phase.Draining(Predecessor.Failed)
      case other         => other
    }
    state.copy(phase = phase)
  }

  def beginCleanup(state: OwnerState): (OwnerState, Boolean) = state.phase match {
    case Phase.Draining(predecessor) if state.admitted == 0 => (state.copy(phase = Phase.Cleaning(predecessor)), true)
    case _                                                  => (state, false)
  }
  def cleanup(state: OwnerState, registryClosed: Boolean, deleteOnRetire: Boolean): Cleanup = state.phase match {
    case Phase.Cleaning(Predecessor.Failed)                                         => Cleanup.PropagateFailure
    case Phase.Cleaning(Predecessor.Succeeded) if !registryClosed && deleteOnRetire => Cleanup.DeleteNode
    case _                                                                          => Cleanup.SkipDelete
  }
  def completed(state: OwnerState, successful: Boolean): OwnerState = state.phase match {
    case Phase.Cleaning(_) => state.copy(phase = if (successful) Phase.Cleaned else Phase.CleanupFailed)
    case _                 => state
  }

  /**
   * Overflow retires admission before the interpreter raises the original
   * arithmetic failure.
   */
  def establishBaseline(state: OwnerState): (OwnerState, Boolean) = state.baseline match {
    case BaselineState.Established(_)               => (state, false)
    case BaselineState.Unestablished(Long.MaxValue) => (retire(state), true)
    case BaselineState.Unestablished(revision) =>
      (state.copy(baseline = BaselineState.Established(revision + 1L)), false)
  }

  def acceptsSnapshot(
    state: OwnerState,
    incarnation: Long,
    registryClosed: Boolean,
    reused: Boolean,
    captured: Option[Baseline],
    sameVersion: Boolean
  ): Boolean =
    canAdmit(state, registryClosed) && (captured match {
      case Some(value) => value.incarnation == incarnation && value.revision == state.baseline.revision
      case None        => !reused && state.baseline.revision == 0L && sameVersion
    })
}
