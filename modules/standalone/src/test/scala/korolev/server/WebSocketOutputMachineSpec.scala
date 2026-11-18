package spoonbill.server

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class WebSocketOutputMachineSpec extends AnyFlatSpec with Matchers {
  import WebSocketOutputMachine.*

  "WebSocket output ownership" should "separate Close emission from the following cleanup pull" in {
    val pulling = attached(initial, true)
    pulling.output shouldBe Output.Pulling
    val closing = closeEmitted(graceful(pulling))
    closing.direction shouldBe Direction.Graceful
    closing.output shouldBe Output.CloseEmitted
    closing.cleanupClaimed shouldBe false
    val finished = finalizeClose(ended(closing), false)
    finished shouldBe State(Direction.Graceful, Output.Ended, true)
  }

  it should "make abort dominant even after graceful cleanup has been claimed" in {
    val closing = finalizeClose(graceful(attached(initial, true)), false)
    val aborted = finalizeClose(closing, true)
    graceful(aborted) shouldBe aborted
    finalizeClose(aborted, false).direction shouldBe Direction.Aborted
    ended(aborted).cleanupClaimed shouldBe true
    attached(initial, false).output shouldBe Output.Ended
  }
}
