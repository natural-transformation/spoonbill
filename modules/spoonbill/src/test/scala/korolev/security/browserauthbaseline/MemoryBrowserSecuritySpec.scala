package spoonbill.browserauthbaseline

import MemoryBrowserAuth.*
import avocet.Id
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.action.{AccessDecision, InvocationBinding}
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.{StateDeserializer, StateSerializer}
import spoonbill.web.{PathAndQuery, Request}

class MemoryBrowserSecuritySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private case class Page(protectedPage: Boolean = false, name: Option[String] = None, count: Int = 0)
  private given StateSerializer[Page]                = (page: Page) => Array.emptyByteArray
  private given StateDeserializer[Page]              = (_: Array[Byte]) => None
  private val start                                  = Instant.parse("2026-10-10T12:00:00Z")
  private val binding                                = "binding-cookie"
  private val credential                             = "A" * 43
  private val qsid                                   = Qsid("device", "view")
  private def connection(n: Long)                    = ConnectionId.fromUuid(new UUID(0, n))
  private def invocation(n: Long)                    = new InvocationBinding(InvocationId.fromUuid(new UUID(1, n)), connection(n))
  private def result[A](value: Future[A]): A         = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A = value.fold(e => fail(s"Unexpected rejection: $e"), identity)
  private def request(authenticated: Boolean = false, browserBinding: String = binding): Request[Unit] = Request(
    Request.Method.Get,
    PathAndQuery.Root,
    Seq("Origin" -> "http://localhost:8080"),
    None,
    (),
    s"baseline_binding=$browserBinding" + (if (authenticated) s";baseline_session=$credential" else "")
  )
  private class Fixture(limits: MemoryBrowserSecurity.Limits = MemoryBrowserSecurity.Limits()) {
    val clock = new AtomicReference(start)
    val host =
      new MemoryBrowserAuth[Future](Vector(syntheticAccount("alice", "password")), () => clock.get(), () => credential)
    val security = new MemoryBrowserSecurity[Future, Page](
      host,
      () => Page(),
      _.protectedPage,
      (page, principal) => page.copy(name = principal.map(_.name)),
      limits
    )
    def create(id: Qsid = qsid): Unit = { result(security.storage.create(id.deviceId, id.sessionId, Page())); () }
    def open(n: Long, authenticated: Boolean = false, id: Qsid = qsid) = {
      val guard = result(security.open(id, request(authenticated), connection(n)))
      if (result(security.storage.exists(id.deviceId, id.sessionId))) guard
      else {
        // Model the v3 terminal reload, fresh HTTP bootstrap and next handshake.
        result(guard.close())
        create(id)
        result(security.open(id, request(authenticated), connection(n)))
      }
    }
    def login(n: Long = 1): UUID = {
      val ceremony = accepted(result(security.begin(connection(n))))
      result(security.password(connection(n), ceremony, "alice", "password")) shouldBe Right(Reply.Prepared(ceremony))
      result(host.deliver(ceremony, binding)).isDefined shouldBe true
      ceremony
    }
  }

  "The v3 memory presentation adapter" should "bind ordinary actions and protected rendering to a captured physical connection" in {
    val f = new Fixture
    f.create()
    val anonymous = f.open(1)
    anonymous.sensitive shouldBe None
    anonymous.viewSnapshots shouldBe None
    result(anonymous.connected(Page())).name shouldBe None
    intercept[SessionAccessDenied](result(anonymous.authorize(Page(protectedPage = true))))
    f.login()
    val authenticated = f.open(2, authenticated = true)
    val principal     = accepted(result(f.security.authority.resolve(invocation(2))))
    result(authenticated.connected(Page())).name shouldBe Some("alice")
    result(authenticated.authorize(Page(protectedPage = true))) shouldBe ()
    result(f.security.authority.revalidate(invocation(2), principal)) shouldBe AccessDecision.Allowed
    intercept[SessionAccessDenied](result(anonymous.authorize(Page())))
    result(f.security.authority.resolve(invocation(1))).isLeft shouldBe true
    result(f.security.authorizeHttp(request(authenticated = true), Page(protectedPage = true))) shouldBe ()
    intercept[SessionAccessDenied](result(f.security.authorizeHttp(request(), Page(protectedPage = true))))
  }

  it should "fence every old-manager operation and old close after same-identity takeover" in {
    val f = new Fixture
    f.create()
    val first = f.open(1)
    val old   = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(old.write(Id.TopLevel, Page(count = 7)))
    val snapshot = result(old.snapshot)
    val second   = f.open(2)
    val current  = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(current.read[Page](Id.TopLevel)) shouldBe Some(Page(count = 7))
    intercept[SessionAccessDenied](result(old.read[Page](Id.TopLevel)))
    intercept[SessionAccessDenied](result(old.write(Id.TopLevel, Page(count = 99))))
    intercept[SessionAccessDenied](result(old.delete(Id.TopLevel)))
    intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
    result(first.close())
    f.security.storage.remove(qsid.deviceId, qsid.sessionId)
    result(second.authorize(Page())) shouldBe ()
    result(current.read[Page](Id.TopLevel)) shouldBe Some(Page(count = 7))
    result(f.security.resources).active shouldBe 1
  }

  it should "bound active views per binding while preserving takeover and independent browser admission" in {
    val f          = new Fixture(MemoryBrowserSecurity.Limits(active = 3, activePerBinding = 1))
    val secondView = Qsid("device", "second-view")
    val otherView  = Qsid("other-device", "view")
    f.create()
    val first = f.open(1)
    f.create(secondView)
    intercept[SessionAccessDenied](result(f.security.open(secondView, request(), connection(2))))
    result(first.authorize(Page())) shouldBe ()
    val successor = f.open(3)
    result(first.close())
    result(successor.authorize(Page())) shouldBe ()
    f.create(otherView)
    val other = result(f.security.open(otherView, request(browserBinding = "other-binding"), connection(4)))
    result(other.authorize(Page())) shouldBe ()
    result(f.security.resources).active shouldBe 2
    result(successor.close())
    val next = result(f.security.open(secondView, request(), connection(5)))
    result(next.authorize(Page())) shouldBe ()
    result(f.security.resources).active shouldBe 2
  }

  it should "retain disconnected immutable presentation only within its reconnect TTL" in {
    val f = new Fixture(MemoryBrowserSecurity.Limits(reconnectSeconds = 5))
    f.create()
    val first = f.open(1)
    val old   = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(old.write(Id.TopLevel, Page(count = 4)))
    result(first.close())
    intercept[SessionAccessDenied](result(old.read[Page](Id.TopLevel)))
    intercept[SessionAccessDenied](result(f.security.storage.get(qsid.deviceId, qsid.sessionId)))
    val next    = f.open(2)
    val resumed = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(resumed.read[Page](Id.TopLevel)) shouldBe Some(Page(count = 4))
    result(next.close())
    f.clock.set(start.plusSeconds(5))
    result(f.security.storage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
    f.security.resume(qsid, request(), connection(3)) shouldBe None
    result(f.security.resources).nodes shouldBe 0
  }

  it should "invalidate revoked managers and publish fresh state under a new anonymous generation" in {
    val f = new Fixture
    f.create()
    f.open(1)
    f.login()
    val active  = f.open(2, authenticated = true)
    val manager = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(manager.write(Id.TopLevel, Page(name = Some("alice"), count = 42)))
    val snapshot = result(manager.snapshot)
    result(f.security.logout(connection(2))) shouldBe Right(())
    intercept[SessionAccessDenied](result(manager.read[Page](Id.TopLevel)))
    intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
    val fresh = f.open(3)
    result(active.close())
    val next = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(next.read[Page](Id.TopLevel)) shouldBe Some(Page())
    result(fresh.authorize(Page())) shouldBe ()
    result(f.security.authority.resolve(invocation(2))).isLeft shouldBe true
    intercept[SessionAccessDenied](result(f.security.begin(connection(2))))
  }

  it should "reject stale-cookie reacquisition after policy changes without restoring former presentation" in {
    val f = new Fixture
    f.create()
    f.open(1)
    f.login()
    f.open(2, authenticated = true)
    val manager = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(f.host.changeAccount("alice", enabled = false))
    intercept[SessionAccessDenied](result(f.security.open(qsid, request(authenticated = true), connection(3))))
    intercept[SessionAccessDenied](result(manager.write(Id.TopLevel, Page(count = 7))))
    result(f.security.resources).active shouldBe 0
    result(f.security.resources).bootstrap shouldBe 1
  }

  it should "bound bootstrap, active, disconnected and node populations and clean abandoned bootstrap" in {
    val f = new Fixture(
      MemoryBrowserSecurity.Limits(active = 1, disconnected = 1, bootstrap = 1, nodesPerView = 1, bootstrapSeconds = 5)
    )
    val other = Qsid("device", "other")
    f.create()
    intercept[SessionAccessDenied](f.create(other))
    val active = f.open(1)
    f.create(other)
    intercept[SessionAccessDenied](f.open(2, id = other))
    val manager = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    intercept[SessionAccessDenied](result(manager.write(Id("1_2"), Page())))
    result(active.close())
    val second = f.open(2, id = other)
    result(second.close())
    result(f.security.resources).disconnected shouldBe 1
    f.create(Qsid("device", "abandoned"))
    f.clock.set(start.plusSeconds(5))
    result(f.security.resources).bootstrap shouldBe 0
  }

  it should "reject absent binding, wrong origin and unknown views without allocation" in {
    val f = new Fixture
    f.create()
    val noBinding = request().copy(renderedCookie = null)
    intercept[SessionAccessDenied](result(f.security.open(qsid, noBinding, connection(1))))
    intercept[SessionAccessDenied](
      result(f.security.open(qsid, request().copy(headers = Seq("Origin" -> "https://wrong.example")), connection(1)))
    )
    intercept[SessionAccessDenied](result(f.security.open(Qsid("unknown", "view"), request(), connection(1))))
    result(f.security.resources).bootstrap shouldBe 1
    result(f.security.resources).active shouldBe 0
  }

  it should "release every presentation resource when the host closes and tolerate late guard cleanup" in {
    val f = new Fixture
    f.create()
    val guard   = f.open(1)
    val manager = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    f.host.close()
    result(guard.close()) shouldBe ()
    f.security.storage.remove(qsid.deviceId, qsid.sessionId)
    result(f.security.close()) shouldBe ()
    result(f.security.resources) shouldBe MemoryBrowserSecurity.Resources(0, 0, 0, 0)
    intercept[SessionAccessDenied](result(manager.read[Page](Id.TopLevel)))
  }

  it should "reject an acquisition delayed across logout instead of capturing the replacement generation" in {
    val beforeContinuation = new AtomicReference[() => Unit](() => ())
    val effect = new Effect.FutureEffect {
      override def flatMap[A, B](value: Future[A])(run: A => Future[B]): Future[B] =
        super.flatMap(value) { result =>
          beforeContinuation.getAndSet(() => ())(); run(result)
        }
    }
    val host = new MemoryBrowserAuth[Future](Vector(syntheticAccount("alice", "password")), () => start)(using effect)
    val security =
      new MemoryBrowserSecurity[Future, Page](host, () => Page(), _.protectedPage, (page, _) => page)(using effect)
    result(security.storage.create(qsid.deviceId, qsid.sessionId, Page()))
    beforeContinuation.set { () =>
      result(host.logout(binding)); ()
    }
    intercept[SessionAccessDenied](result(security.open(qsid, request(), connection(1))))
    result(security.resources).active shouldBe 0
    result(security.resources).bootstrap shouldBe 1
  }

  it should "preserve the HTTP DOM baseline and require terminal reload before an identity-changed view is rendered" in {
    val f = new Fixture
    result(f.security.storage.create(qsid.deviceId, qsid.sessionId, Page(count = 17)))
    val anonymous = f.open(1)
    val initial   = result(f.security.storage.get(qsid.deviceId, qsid.sessionId))
    result(initial.read[Page](Id.TopLevel)) shouldBe Some(Page(count = 17))
    f.login()
    val reloading = result(f.security.open(qsid, request(authenticated = true), connection(2)))
    // SessionsService checks this after guard acquisition and emits Reload;
    // it must never initialize the old browser DOM from replacement nodes.
    result(f.security.storage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
    intercept[SessionAccessDenied](result(f.security.storage.get(qsid.deviceId, qsid.sessionId)))
    result(reloading.close())
    result(anonymous.close())
    result(f.security.storage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
    f.create()
    val fresh = f.open(3, authenticated = true)
    result(fresh.connected(Page())).name shouldBe Some("alice")
  }
}
