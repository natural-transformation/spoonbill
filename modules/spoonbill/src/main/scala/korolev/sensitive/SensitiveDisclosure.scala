package spoonbill.sensitive

import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.Effect

/** One-shot ownership transfer from an action result into the connection's
  * delivery resource. Cached action results retain this empty holder after
  * handoff/suppression, rather than retaining their original plaintext payload.
  * Caller-owned values and application callback captures remain host-owned.
  */
final class SensitiveDisclosure private (payload: SensitivePayload, owner: SensitiveDisclosure.Owner, released: () => Unit) {
  private val pending = new AtomicReference(Option(payload))
  private val releaseAction = new AtomicReference(Option(released))
  override def toString: String = "SensitiveDisclosure(<redacted>)"

  private[spoonbill] def hasPayload: Boolean = pending.get().nonEmpty
  private[spoonbill] def belongsTo(expected: SensitiveDisclosure.Owner): Boolean = owner eq expected
  private def release(): Unit = releaseAction.getAndSet(None).foreach(_.apply())
  private[spoonbill] def discard(): Unit = {
    if (pending.getAndSet(None).nonEmpty) release()
  }

  private[spoonbill] def consume[F[_]: Effect](expected: SensitiveDisclosure.Owner)(
    publish: SensitivePayload => F[DisclosureOutcome]
  ): F[DisclosureOutcome] = {
    val F = Effect[F]
    if (!belongsTo(expected)) F.fail(new spoonbill.server.SessionAccessDenied)
    else F.flatMap(F.delay {
      val value = pending.getAndSet(None)
      if (value.nonEmpty) release()
      value
    }) {
      case Some(value) => F.delayAsync(publish(value))
      case None => F.pure(DisclosureOutcome.NotSent)
    }
  }
}

object SensitiveDisclosure {
  /** An invocation identity without a reference to its connection or authority. */
  private[spoonbill] final class Owner
  private[spoonbill] def once(payload: SensitivePayload, owner: Owner, released: () => Unit = () => ()): SensitiveDisclosure =
    new SensitiveDisclosure(payload, owner, released)
}
