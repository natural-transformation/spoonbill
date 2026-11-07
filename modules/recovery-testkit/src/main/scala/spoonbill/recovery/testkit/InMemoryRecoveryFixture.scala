package spoonbill.recovery.testkit

import java.security.SecureRandom
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.recovery.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.BrowserSessionToken
import spoonbill.snapshot.{SnapshotFormat, ViewSnapshotStore}

object InMemoryRecoveryFixture {

  /** Synthetic host sessions; revocation happens through the browser provider. */
  final case class HostState(sessions: Map[UUID, HostPrincipal[String]] = Map.empty)
  val start: Instant = Instant.parse("2026-11-07T00:00:00Z")
}

/**
 * The modeled law host: InMemoryBrowserPersistence with its in-memory
 * publication capability, the given payload adapter and gated bindings. It
 * establishes the protocol laws deterministically; it is not durability,
 * shared-view or two-process evidence. Adapter authors run RecoveryLaws with
 * this fixture and their own SnapshotPayloadStore.
 */
final class InMemoryRecoveryFixture[F[_], D](
  format: SnapshotFormat[D],
  val payloads: SnapshotPayloadStore[F],
  val referenceSettings: SnapshotReferenceSettings = SnapshotReferenceSettings(),
  bindingSettings: PayloadRecoverySettings = PayloadRecoverySettings(),
  retention: BrowserRetentionPolicy = BrowserRetentionPolicy()
)(using F: Effect[F])
    extends RecoveryFixture[F, D] {
  import InMemoryRecoveryFixture.*

  final class View private[InMemoryRecoveryFixture] (
    private[InMemoryRecoveryFixture] val binding: String,
    private[InMemoryRecoveryFixture] val token: String,
    val view: BrowserView[String]
  )

  val clock = new AtomicReference(start)
  private val random = new SecureRandom()
  private val host = new InMemoryBrowserPersistence.Host[HostState, String] {
    def resolve(state: HostState, id: UUID, now: Instant): Option[HostPrincipal[String]] = state.sessions.get(id)
  }
  val persistence = new InMemoryBrowserPersistence[F, HostState, String](
    HostState(),
    host,
    () => clock.get(),
    () => UUID.randomUUID(),
    BrowserPersistenceLimits(retention = retention)
  )
  val references = new InMemorySnapshotReferences[F, HostState, String](persistence, referenceSettings)
  val harness    = new RecoveryHarness[F, String, D](references, payloads, format, bindingSettings)

  def payloadBasis: CleanupBasis = payloads.cleanupBasis

  private def required[E, A](value: F[Either[E, A]]): F[A] = value.flatMap {
    case Right(result) => F.pure(result)
    case Left(error)   => F.fail(new IllegalStateException(s"Fixture operation rejected: $error"))
  }
  private def secret(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
  private def connection(): ConnectionId = ConnectionId.fromUuid(UUID.randomUUID())

  private def stage(tx: InMemoryBrowserPersistence.Transaction[HostState]): HostSessionPrepared = {
    val id        = UUID.randomUUID()
    val deadline  = clock.get().plusSeconds(86000)
    val principal = HostPrincipal("name", "subject", 1L, 1L, deadline)
    tx.modify(state => state.copy(sessions = state.sessions.updated(id, principal)))
    new HostSessionPrepared(id, UUID.randomUUID(), deadline, BrowserSessionToken.fromString(secret()).toOption.get)
  }

  /** Anonymous attachment, sign-in, delivery and activation on the given browser and view. */
  private def login(binding: String, qsid: Qsid): F[View] =
    for {
      identity  <- required(persistence.capture(binding, None, activate = false))
      anonymous <- required(persistence.claim(identity, qsid, connection(), existingOnly = false))
      ceremony  <- required(persistence.begin(anonymous, "login"))
      receipt   <- required(persistence.prepare(ceremony)(stage))
      token     <- persistence.deliver(receipt.completionId, binding).map(_.getOrElse(throw new IllegalStateException("No cookie")))
      active    <- required(persistence.capture(binding, Some(token.value), activate = true))
      view      <- required(persistence.claim(active, qsid, connection(), existingOnly = true))
    } yield new View(binding, token.value, view)

  def open(): F[View] = login(secret(), Qsid("device", UUID.randomUUID().toString))

  def takeOver(previous: View): F[View] =
    for {
      active <- required(persistence.capture(previous.binding, Some(previous.token), activate = true))
      view   <- required(persistence.claim(active, previous.view.qsid, connection(), existingOnly = true))
    } yield new View(previous.binding, previous.token, view)

  def revoke(view: View): F[Unit] = required(persistence.logout(view.binding, Some(view.token))).map(_ => ())

  def rotateIdentity(view: View): F[View] = revoke(view).flatMap(_ => login(view.binding, view.view.qsid))

  def advanceClock(by: FiniteDuration): F[Unit] = F.delay { clock.updateAndGet(_.plusMillis(by.toMillis)); () }

  def reclaimView(view: View): F[Unit] =
    persistence
      .release(view.view)
      .flatMap(_ => advanceClock((retention.idleViewSeconds + 1).seconds))
      .flatMap(_ => persistence.maintenance())

  def store(view: View): ViewSnapshotStore[F, D] =
    harness.store(view.view).getOrElse(throw new IllegalStateException("The fixture view is not authenticated"))
  def rebind(view: View, writer: SnapshotFormat[D]): ViewSnapshotStore[F, D] =
    harness.store(view.view, writer).getOrElse(throw new IllegalStateException("The fixture view is not authenticated"))
  def gate(point: RecoveryFault): F[RecoveryGate[F]] = F.delay(harness.gates.arm(point))
  def maintenance(worker: Int): F[Unit]             = harness.binding(worker).maintenance()
  def statistics(worker: Int): RecoveryStatistics   = harness.binding(worker).statistics

  def dropPayload(view: View): F[Unit] = references.inventory(view.view.qsid).flatMap { inventory =>
    inventory.current.fold(F.unit)(ref => payloads.delete(ref).map(_ => ()))
  }

  def observe(view: View): F[RecoveryObservation] = references.inventory(view.view.qsid).flatMap { inventory =>
    val present = inventory.current.fold(F.pure(Option.empty[Boolean])) { ref =>
      payloads.get(ref).map {
        case PayloadRead.Found(_) => Some(true)
        case _                    => Some(false)
      }
    }
    present.map { payloadPresent =>
      RecoveryObservation(
        inventory.revision,
        inventory.published,
        inventory.staged,
        inventory.retired,
        inventory.deleted,
        inventory.openPins,
        inventory.openObligations,
        inventory.uncertainObligations,
        payloadPresent,
        harness.operations
      )
    }
  }

  def close(): F[Unit] =
    harness.binding(0).close().flatMap(_ => harness.binding(1).close()).flatMap(_ => persistence.close())
}
