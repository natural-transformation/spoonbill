package spoonbill.auth

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.security.Identifiers.{ViewOwnerId, ViewSessionId}
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.security.ViewFence

class BrowserReplacementModelsSpec extends AnyFlatSpec with Matchers {
  private def uuid(value: Long): UUID = new UUID(0L, value)
  private val now                     = Instant.parse("2026-10-16T00:00:00Z")
  private val scope                   = BrowserProviderScope("realm", "browser")
  private val binding                 = Vector.fill[Byte](32)(1)
  private val oldCredential           = Vector.fill[Byte](32)(2)
  private val newCredential           = Vector.fill[Byte](32)(3)
  private val principal               = HostPrincipalStamp("subject", 7L, 2L, now.plusSeconds(600))
  private val fence = ViewFence(
    ViewSessionId.fromUuid(uuid(1)),
    ViewOwnerId.fromUuid(uuid(2)),
    ViewOwnershipEpoch.fromLong(1L).toOption.get
  )
  private val predecessor = BrowserReplacementPredecessor(
    scope,
    BrowserAuthorityStamp(binding, 3L, Some(uuid(3)), Some(principal)),
    oldCredential,
    fence
  )
  private val ceremony = BrowserCeremonyRecord(
    CeremonyId(uuid(4)),
    uuid(5),
    binding,
    3L,
    "regenerate-recovery",
    now.plusSeconds(120),
    Some(BrowserChallenge("subject", 11L, 2L, "proof")),
    BrowserCeremonyPhase.Admitted
  )
  private val attempt = BrowserReplacementAttempt(ceremony, predecessor)
  private val successor = BrowserReplacementSuccessor(
    uuid(6),
    newCredential,
    principal.copy(securityVersion = 8L, policyVersion = 3L),
    now.plusSeconds(300)
  )
  private val deliveryDeadline = now.plusSeconds(60)

  private def prepare(
    value: BrowserReplacementAttempt = attempt,
    next: BrowserReplacementSuccessor = successor,
    predecessorAuthorized: Boolean = false,
    deadline: Instant = deliveryDeadline,
    time: Instant = now
  ): Either[BrowserCeremonyError, BrowserReplacementReservation] =
    value.prepare(next, predecessorAuthorized, deadline, time)

  private def accepted[A](result: Either[BrowserCeremonyError, A]): A =
    result.fold(error => fail(s"Unexpected rejection: $error"), identity)

  private def recover(
    reservation: BrowserReplacementReservation,
    observedScope: BrowserProviderScope = scope,
    observedBinding: Vector[Byte] = binding,
    generation: Long = 3L,
    currentSession: Option[UUID] = Some(uuid(3)),
    pending: Option[CeremonyId] = Some(ceremony.id),
    host: Option[HostPrincipalStamp] = Some(successor.principal),
    credential: Option[Vector[Byte]] = None,
    material: Boolean = true,
    remaining: Int = 3,
    time: Instant = now
  ): Either[BrowserCeremonyError, BrowserReplacementAvailability] =
    reservation.recover(
      observedScope,
      observedBinding,
      generation,
      currentSession,
      pending,
      host,
      credential,
      material,
      remaining,
      time
    )

  "Replacement preparation decisions" should "retain exact attempt facts while permitting legitimate version advancement" in {
    val reservation = accepted(prepare())
    reservation.attempt.ceremony shouldBe ceremony.copy(phase = BrowserCeremonyPhase.Committed)
    reservation.attempt.predecessor shouldBe predecessor
    reservation.successor shouldBe successor
    reservation.expiresAt shouldBe deliveryDeadline
    attempt.ceremony.phase shouldBe BrowserCeremonyPhase.Admitted
    accepted(prepare(next = successor.copy(principal = principal))).successor.principal shouldBe principal
  }

  it should "reject invalid captured authority and a predecessor still authorized after mutation" in {
    val invalid = Vector(
      predecessor.copy(authority = predecessor.authority.copy(bindingKey = Vector.empty)),
      predecessor.copy(authority = predecessor.authority.copy(generation = -1L)),
      predecessor.copy(authority = predecessor.authority.copy(sessionId = None)),
      predecessor.copy(authority = predecessor.authority.copy(host = None)),
      predecessor.copy(authority = predecessor.authority.copy(host = Some(principal.copy(subject = "")))),
      predecessor.copy(authority = predecessor.authority.copy(host = Some(principal.copy(securityVersion = -1L)))),
      predecessor.copy(authority = predecessor.authority.copy(host = Some(principal.copy(policyVersion = -1L)))),
      predecessor.copy(authority = predecessor.authority.copy(host = Some(principal.copy(expiresAt = now)))),
      predecessor.copy(credentialHash = Vector.empty)
    )
    invalid.foreach { captured =>
      val value = attempt.copy(
        predecessor = captured,
        ceremony = ceremony.copy(challenge = None)
      )
      prepare(value = value) shouldBe Left(BrowserCeremonyError.Denied)
    }
    prepare(predecessorAuthorized = true) shouldBe Left(BrowserCeremonyError.Denied)
  }

  it should "reject mismatched ceremony facts and attempts that were not admitted" in {
    Vector(
      ceremony.copy(bindingKey = Vector.fill[Byte](32)(4)),
      ceremony.copy(generation = 4L),
      ceremony.copy(challenge = Some(BrowserChallenge("other", 11L, 2L, "proof"))),
      ceremony.copy(challenge = Some(BrowserChallenge("subject", 11L, 3L, "proof")))
    ).foreach(changed => prepare(value = attempt.copy(ceremony = changed)) shouldBe Left(BrowserCeremonyError.Denied))
    Vector(
      BrowserCeremonyPhase.Begun,
      BrowserCeremonyPhase.Challenged,
      BrowserCeremonyPhase.Committed,
      BrowserCeremonyPhase.NotCommitted
    ).foreach { phase =>
      prepare(value = attempt.copy(ceremony = ceremony.copy(phase = phase))) shouldBe Left(BrowserCeremonyError.Used)
    }
  }

  it should "reject account switching, reused credentials or sessions, backward versions and invalid successor lifetime" in {
    Vector(
      successor.copy(sessionId = uuid(3)),
      successor.copy(credentialHash = oldCredential),
      successor.copy(credentialHash = Vector.empty),
      successor.copy(principal = successor.principal.copy(subject = "other")),
      successor.copy(principal = successor.principal.copy(securityVersion = 6L)),
      successor.copy(principal = successor.principal.copy(policyVersion = 1L)),
      successor.copy(expiresAt = principal.expiresAt.plusSeconds(1))
    ).foreach(next => prepare(next = next) shouldBe Left(BrowserCeremonyError.Denied))
    prepare(next = successor.copy(expiresAt = now)) shouldBe Left(BrowserCeremonyError.Expired)
    prepare(time = ceremony.expiresAt) shouldBe Left(BrowserCeremonyError.Expired)
    prepare(deadline = now) shouldBe Left(BrowserCeremonyError.Expired)
    prepare(deadline = ceremony.expiresAt.plusSeconds(1)) shouldBe Left(BrowserCeremonyError.Expired)
    prepare(next = successor.copy(expiresAt = now.plusSeconds(30))) shouldBe Left(BrowserCeremonyError.Expired)
  }

  "Committed replacement recovery decisions" should "permit bounded delivery with missing or known predecessor credentials" in {
    val reservation = accepted(prepare())
    val delivered =
      Right(BrowserReplacementAvailability.DeliveryAvailable)
    recover(reservation) shouldBe delivered
    recover(reservation, credential = Some(oldCredential)) shouldBe delivered
    (1 to 5).foreach(_ => recover(reservation) shouldBe delivered)
    reservation.attempt.ceremony.expiresAt shouldBe ceremony.expiresAt
    reservation.expiresAt shouldBe deliveryDeadline
  }

  "Replacement slot decisions" should "reserve the predecessor and activate only its exact successor once" in {
    val reservation = accepted(prepare())
    val original    = BrowserReplacementSlot(3L, Some(uuid(3)))
    val pending     = accepted(original.reserve(reservation))
    pending.generation shouldBe original.generation
    pending.current shouldBe original.current
    pending.reserve(reservation) shouldBe Left(BrowserCeremonyError.Denied)
    original.activate(reservation, now) shouldBe Left(BrowserCeremonyError.Denied)
    pending.copy(current = Some(uuid(9))).activate(reservation, now) shouldBe Left(BrowserCeremonyError.Denied)
    pending.copy(generation = 4L).activate(reservation, now) shouldBe Left(BrowserCeremonyError.Denied)
    pending.activate(reservation, deliveryDeadline) shouldBe Left(BrowserCeremonyError.Expired)
    val activated = accepted(pending.activate(reservation, now))
    activated shouldBe BrowserReplacementSlot(4L, Some(successor.sessionId))
    activated.activate(reservation, now) shouldBe Left(BrowserCeremonyError.Denied)
    val loggedOut = accepted(pending.logout)
    loggedOut shouldBe BrowserReplacementSlot(4L, None)
    loggedOut.reserve(reservation) shouldBe Left(BrowserCeremonyError.Denied)
    BrowserReplacementSlot(Long.MaxValue, None).logout shouldBe Left(BrowserCeremonyError.Denied)
  }

  it should "reject a reservation whose generation cannot advance at activation" in {
    val exhausted   = predecessor.copy(authority = predecessor.authority.copy(generation = Long.MaxValue))
    val value       = attempt.copy(ceremony = ceremony.copy(generation = Long.MaxValue), predecessor = exhausted)
    val reservation = accepted(prepare(value = value))
    BrowserReplacementSlot(Long.MaxValue, exhausted.authority.sessionId).reserve(reservation) shouldBe
      Left(BrowserCeremonyError.Denied)
  }

  it should "allow an already-held valid successor after material removal or exhaustion until the fixed deadline" in {
    val reservation = accepted(prepare())
    val activation =
      Right(BrowserReplacementAvailability.ActivationAvailable)
    recover(reservation, credential = Some(newCredential), material = false, remaining = 0) shouldBe activation
    recover(
      reservation,
      credential = Some(newCredential),
      material = false,
      remaining = 0,
      time = deliveryDeadline.minusNanos(1)
    ) shouldBe activation
    recover(reservation, credential = Some(newCredential), time = deliveryDeadline) shouldBe
      Right(BrowserReplacementAvailability.Unavailable)
    recover(reservation, credential = Some(newCredential), time = ceremony.expiresAt) shouldBe
      Right(BrowserReplacementAvailability.Unavailable)
  }

  it should "preserve committed disposition after expiry, revocation, logout, reservation loss and exhausted delivery" in {
    val reservation = accepted(prepare())
    val unavailable = Right(BrowserReplacementAvailability.Unavailable)
    Vector(
      recover(reservation, material = false),
      recover(reservation, remaining = 0),
      recover(reservation, time = deliveryDeadline),
      recover(reservation, time = ceremony.expiresAt),
      recover(reservation, host = None),
      recover(reservation, host = Some(successor.principal.copy(securityVersion = 9L))),
      recover(reservation, generation = 4L),
      recover(reservation, currentSession = None),
      recover(reservation, pending = None),
      recover(reservation, pending = Some(CeremonyId(uuid(9))))
    ).foreach(_ shouldBe unavailable)
    reservation.attempt.ceremony.phase shouldBe BrowserCeremonyPhase.Committed
  }

  it should "deny foreign recovery and supplied unrelated or malformed credentials without a missing-cookie fallback" in {
    val reservation = accepted(prepare())
    recover(reservation, observedScope = scope.copy(cookieNamespace = "other")) shouldBe Left(
      BrowserCeremonyError.Denied
    )
    recover(reservation, observedBinding = Vector.fill[Byte](32)(4)) shouldBe Left(BrowserCeremonyError.Denied)
    Vector(Vector.fill[Byte](32)(4), Vector.empty[Byte], Vector.fill[Byte](31)(3)).foreach { hash =>
      recover(reservation, credential = Some(hash)) shouldBe Left(BrowserCeremonyError.Denied)
      recover(reservation, credential = Some(hash), material = false, remaining = 0) shouldBe Left(
        BrowserCeremonyError.Denied
      )
    }
  }
}
