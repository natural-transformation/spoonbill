package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import spoonbill.effect.Queue

class SessionIdleInstallRaceSpec extends AnyFlatSpec with Matchers {
  import SessionIdleTestSupport.*
  import SessionIdleTestSupport.given

  "Guarded idle timer ownership" should "cancel a handle returned after input closure has completed teardown" in {
    val f        = new Fixture(guarded = true)
    val incoming = Queue[Future, String]()
    // Deterministic equivalent of teardown racing between scheduling the job
    // and publishing its handle into the session's idle resource slot.
    f.timing.onCreated = _ => await(incoming.stream.cancel())
    val attached = f.attach(incoming)
    try {
      await(f.service.getApp(f.qsid)) shouldBe None
      f.destroyed shouldBe 1
      f.guardsClosed shouldBe 1
      f.timing.jobs.size shouldBe 1
      f.timing.jobs.head.cancellations shouldBe 1
      await(f.timing.jobs.head.fire())
      f.destroyed shouldBe 1
      f.guardsClosed shouldBe 1
    } finally await(attached.release())
  }

  it should "leave cancellation with teardown when it has already taken the new published handle" in {
    val f        = new Fixture(guarded = true)
    val incoming = Queue[Future, String]()
    val attached = f.attach(incoming)
    f.timing.onCancelled = job => if (job.id == 0) await(attached.release())
    try {
      await(incoming.enqueue("[6]"))
      await(f.service.getApp(f.qsid)) shouldBe None
      f.timing.jobs.size shouldBe 2
      f.timing.jobs.head.cancellations shouldBe 1
      f.timing.jobs(1).cancellations shouldBe 1
      f.destroyed shouldBe 1
      f.guardsClosed shouldBe 1
    } finally await(attached.release())
  }
}
