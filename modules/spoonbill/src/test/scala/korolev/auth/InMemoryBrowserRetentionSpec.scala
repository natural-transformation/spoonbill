package spoonbill.auth

import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.BrowserSessionToken

class InMemoryBrowserRetentionSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global
  private val start = Instant.parse("2026-01-01T00:00:00Z")
  private val binding = "a" * 43
  private val raw = BrowserSessionToken.fromString("s" * 43).toOption.get
  private val policy = BrowserRetentionPolicy(idleBindingSeconds = 3, idleViewSeconds = 4,
    historySeconds = 2, maxSessionSeconds = 600)
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A = value.fold(e => fail(s"Unexpected rejection: $e"), identity)
  private case class HostState(sessions: Map[UUID, HostPrincipal[String]] = Map.empty, proofs: Int = 0,
    revoked: Set[UUID] = Set.empty)
  private class Fixture(
    limits: BrowserPersistenceLimits = BrowserPersistenceLimits(retainedBindings = 2, retainedViews = 2,
      retainedCeremonies = 2, anonymousBindings = 1, ceremonySeconds = 2, retention = policy)
  ) {
    val clock = new AtomicReference(start)
    val sequence = new AtomicLong()
    val restoreRevokedHost = new AtomicBoolean()
    def id(): UUID = new UUID(0L, sequence.incrementAndGet())
    val provider = new InMemoryBrowserPersistence[Future, HostState, String](HostState(),
      new InMemoryBrowserPersistence.Host[HostState, String] {
        def resolve(state: HostState, id: UUID, now: Instant): Option[HostPrincipal[String]] =
          if (state.revoked(id) && !restoreRevokedHost.get()) None else state.sessions.get(id)
      }, () => clock.get(), () => new UUID(7L, 7L), limits)
    def advance(seconds: Long): Unit = { clock.updateAndGet(_.plusSeconds(seconds)); () }
    def open(key: String = binding, qsid: Qsid = Qsid("device", "view")): BrowserView[String] = {
      val identity = accepted(await(provider.capture(key, None, activate = false)))
      accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(id()), existingOnly = false)))
    }
    def prepare(view: BrowserView[String], lifetime: Long, session: UUID = new UUID(42L, 42L),
      secret: BrowserSessionToken = raw):
      (CommittedAuthentication, BrowserSessionToken) = {
      val ceremony = accepted(await(provider.begin(view, "login")))
      val receipt = accepted(await(provider.prepare(ceremony) { tx =>
        val expires = clock.get().plusSeconds(lifetime)
        tx.modify(s => s.copy(sessions = s.sessions.updated(session, HostPrincipal("name", "subject", 1, 1, expires)),
          proofs = s.proofs + 1))
        new HostSessionPrepared(session, id(), expires, secret)
      }))
      receipt -> await(provider.deliver(receipt.completionId, view.identity.binding)).get
    }
    def activate(view: BrowserView[String], token: BrowserSessionToken): BrowserView[String] = {
      val identity = accepted(await(provider.capture(view.identity.binding, Some(token.value), activate = true)))
      accepted(await(provider.claim(identity, view.qsid, ConnectionId.fromUuid(id()), existingOnly = true)))
    }
    def login(key: String, qsid: Qsid, lifetime: Long, session: UUID): BrowserView[String] = {
      val initial = open(key, qsid)
      activate(initial, prepare(initial, lifetime, session)._2)
    }
    def replace(view: BrowserView[String], lifetime: Long): (CommittedReplacement, BrowserSessionToken) = {
      val ceremony = accepted(await(provider.beginReplacement(view, "replacement")))
      val invocation = BrowserReplacementInvocation.capture(view,
        new InvocationBinding(InvocationId.fromUuid(id()), view.connectionId))
      val receipt = accepted(await(provider.prepareReplacement(ceremony, invocation) { tx =>
        val expires = clock.get().plusSeconds(lifetime)
        val session = new UUID(77L, 77L)
        tx.modify(s => s.copy(sessions = s.sessions.updated(session, HostPrincipal("name", "subject", 2, 1, expires)),
          proofs = s.proofs + 1, revoked = s.revoked + view.identity.sessionId.get))
        new HostSessionPrepared(session, id(), expires, raw)
      }))
      receipt -> await(provider.deliver(receipt.completionId, view.identity.binding)).get
    }
    def resources = await(provider.resources)
    def maintain(): Unit = await(provider.maintenance())
    def close(): Unit = await(provider.close())
  }

  "Memory browser retention" should "sustain repeated complete lifecycles without evicting a live identity" in {
    val db = new Fixture(BrowserPersistenceLimits(retainedBindings = 2, retainedViews = 2, retainedCeremonies = 2,
      anonymousBindings = 1, ceremonySeconds = 2, retention = policy.copy(idleViewSeconds = 10)))
    try {
      val keeper = db.login("k" * 43, Qsid("keeper", "view"), 500, new UUID(100, 100))
      for (_ <- 1 to 25) {
        val temporary = db.login(binding, Qsid("temporary", "view"), 5, new UUID(42, 42))
        await(db.provider.release(temporary))
        db.advance(7)
        accepted(await(db.provider.authorize(keeper)))
        db.maintain()
        db.advance(3)
        db.maintain()
        db.resources.bindings shouldBe 1
        db.resources.views shouldBe 1
        db.resources.ceremonies shouldBe 1
        db.resources.sessions shouldBe 1
        accepted(await(db.provider.authorize(keeper))).principal.map(_.value) shouldBe Some("name")
        await(db.provider.authorize(temporary)).isLeft shouldBe true
      }
    } finally db.close()
  }

  it should "report anonymous quota warning and exhaustion independently of global binding capacity" in {
    val db = new Fixture(BrowserPersistenceLimits(retainedBindings = 100, anonymousBindings = 1, retention = policy))
    try {
      accepted(await(db.provider.capture(binding, None, activate = false)))
      await(db.provider.capture("b" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
      val usage = accepted(await(db.provider.usage()))
      usage.anonymousBindings shouldBe 1L
      usage.warnings().filter(_.resource == "anonymousBindings") shouldBe
        Vector(BrowserCapacityWarning("anonymousBindings", 1L, 1L))
      usage.warnings().exists(_.resource == "retainedBindings") shouldBe false
      db.advance(policy.idleBindingSeconds)
      db.maintain()
      accepted(await(db.provider.usage())).anonymousBindings shouldBe 0L
      accepted(await(db.provider.capture("b" * 43, None, activate = false)))
    } finally db.close()
  }

  it should "fence recycled bindings and view keys even when owner UUIDs repeat" in {
    val db = new Fixture
    try {
      val old = db.open()
      val ceremony = accepted(await(db.provider.begin(old, "login")))
      await(db.provider.release(old))
      db.advance(6)
      db.maintain()
      db.resources.bindings shouldBe 0
      val fresh = db.open()
      fresh.ownerId shouldBe old.ownerId
      fresh.identity.generation should be > old.identity.generation
      fresh.epoch should be > old.epoch
      await(db.provider.claim(old.identity, old.qsid, ConnectionId.fromUuid(db.id()), existingOnly = false)).isLeft shouldBe true
      await(db.provider.release(old))
      accepted(await(db.provider.authorize(fresh)))
      val called = new AtomicBoolean()
      await(db.provider.prepare(ceremony) { _ => called.set(true); throw new AssertionError("Stale callback") }).isLeft shouldBe true
      called.get() shouldBe false
      await(db.provider.transact(old)(_ => called.set(true))).isLeft shouldBe true
      called.get() shouldBe false
      await(db.provider.resume(fresh, ceremony.id, "login")).isLeft shouldBe true
    } finally db.close()
  }

  it should "retain the expiry grace and reclaim per-binding ceremony capacity with nonreused IDs" in {
    val db = new Fixture(BrowserPersistenceLimits(retainedCeremonies = 1, ceremoniesPerBinding = 1,
      ceremonySeconds = 2, retention = policy.copy(idleViewSeconds = 30)))
    try {
      val view = db.open()
      val first = accepted(await(db.provider.begin(view, "login")))
      db.advance(3)
      db.maintain()
      db.resources.ceremonies shouldBe 1
      await(db.provider.begin(view, "login")) shouldBe Left(BrowserFailure.Capacity)
      db.advance(1)
      val next = accepted(await(db.provider.begin(view, "login")))
      next.id should not be first.id
      next.id.value.version() shouldBe 4
      next.id.value.variant() shouldBe 2
      val called = new AtomicBoolean()
      await(db.provider.prepare(first) { _ => called.set(true); throw new AssertionError("Expired callback") }).isLeft shouldBe true
      called.get() shouldBe false
    } finally db.close()
  }

  it should "derive a fresh cookie when a host secret and session ID repeat after history retirement" in {
    val db = new Fixture
    try {
      val first = db.open()
      val (firstReceipt, firstToken) = db.prepare(first, 2)
      val signedIn = db.activate(first, firstToken)
      firstToken.value should not be raw.value
      await(db.provider.release(signedIn))
      db.advance(4)
      db.maintain()
      db.advance(3)
      db.maintain()
      db.resources.sessions shouldBe 0
      val second = db.open()
      val (secondReceipt, secondToken) = db.prepare(second, 2)
      firstReceipt.completionId should not be secondReceipt.completionId
      secondToken.value should not be firstToken.value
      await(db.provider.capture(binding, Some(firstToken.value), activate = true)).isLeft shouldBe true
      await(db.provider.deliver(firstReceipt.completionId, binding)) shouldBe None
      db.activate(second, secondToken).identity.principal.map(_.value) shouldBe Some("name")
    } finally db.close()
  }

  it should "renew active leases and refuse an expired owner before it can renew itself" in {
    val db = new Fixture
    try {
      val view = db.open()
      db.advance(3)
      accepted(await(db.provider.authorize(view)))
      db.advance(3)
      accepted(await(db.provider.authorize(view)))
      db.advance(4)
      await(db.provider.authorize(view)).isLeft shouldBe true
      db.maintain()
      db.resources.ownedViews shouldBe 0
      db.resources.views shouldBe 0
      val fresh = db.open()
      fresh.epoch should be > view.epoch
      accepted(await(db.provider.authorize(fresh)))
    } finally db.close()
  }

  it should "keep new cookies secret when a host reuses an exposed retired cookie as preparation material" in {
    val db = new Fixture
    try {
      val first = db.open()
      val (_, exposed) = db.prepare(first, 2)
      val signedIn = db.activate(first, exposed)
      await(db.provider.release(signedIn))
      db.advance(4)
      db.maintain()
      db.advance(3)
      db.maintain()
      db.resources.sessions shouldBe 0
      val second = db.open()
      val (receipt, issued) = db.prepare(second, 2, secret = exposed)
      issued.value should not be exposed.value
      // The attacker knows the retired cookie and every issuance context field,
      // but using that exposed value as the HMAC key cannot mint the new cookie.
      val forged = BrowserIssuance.credential(Base64.getUrlDecoder.decode(exposed.value), exposed,
        db.provider.scope, second.identity.authorityStamp.bindingKey, receipt.completionId)
      issued.value should not be forged.value
      await(db.provider.capture(binding, Some(exposed.value), activate = true)).isLeft shouldBe true
      await(db.provider.capture(binding, Some(forged.value), activate = true)).isLeft shouldBe true
      db.activate(second, issued).identity.principal.map(_.value) shouldBe Some("name")
    } finally db.close()
  }

  it should "reserve global capacity against anonymous churn and recover its idle quota" in {
    val db = new Fixture(BrowserPersistenceLimits(retainedBindings = 3, anonymousBindings = 1,
      retention = policy.copy(idleViewSeconds = 10)))
    try {
      val keeper = db.login("k" * 43, Qsid("keeper", "view"), 100, new UUID(99, 99))
      accepted(await(db.provider.capture(binding, None, activate = false)))
      await(db.provider.capture("b" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
      accepted(await(db.provider.authorize(keeper)))
      db.advance(3)
      accepted(await(db.provider.capture("b" * 43, None, activate = false)))
      accepted(await(db.provider.authorize(keeper)))
      db.resources.bindings shouldBe 2
    } finally db.close()
  }

  it should "reject browser sessions beyond the configured lifetime without committing host state" in {
    val db = new Fixture(BrowserPersistenceLimits(retention = policy.copy(maxSessionSeconds = 5)))
    try {
      val view = db.open()
      val ceremony = accepted(await(db.provider.begin(view, "login")))
      val result = await(db.provider.prepare(ceremony) { tx =>
        val expires = db.clock.get().plusSeconds(6)
        val session = db.id()
        tx.modify(s => s.copy(sessions = s.sessions.updated(session, HostPrincipal("name", "subject", 1, 1, expires)), proofs = 1))
        new HostSessionPrepared(session, db.id(), expires, raw)
      })
      result.isLeft shouldBe true
      accepted(await(db.provider.inspectHost)).proofs shouldBe 0
      db.resources.sessions shouldBe 0
    } finally db.close()
  }

  it should "retain live successor completion metadata past the ceremony deadline and eventually reclaim it" in {
    val db = new Fixture(BrowserPersistenceLimits(ceremonySeconds = 2,
      retention = policy.copy(idleViewSeconds = 10)))
    try {
      val previous = db.login(binding, Qsid("device", "view"), 40, new UUID(42, 42))
      val (receipt, token) = db.replace(previous, 20)
      val successor = db.activate(previous, token)
      db.advance(5)
      db.maintain()
      db.resources.ceremonies shouldBe 2
      accepted(await(db.provider.withSuccessorCompletion(successor)((_, completion) => completion)))
        .successorSessionId shouldBe successor.identity.sessionId.get
      db.advance(17)
      db.maintain()
      await(db.provider.authorize(successor)).isLeft shouldBe true
      await(db.provider.deliver(receipt.completionId, binding)) shouldBe None
      db.resources.ceremonies shouldBe 1
      db.advance(20)
      db.maintain()
      db.resources.bindings shouldBe 0
      db.resources.ceremonies shouldBe 0
      db.resources.sessions shouldBe 0
    } finally db.close()
  }

  it should "never restore predecessor authority when an abandoned committed successor expires" in {
    val db = new Fixture(BrowserPersistenceLimits(ceremonySeconds = 2,
      retention = policy.copy(idleViewSeconds = 30)))
    try {
      val previous = db.login(binding, Qsid("device", "view"), 40, new UUID(42, 42))
      db.replace(previous, 3)
      db.restoreRevokedHost.set(true)
      await(db.provider.authorize(previous)).isLeft shouldBe true
      db.advance(5)
      db.maintain()
      await(db.provider.authorize(previous)).isLeft shouldBe true
      await(db.provider.capture(binding, previous.identity.token, activate = true)).isLeft shouldBe true
      val anonymous = accepted(await(db.provider.capture(binding, None, activate = false)))
      anonymous.principal shouldBe None
      anonymous.generation should be > previous.identity.generation
    } finally db.close()
  }

  it should "wait for a held host transaction and never expose its expired staged writes" in {
    val db = new Fixture
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val maintenanceEntered = new CountDownLatch(1)
    val finished = new AtomicBoolean()
    try {
      val view = db.open()
      val ceremony = accepted(await(db.provider.begin(view, "login")))
      val preparing = Future(db.provider.prepare(ceremony) { tx =>
        entered.countDown()
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out")
        val expires = db.clock.get().plusSeconds(3)
        val session = db.id()
        tx.modify(s => s.copy(sessions = s.sessions.updated(session, HostPrincipal("name", "subject", 1, 1, expires)), proofs = 1))
        new HostSessionPrepared(session, db.id(), expires, raw)
      }).flatMap(identity)
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      db.advance(6)
      val maintaining = Future { maintenanceEntered.countDown(); db.maintain(); finished.set(true) }
      maintenanceEntered.await(5, TimeUnit.SECONDS) shouldBe true
      finished.get() shouldBe false
      release.countDown()
      await(preparing).isLeft shouldBe true
      await(maintaining)
      accepted(await(db.provider.inspectHost)).proofs shouldBe 0
      db.resources.ceremonies shouldBe 0
    } finally { release.countDown(); db.close() }
  }
}
