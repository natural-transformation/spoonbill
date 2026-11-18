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
  def closed(registry: Registry): Boolean = registry.admission eq Admission.Closed
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

  /**
   * One allocation-free value per owner. The low 32 bits preserve the original
   * signed Int admission counter, including wrapping arithmetic. Phase uses
   * four bits and the reachable baseline states use two.
   *
   * Baseline revisions are structurally bounded: initially zero; establish
   * increments once; a successful reused predecessor sets one. The established
   * flag survives that assignment. Thus every reachable revision is 0, 1 or 2.
   */
  opaque type OwnerState = Long
  object OwnerState {
    def apply(): OwnerState = 0L
  }
  final case class Baseline(incarnation: Long, revision: Long)
  enum Cleanup { case PropagateFailure, DeleteNode, SkipDelete }

  private final val CountMask           = 0xffffffffL
  private final val PhaseShift          = 32
  private final val PhaseMask           = 15L << PhaseShift
  private final val BaselineShift       = 36
  private final val BaselineMask        = 3L << BaselineShift
  private final val GatedCode           = 0
  private final val OpenCode            = 1
  private final val BlockedCode         = 2
  private final val RetiredBeforeCode   = 3
  private final val DrainingSuccessCode = 4
  private final val DrainingFailureCode = 5
  private final val CleaningSuccessCode = 6
  private final val CleaningFailureCode = 7
  private final val CleanedCode         = 8
  private final val CleanupFailedCode   = 9

  private def phaseCode(state: OwnerState): Int    = ((state >>> PhaseShift) & 15L).toInt
  private def baselineCode(state: OwnerState): Int = ((state >>> BaselineShift) & 3L).toInt
  private def withPhase(state: OwnerState, phase: Int): OwnerState =
    (state & ~PhaseMask) | (phase.toLong << PhaseShift)
  private def withBaseline(state: OwnerState, baseline: Int): OwnerState =
    (state & ~BaselineMask) | (baseline.toLong << BaselineShift)

  /** Also exposes the complete Int domain to representation-law tests. */
  private[internal] def withAdmittedCount(state: OwnerState, count: Int): OwnerState =
    (state & ~CountMask) | (count.toLong & CountMask)
  def admittedCount(state: OwnerState): Int = state.toInt

  // Diagnostic views are cached; hot getters never reconstruct enum wrappers.
  private val phases = Vector(
    Phase.Gated,
    Phase.Open,
    Phase.Blocked,
    Phase.RetiredBeforePredecessor,
    Phase.Draining(Predecessor.Succeeded),
    Phase.Draining(Predecessor.Failed),
    Phase.Cleaning(Predecessor.Succeeded),
    Phase.Cleaning(Predecessor.Failed),
    Phase.Cleaned,
    Phase.CleanupFailed
  )
  private val baselines = Vector(
    BaselineState.Unestablished(0L),
    BaselineState.Unestablished(1L),
    BaselineState.Established(1L),
    BaselineState.Established(2L)
  )
  def phase(state: OwnerState): Phase = {
    val code = phaseCode(state)
    if (code < phases.size) phases(code) else Phase.CleanupFailed
  }
  def baselineState(state: OwnerState): BaselineState = baselines(baselineCode(state))
  def baselineRevision(state: OwnerState): Long = baselineCode(state) match {
    case 0 => 0L
    case 3 => 2L
    case _ => 1L
  }

  def active(state: OwnerState): Boolean = phaseCode(state) match {
    case GatedCode | OpenCode | BlockedCode => true
    case _                                  => false
  }
  def open(state: OwnerState): Boolean = (state & PhaseMask) == (OpenCode.toLong << PhaseShift)
  def canAdmit(state: OwnerState, registryClosed: Boolean): Boolean =
    !registryClosed && (state & PhaseMask) == (OpenCode.toLong << PhaseShift)
  def admitted(state: OwnerState): OwnerState =
    if ((state & PhaseMask) != (OpenCode.toLong << PhaseShift)) state
    else if (state.toInt == -1) state & ~CountMask
    else state + 1L

  /**
   * One shallow hot transition; a changed value is the successful admission.
   */
  def reserve(state: OwnerState, registryClosed: Boolean): OwnerState =
    if (registryClosed || (state & PhaseMask) != (OpenCode.toLong << PhaseShift)) state
    else if (state.toInt == -1) state & ~CountMask
    else state + 1L

  /**
   * The interpreter's one-use lease authorizes release, even across Int wrap.
   */
  def released(state: OwnerState): OwnerState =
    if (state.toInt == 0) state | CountMask else state - 1L

  def predecessorCompleted(state: OwnerState, successful: Boolean, reused: Boolean): OwnerState = {
    val nextPhase = phaseCode(state) match {
      case GatedCode | OpenCode | BlockedCode => if (successful) OpenCode else BlockedCode
      case RetiredBeforeCode | DrainingSuccessCode | DrainingFailureCode =>
        if (successful) DrainingSuccessCode else DrainingFailureCode
      case CleaningSuccessCode | CleaningFailureCode => if (successful) CleaningSuccessCode else CleaningFailureCode
      case other                                     => other
    }
    val next = withPhase(state, nextPhase)
    if (successful && reused) withBaseline(next, if (baselineCode(state) >= 2) 2 else 1) else next
  }

  def retire(state: OwnerState): OwnerState = withPhase(
    state,
    phaseCode(state) match {
      case GatedCode   => RetiredBeforeCode
      case OpenCode    => DrainingSuccessCode
      case BlockedCode => DrainingFailureCode
      case other       => other
    }
  )

  /** Compact hot-path result: unchanged means cleanup was not claimed. */
  def claimCleanup(state: OwnerState): OwnerState = {
    val phase = state & PhaseMask
    if (phase == (DrainingSuccessCode.toLong << PhaseShift) && state.toInt == 0)
      (state & ~PhaseMask) | (CleaningSuccessCode.toLong << PhaseShift)
    else if (phase == (DrainingFailureCode.toLong << PhaseShift) && state.toInt == 0)
      (state & ~PhaseMask) | (CleaningFailureCode.toLong << PhaseShift)
    else state
  }
  def changed(before: OwnerState, after: OwnerState): Boolean = before != after
  def beginCleanup(state: OwnerState): (OwnerState, Boolean) = {
    val next = claimCleanup(state)
    (next, changed(state, next))
  }
  def cleanup(state: OwnerState, registryClosed: Boolean, deleteOnRetire: Boolean): Cleanup = phaseCode(state) match {
    case CleaningFailureCode                                      => Cleanup.PropagateFailure
    case CleaningSuccessCode if !registryClosed && deleteOnRetire => Cleanup.DeleteNode
    case _                                                        => Cleanup.SkipDelete
  }
  def completed(state: OwnerState, successful: Boolean): OwnerState = phaseCode(state) match {
    case CleaningSuccessCode | CleaningFailureCode =>
      withPhase(state, if (successful) CleanedCode else CleanupFailedCode)
    case _ => state
  }

  def establishBaseline(state: OwnerState): OwnerState = baselineCode(state) match {
    case 0 => withBaseline(state, 2)
    case 1 => withBaseline(state, 3)
    case _ => state
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
      case Some(value) => value.incarnation == incarnation && value.revision == baselineRevision(state)
      case None        => !reused && baselineRevision(state) == 0L && sameVersion
    })
}
