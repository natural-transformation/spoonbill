package spoonbill.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId

class InMemoryBrowserCheckpointSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]                       = Effect.futureEffect
  private def await[A](value: Future[A]): A          = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A = value.fold(e => fail(s"Unexpected rejection: $e"), identity)
  private class Fixture {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val clock = new AtomicReference(start)
    val policy =
      BrowserRetentionPolicy(idleBindingSeconds = 3, idleViewSeconds = 4, historySeconds = 2, maxSessionSeconds = 600)
    val provider = new InMemoryBrowserPersistence[Future, Int, String](
      0,
      new InMemoryBrowserPersistence.Host[Int, String] {
        def resolve(state: Int, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
      },
      () => clock.get(),
      () => UUID.randomUUID(),
      BrowserPersistenceLimits(retention = policy)
    )
    def open(): BrowserView[String] = {
      val identity = accepted(await(provider.capture("b" * 43, None, activate = false)))
      accepted(
        await(
          provider.claim(
            identity,
            Qsid("checkpoint", "view"),
            ConnectionId.fromUuid(UUID.randomUUID()),
            existingOnly = false
          )
        )
      )
    }
  }

  "Memory provider checkpoints" should "retain observed time when staged host changes roll back" in {
    val f = new Fixture
    try {
      val view = f.open()
      await(f.provider.transact(view) { tx =>
        tx.modify(_ + 1)
        f.clock.set(f.start.plusSeconds(10))
        tx.now shouldBe f.clock.get()
        throw new IllegalStateException("abort after observing time")
      }).isLeft shouldBe true
      accepted(await(f.provider.inspectHost)) shouldBe 0
      f.clock.set(f.start)
      await(f.provider.authorize(view)).isLeft shouldBe true
    } finally await(f.provider.close())
  }

  it should "retain reclaimed records and monotone fences when a later reclaim listener throws" in {
    val f = new Fixture
    try {
      val old = f.open()
      await(f.provider.release(old))
      var failListener = true
      f.provider.onViewsReclaimed { (_, _) =>
        if (failListener) { failListener = false; throw new IllegalStateException("listener after reclamation") }
      }
      f.clock.set(f.start.plusSeconds(10))
      await(f.provider.capture("c" * 43, None, activate = false)) shouldBe Left(BrowserFailure.StorageFailure)
      val swept = await(f.provider.resources)
      swept.views shouldBe 0
      swept.bindings shouldBe 0
      val next = f.open()
      next.epoch should be > old.epoch
      next.identity.generation should be > old.identity.generation
      await(f.provider.authorize(old)).isLeft shouldBe true
      await(f.provider.authorize(next)).isRight shouldBe true
    } finally await(f.provider.close())
  }
}
