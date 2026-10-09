package spoonbill.internal

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.*
import spoonbill.server.SessionAccessDenied

private[spoonbill] final case class SensitiveAuthorization[F[_]](audience: Audience, revalidate: () => F[Unit])

/** One connection's bounded disclosure resources. The ordinary outgoing queue
  * contains IDs only. Plaintext is detached on first pull, never cached for retry,
  * and no payload is retained while awaiting browser acknowledgement.
  */
private[spoonbill] final class SensitiveRuntime[F[_]: Effect](
  connection: ConnectionId,
  deadlines: Frontend.RpcDeadlineScheduler,
  sendClear: (PresentationId, RegionId) => F[Unit],
  now: () => Instant = () => Instant.now()
) {
  private final class Entry(val model: AtomicReference[SensitivePresentation], payload: SensitivePayload,
    val permit: SensitiveAuthorization[F], callback: Effect.Promise[DisclosureOutcome]) {
    val plaintext = new AtomicReference(Option(payload))
    val waiting = new AtomicReference(Option(callback))
    val cancellations = new AtomicReference(List.empty[() => Unit])
    def finish(outcome: DisclosureOutcome): Unit = waiting.getAndSet(None).foreach(_(Right(outcome)))
  }
  private val entries = mutable.Map.empty[PresentationId, Entry]
  private final case class Retirement(entry: Entry, region: RegionId, outcome: DisclosureOutcome)
  private var closed = false // ALLOW-VAR: guarded by entries monitor.
  private def launch(operation: F[Unit]): Unit = Effect[F].runAsync(operation)(_ => ())

  private def install(entry: Entry, cancel: () => Unit): Unit = entries.synchronized {
    if (entries.get(entry.model.get().binding.presentationId).contains(entry)) entry.cancellations.updateAndGet(cancel :: _)
    else cancel()
    ()
  }

  def present(region: RegionId, purpose: Purpose, payload: SensitivePayload, lifetime: FiniteDuration,
    permit: SensitiveAuthorization[F])(enqueue: PresentationId => F[Unit]): F[DisclosureOutcome] =
    clearRegion(region, ClearReason.Replaced).flatMap { _ => Effect[F].promise[DisclosureOutcome] { callback =>
      val issued = now()
      if (lifetime.toMillis <= 0L || lifetime > 5.minutes) throw new IllegalArgumentException("Invalid sensitive lifetime")
      val binding = PresentationBinding(connection, region, PresentationId.fromUuid(UUID.randomUUID()), permit.audience)
      val model = SensitivePresentation.prepare(binding, purpose, issued, issued.plusMillis(lifetime.toMillis))
        .fold(_ => throw new IllegalArgumentException("Invalid sensitive lifetime"), identity)
      val entry = new Entry(new AtomicReference(model), payload, permit, callback)
      entries.synchronized {
        if (closed || entries.size >= 4 || entries.values.exists(_.model.get().binding.regionId == region))
          throw new SessionAccessDenied
        entries.put(binding.presentationId, entry)
      }
      try {
      install(entry, deadlines.schedule(lifetime)(() => launch(clear(binding.presentationId, ClearReason.Expired))))
      install(entry, deadlines.schedule(lifetime.min(5.seconds))(() => {
        if (entry.waiting.get().nonEmpty) launch(clear(binding.presentationId, ClearReason.DeliveryFailed))
      }))
      def audit(): Unit = {
        if (pending(binding.presentationId)) Effect[F].runAsync(Effect[F].delayAsync(permit.revalidate())) {
          case Left(_) => launch(clear(binding.presentationId, ClearReason.Revoked))
          case Right(_) =>
            if (pending(binding.presentationId)) {
              try install(entry, deadlines.schedule(1.second)(() => audit()))
              catch { case NonFatal(_) => launch(clear(binding.presentationId, ClearReason.DeliveryFailed)) }
            }
        }
      }
      install(entry, deadlines.schedule(1.second)(() => audit()))
      Effect[F].runAsync(Effect[F].delayAsync(enqueue(binding.presentationId))) {
        case Left(_) => launch(clear(binding.presentationId, ClearReason.DeliveryFailed))
        case Right(_) => ()
      }
      } catch { case NonFatal(_) => launch(clear(binding.presentationId, ClearReason.DeliveryFailed)) }
    }}

  def pending(id: PresentationId): Boolean = entries.synchronized(entries.contains(id))
  private[spoonbill] def retainedPayloads: Int = entries.synchronized(entries.values.count(_.plaintext.get().nonEmpty))

  def revalidateActive(): F[Unit] = entries.synchronized(entries.toList).map { case (id, entry) =>
    Effect[F].delayAsync(entry.permit.revalidate()).recoverF { case _ => clear(id, ClearReason.Revoked) }
  }.sequence.unit

  def pull(id: PresentationId): F[Option[String]] = {
    val selected = entries.synchronized(entries.get(id))
    selected match {
      case None => Effect[F].pure(None)
      case Some(entry) => Effect[F].delayAsync(entry.permit.revalidate()).flatMap { _ => Effect[F].delay {
        entries.synchronized {
          if (!entries.get(id).contains(entry)) None
          else {
            val current = entry.model.get()
            val observed = now()
            if (current.phase == PresentationPhase.Prepared &&
                (!observed.isBefore(current.issuedAt.plusSeconds(5)) || !observed.isBefore(current.expiresAt)))
              throw new java.util.concurrent.TimeoutException("Sensitive delivery deadline exceeded")
            current.emitted(current.binding, observed) match {
              case Left(_) => None
              case Right(emitted) =>
                entry.model.set(emitted)
                entry.plaintext.getAndSet(None).map(value => encode(emitted, value))
            }
          }
        }
      }}.recoverF {
        case _: java.util.concurrent.TimeoutException => clear(id, ClearReason.DeliveryFailed).as(None)
        case _ => clear(id, ClearReason.Revoked).as(None)
      }
    }
  }

  def acknowledge(id: PresentationId, region: RegionId, processed: Boolean): F[Unit] = Effect[F].delay {
    entries.synchronized {
      entries.get(id).filter(_.model.get().binding.regionId == region).flatMap { entry =>
        val current = entry.model.get()
        val observed = now()
        val timely = observed.isBefore(current.expiresAt) &&
          (current.phase == PresentationPhase.BrowserAcknowledged || observed.isBefore(current.issuedAt.plusSeconds(5)))
        if (processed && timely) current.acknowledge(current.binding, observed).toOption.map { next =>
          entry.model.set(next)
          true -> entry
        } else Some(false -> entry)
      }
    }
  }.flatMap {
    // Effect implementations may run continuations inline. No application code
    // or promise completion may execute while the presentation monitor is held.
    case Some((true, entry)) => Effect[F].delay(entry.finish(DisclosureOutcome.BrowserProcessed))
    case Some((false, _)) => clear(id, ClearReason.DeliveryFailed)
    case _ => Effect[F].unit
  }

  def browserCleared(id: PresentationId, region: RegionId): F[Unit] =
    if (entries.synchronized(entries.get(id).exists(_.model.get().binding.regionId == region)))
      clear(id, ClearReason.Requested, notifyBrowser = false)
    else Effect[F].unit

  def clearRegion(region: RegionId, reason: ClearReason = ClearReason.Requested): F[Unit] = {
    val ids = entries.synchronized(entries.values.filter(_.model.get().binding.regionId == region)
      .map(_.model.get().binding.presentationId).toList)
    ids.map(clear(_, reason)).sequence.unit
  }

  def clearAll(reason: ClearReason, notifyBrowser: Boolean = true): F[Unit] =
    entries.synchronized(entries.keys.toList).map(clear(_, reason, notifyBrowser)).sequence.unit

  def close(): F[Unit] = Effect[F].delay { entries.synchronized { closed = true } }
    .flatMap(_ => clearAll(ClearReason.Disconnected, notifyBrowser = false))

  private def clear(id: PresentationId, reason: ClearReason, notifyBrowser: Boolean = true): F[Unit] =
    Effect[F].delay {
      entries.synchronized(entries.remove(id)).map { entry =>
        val ended = entry.model.get().clear(reason)
        entry.model.set(ended)
        entry.plaintext.set(None)
        entry.cancellations.getAndSet(Nil).foreach(cancel => try cancel() catch { case NonFatal(_) => () })
        val outcome = ended.phase match {
          case PresentationPhase.Closed(_, value) => value
          case _ => throw new IllegalStateException("Sensitive retirement is not terminal")
        }
        Retirement(entry, ended.binding.regionId, outcome)
      }
    }.flatMap {
      case Some(retired) =>
        // Queue the mandatory clear before completing an effect whose user
        // continuation may run inline. Recursive close on enqueue failure sees
        // this entry already retired; settle it even when notification fails.
        val notify = if (notifyBrowser) Effect[F].delayAsync(sendClear(id, retired.region)) else Effect[F].unit
        notify.map(_ => Option.empty[Throwable]).recover { case error => Some(error) }.flatMap { error =>
          Effect[F].delay(retired.entry.finish(retired.outcome)).flatMap { _ =>
            error.fold(Effect[F].unit)(Effect[F].fail[Unit])
          }
        }
      case None => Effect[F].unit
    }

  private def encode(model: SensitivePresentation, payload: SensitivePayload): String = {
    def quote(value: String): String = {
      val builder = new mutable.StringBuilder("\"")
      jsonEscape(builder, value, unicode = true)
      builder.append('"').result()
    }
    def strings(values: Vector[String]): String = values.map(quote).mkString("[", ",", "]")
    val body = payload match {
      case value: SensitivePayload.TextList => s"[0,${strings(value.items)}]"
      case value: SensitivePayload.TotpSetup => s"[1,${quote(value.uri)},${value.qr.fold("null")(qr => strings(qr.rows))}]"
    }
    val remaining = math.max(0L, java.time.Duration.between(now(), model.expiresAt).toMillis)
    s"[22,${quote(connection.toString)},${quote(model.binding.presentationId.value.toString)},${quote(model.binding.regionId.value)},${quote(model.purpose.value)},$remaining,$body,${model.expiresAt.toEpochMilli}]"
  }
}
