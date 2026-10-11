package spoonbill.state

import avocet.Id
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.ViewFence
import spoonbill.state.BoundedPresentationStore.*
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.*

class BoundedPresentationStoreSpec extends AnyFlatSpec with Matchers {
  private val start   = Instant.parse("2026-10-11T00:00:00Z")
  private val viewKey = Qsid("device", "view")
  private val child   = Id("1_2")
  private def fence(epoch: Long, view: Long = 1L): ViewFence = ViewFence(
    ViewSessionId.fromUuid(new UUID(0L, view)),
    ViewOwnerId.fromUuid(new UUID(0L, epoch)),
    ViewOwnershipEpoch.fromLong(epoch).toOption.get
  )
  private def await[A](value: Future[A]): A = Await.result(value, 2.seconds)
  private def rejected[A](value: => Future[A], error: Error): Unit =
    intercept[Rejected](await(value)).error shouldBe error

  "A bounded presentation store" should "retain component nodes but permanently detach predecessor managers and snapshots" in {
    val store        = new BoundedPresentationStore[Future, String](Limits(), () => start)
    val bootstrap    = await(store.bootstrap(viewKey, "root"))
    val bootSnapshot = await(bootstrap.snapshot)
    val first        = await(store.claim(viewKey, fence(1)))
    rejected(bootstrap.write(child, "late bootstrap"), Error.StaleOwner)
    intercept[Rejected](bootSnapshot.apply[String](Id.TopLevel)).error shouldBe Error.StaleOwner
    await(first.manager.write(child, "component"))
    await(first.publish())
    val snapshot = await(first.manager.snapshot)
    await(first.manager.write(child, "new component"))
    snapshot.apply[String](child) shouldBe Some("component")
    val second = await(store.claim(viewKey, fence(2)))
    await(second.manager.read[String](child)) shouldBe Some("new component")
    rejected(first.manager.read[String](child), Error.StaleOwner)
    rejected(first.manager.write(child, "stale"), Error.StaleOwner)
    rejected(first.publish(), Error.StaleOwner)
    intercept[Rejected](snapshot.apply[String](child)).error shouldBe Error.StaleOwner
    await(first.release())
    await(first.invalidate())
    await(second.publish())
    store.storage.remove(viewKey.deviceId, viewKey.sessionId)
    await(second.manager.read[String](child)) shouldBe Some("new component")
    await(store.resources).active shouldBe 1
  }

  it should "retain disconnected state within TTL and reject the released owner" in {
    val clock = new AtomicReference(start)
    val store = new BoundedPresentationStore[Future, String](Limits(reconnectSeconds = 5), () => clock.get())
    await(store.bootstrap(viewKey, "root"))
    val first = await(store.claim(viewKey, fence(1)))
    await(first.publish())
    await(first.release())
    await(first.release())
    rejected(first.manager.read[String](Id.TopLevel), Error.StaleOwner)
    await(store.resources).disconnected shouldBe 1
    val second = await(store.claim(viewKey, fence(2)))
    await(second.manager.read[String](Id.TopLevel)) shouldBe Some("root")
    await(second.publish())
    await(second.release())
    clock.set(start.plusSeconds(5))
    await(store.storage.exists(viewKey.deviceId, viewKey.sessionId)) shouldBe false
    await(store.resources).history shouldBe 1
    await(store.bootstrap(viewKey, "fresh"))
    rejected(store.claim(viewKey, fence(2)), Error.StaleOwner)
    val third = await(store.claim(viewKey, fence(3)))
    await(third.manager.read[String](Id.TopLevel)) shouldBe Some("fresh")
  }

  it should "invalidate retained state without allowing same-epoch resurrection" in {
    val store = new BoundedPresentationStore[Future, String](Limits(), () => start)
    await(store.bootstrap(viewKey, "revoked"))
    val first = await(store.claim(viewKey, fence(1)))
    await(first.publish())
    await(first.release())
    await(first.invalidate())
    await(store.storage.exists(viewKey.deviceId, viewKey.sessionId)) shouldBe false
    await(store.bootstrap(viewKey, "fresh"))
    rejected(store.claim(viewKey, fence(1)), Error.StaleOwner)
    rejected(store.claim(viewKey, fence(2, view = 2)), Error.WrongView)
    val next = await(store.claim(viewKey, fence(2)))
    await(next.manager.read[String](Id.TopLevel)) shouldBe Some("fresh")
  }

  it should "reject delayed publication after invalidation without touching a new owner" in {
    val store = new BoundedPresentationStore[Future, String](Limits(), () => start)
    await(store.bootstrap(viewKey, "old"))
    val first   = await(store.claim(viewKey, fence(1)))
    val setup   = Promise[Unit]()
    val delayed = setup.future.flatMap(_ => first.publish())
    await(first.invalidate())
    await(store.bootstrap(viewKey, "new"))
    val second = await(store.claim(viewKey, fence(2)))
    await(second.publish())
    setup.success(())
    rejected(delayed, Error.StaleOwner)
    await(first.release())
    await(second.manager.read[String](Id.TopLevel)) shouldBe Some("new")
  }

  it should "bound every retained category and keep failure from detaching a valid owner" in {
    val store = new BoundedPresentationStore[Future, String](
      Limits(active = 2, pending = 1, bootstrap = 1, disconnected = 1, history = 2, nodesPerView = 2),
      () => start
    )
    val other = Qsid("device", "other")
    val third = Qsid("device", "third")
    await(store.bootstrap(viewKey, "root"))
    rejected(store.bootstrap(other, "other"), Error.Capacity)
    val first = await(store.claim(viewKey, fence(1)))
    await(store.bootstrap(other, "other"))
    rejected(store.claim(other, fence(1, 2)), Error.Capacity)
    await(first.publish())
    val second = await(store.claim(other, fence(1, 2)))
    rejected(store.claim(viewKey, fence(2)), Error.Capacity)
    await(first.manager.read[String](Id.TopLevel)) shouldBe Some("root")
    await(first.manager.write(child, "child"))
    rejected(first.manager.write(Id("1_3"), "overflow"), Error.Capacity)
    await(first.manager.write(child, "updated"))
    await(second.publish())
    await(store.bootstrap(third, "third"))
    rejected(store.claim(third, fence(1, 3)), Error.Capacity)
    await(first.release())
    await(second.release())
    await(store.resources).disconnected shouldBe 1
    await(store.resources).history shouldBe 2
    rejected(store.claim(third, fence(1, 3)), Error.Capacity)
    await(store.close())
    await(store.close())
    await(store.resources) shouldBe Resources(0, 0, 0, 0, 0, 0)
    rejected(first.manager.read[String](Id.TopLevel), Error.Closed)
  }

  it should "expire abandoned bootstrap and pending claims without reviving them after a backward clock" in {
    val clock = new AtomicReference(start)
    val store = new BoundedPresentationStore[Future, String](
      Limits(bootstrapSeconds = 2, pendingSeconds = 2),
      () => clock.get()
    )
    val boot     = await(store.bootstrap(viewKey, "root"))
    val snapshot = await(boot.snapshot)
    clock.set(start.plusSeconds(2))
    intercept[Rejected](snapshot.apply[String](Id.TopLevel)).error shouldBe Error.StaleOwner
    clock.set(start)
    rejected(boot.read[String](Id.TopLevel), Error.StaleOwner)
    await(store.bootstrap(viewKey, "fresh"))
    val lease = await(store.claim(viewKey, fence(1)))
    clock.set(start.plusSeconds(4))
    rejected(lease.publish(), Error.StaleOwner)
    await(store.resources).pending shouldBe 0
    await(store.resources).history shouldBe 1
  }
}
