package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuardedSessionMachineSpec extends AnyFlatSpec with Matchers {
  import GuardedSessionMachine.*

  "Guarded lifetime decisions" should "keep acquisition rollback separate from successful attachment and terminal signalling" in {
    val initial = State()
    needsRollback(initial) shouldBe false
    val claimed = step(initial, Event.Claim)._1
    needsRollback(claimed) shouldBe true
    step(claimed, Event.Claim) shouldBe (claimed, Command.None)
    val (signalledState, signal) = step(claimed, Event.Signal)
    signal shouldBe Command.Signal
    signalled(signalledState) shouldBe true
    step(signalledState, Event.Signal) shouldBe (signalledState, Command.None)
    // Signalling schedules cleanup; it does not move the admission boundary earlier.
    live(signalledState) shouldBe true
    val attached = step(signalledState, Event.Attach)._1
    needsRollback(attached) shouldBe false
    step(attached, Event.Claim) shouldBe (attached, Command.None)
    val denied = step(attached, Event.Deny)._1
    live(denied) shouldBe false
    live(step(denied, Event.AcquireStorage)._1) shouldBe false
    ownsStorage(step(denied, Event.AcquireStorage)._1) shouldBe true
  }

  it should "stop delivery and component admission before denying access, then drain before releasing resources" in {
    val (started, first) = step(State(), Event.BeginCleanup)
    first shouldBe Command.Run(Stage.StopExtensions)
    live(started) shouldBe true
    step(started, Event.BeginCleanup) shouldBe (started, Command.None)
    step(started, Event.Finished(Stage.DrainWrites)) shouldBe (started, Command.None)
    val components = step(started, Event.Finished(Stage.StopExtensions))._1
    val admission  = step(components, Event.Finished(Stage.StopComponents))._1
    admission.cleanup shouldBe Cleanup.Running(Stage.DenyAndStopIdle)
    val denied   = step(admission, Event.Deny)._1
    val ready    = step(denied, Event.Finished(Stage.DenyAndStopIdle))._1
    val frontend = step(ready, Event.Finished(Stage.RejectReady))._1
    val draining = step(frontend, Event.Finished(Stage.CloseFrontend))._1
    draining.cleanup shouldBe Cleanup.Running(Stage.DrainWrites)
    live(draining) shouldBe false
    step(draining, Event.BeginCleanup) shouldBe (draining, Command.None)
    step(draining, Event.Finished(Stage.CloseGuard)) shouldBe (draining, Command.None)
    val application = step(draining, Event.Finished(Stage.DrainWrites))._1
    val guard       = step(application, Event.Finished(Stage.DestroyApplication))._1
    val extensions  = step(guard, Event.Finished(Stage.CloseGuard))._1
    val storage     = step(extensions, Event.Finished(Stage.DestroyExtensions))._1
    val registry    = step(storage, Event.Finished(Stage.RemoveStorage))._1
    val attachment  = step(registry, Event.Finished(Stage.RemoveApplication))._1
    attachment.cleanup shouldBe Cleanup.Running(Stage.ReleaseAttachment)
    val done = step(attachment, Event.Finished(Stage.ReleaseAttachment))._1
    done.cleanup shouldBe Cleanup.Done
    step(done, Event.BeginCleanup) shouldBe (done, Command.None)
  }

  it should "never retry cleanup after an unisolated failure or release the attachment by skipping a stage" in {
    val waiting = step(State(), Event.BeginCleanup)._1
    val failed  = step(waiting, Event.CleanupFailed)._1
    failed.cleanup shouldBe Cleanup.Failed
    step(failed, Event.BeginCleanup) shouldBe (failed, Command.None)
    step(failed, Event.Finished(Stage.ReleaseAttachment)) shouldBe (failed, Command.None)
    for (stage <- Stage.values) {
      val state  = State(cleanup = Cleanup.Running(stage), admission = Admission.Denied)
      val closed = step(state, Event.Signal)._1
      closed.cleanup shouldBe state.cleanup
      live(closed) shouldBe false
    }
  }
}
