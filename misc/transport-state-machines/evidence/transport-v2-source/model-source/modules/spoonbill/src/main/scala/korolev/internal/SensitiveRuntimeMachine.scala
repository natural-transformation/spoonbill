package spoonbill.internal

import java.time.Instant
import scala.concurrent.duration.*
import spoonbill.sensitive.*

/**
 * Sensitive delivery decisions contain metadata only. Plaintext, continuations
 * and cancellation handles belong to the interpreter's single held record.
 */
private[internal] object SensitiveRuntimeMachine {
  val acknowledgementWindow: FiniteDuration = 5.seconds
  enum Waiter { case Pending, Completed }
  final case class Entry(presentation: SensitivePresentation, waiter: Waiter = Waiter.Pending)
  enum Emission        { case Ignore, TooLate; case Emit(next: Entry)  }
  enum Acknowledgement { case Ignore, Retire; case Accept(next: Entry) }

  def acknowledgementDelay(lifetime: FiniteDuration): FiniteDuration = lifetime.min(acknowledgementWindow)
  private def beforeAcknowledgementDeadline(value: SensitivePresentation, now: Instant): Boolean =
    now.isBefore(value.issuedAt.plusNanos(acknowledgementWindow.toNanos))

  def emit(state: Entry, now: Instant): Emission = {
    val current = state.presentation
    if (
      current.phase == PresentationPhase.Prepared &&
      (!beforeAcknowledgementDeadline(current, now) || !now.isBefore(current.expiresAt))
    ) Emission.TooLate
    else
      current.emitted(current.binding, now) match {
        case Left(_)     => Emission.Ignore
        case Right(next) => Emission.Emit(state.copy(presentation = next))
      }
  }

  def acknowledge(state: Entry, processed: Boolean, now: Instant): Acknowledgement = {
    val current = state.presentation
    val timely = now.isBefore(current.expiresAt) &&
      (current.phase == PresentationPhase.BrowserAcknowledged || beforeAcknowledgementDeadline(current, now))
    if (!processed || !timely) Acknowledgement.Retire
    else
      current.acknowledge(current.binding, now) match {
        case Left(_)     => Acknowledgement.Ignore
        case Right(next) => Acknowledgement.Accept(state.copy(presentation = next))
      }
  }

  def clear(state: Entry, reason: ClearReason): Entry = state.copy(presentation = state.presentation.clear(reason))
  def finish(state: Entry): Entry                     = state.copy(waiter = Waiter.Completed)

  final case class Registry(
    pending: PendingRegistryMachine.State[PresentationId],
    regions: Map[RegionId, PresentationId]
  )
  val initial: Registry = Registry(PendingRegistryMachine.initial[PresentationId], Map.empty)

  def register(state: Registry, binding: PresentationBinding, token: PendingRegistryMachine.Token): Option[Registry] =
    if (state.regions.contains(binding.regionId)) None
    else {
      val (next, admission) = PendingRegistryMachine.register(
        state.pending,
        binding.presentationId,
        token,
        4,
        PendingRegistryMachine.DuplicatePolicy.Replace
      )
      Option.when(admission == PendingRegistryMachine.Admission.Accepted)(
        Registry(next, state.regions.updated(binding.regionId, binding.presentationId))
      )
    }

  def remove(state: Registry, id: PresentationId): Registry = {
    val (next, token) = PendingRegistryMachine.take(state.pending, id, None)
    if (token.isEmpty) state else Registry(next, state.regions.filterNot(_._2 == id))
  }

  def close(state: Registry): Registry = state.copy(pending = PendingRegistryMachine.seal(state.pending))
}
