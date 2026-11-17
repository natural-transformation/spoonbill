package spoonbill.security.jdbc

import java.time.{Duration, Instant}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JdbcClockMachineSpec extends AnyFlatSpec with Matchers {
  private val start = Instant.parse("2026-10-11T00:00:00Z")

  "Clock frontiers" should "never authorize an unacknowledged observation or move backward" in {
    val samples = Vector(0L, 8L, -2L, 3L, 20L, -10L).map(start.plusSeconds)
    val observations = samples.scanLeft(Instant.MIN)(JdbcClockMachine.observe)
    observations.tail shouldBe Vector(0L, 8L, 8L, 8L, 20L, 20L).map(start.plusSeconds)
    observations.foreach(value => JdbcClockMachine.covered(None, value) shouldBe None)
    val checkpoints = samples.scanLeft(Option.empty[Instant])(JdbcClockMachine.acknowledge)
    checkpoints.last shouldBe Some(start.plusSeconds(20))
    checkpoints.drop(1).zip(observations.tail).foreach { case (checkpoint, observed) =>
      JdbcClockMachine.covered(checkpoint, observed) shouldBe Some(observed)
      JdbcClockMachine.covered(checkpoint, observed.plusNanos(1)) shouldBe None
    }
  }

  it should "retain observed expiry after a failed checkpoint without inventing durable authority" in {
    val acknowledged = JdbcClockMachine.acknowledge(None, start)
    val afterFailure = JdbcClockMachine.observe(start, start.plusSeconds(2))
    val backwardWall = JdbcClockMachine.observe(afterFailure, start.minusSeconds(5))
    backwardWall shouldBe start.plusSeconds(2)
    JdbcClockMachine.covered(acknowledged, backwardWall) shouldBe None
    val retried = JdbcClockMachine.acknowledge(acknowledged, backwardWall)
    JdbcClockMachine.covered(retried, backwardWall) shouldBe Some(backwardWall)
  }

  it should "renew from wall time without extending a future restart floor early" in {
    val checkpoint = start.plusSeconds(1)
    val window = Duration.ofMillis(500)
    JdbcClockMachine.renewAhead(checkpoint, start, window) shouldBe false
    JdbcClockMachine.renewAhead(checkpoint, start.plusMillis(500).minusNanos(1), window) shouldBe false
    JdbcClockMachine.renewAhead(checkpoint, start.plusMillis(500), window) shouldBe true
    JdbcClockMachine.externallyAdvanced(checkpoint, checkpoint) shouldBe false
    JdbcClockMachine.externallyAdvanced(checkpoint, checkpoint.plusNanos(1)) shouldBe true
  }

  it should "fence only failures after a waiter arrived, including signed counter wrap" in {
    JdbcClockMachine.failedWhileWaiting(None, 10L) shouldBe false
    JdbcClockMachine.failedWhileWaiting(Some(9L), 10L) shouldBe false
    JdbcClockMachine.failedWhileWaiting(Some(10L), 10L) shouldBe false
    JdbcClockMachine.failedWhileWaiting(Some(11L), 10L) shouldBe true
    JdbcClockMachine.failedWhileWaiting(Some(Long.MinValue + 1L), Long.MaxValue) shouldBe true
    JdbcClockMachine.failedWhileWaiting(Some(Long.MaxValue), Long.MinValue + 1L) shouldBe false
  }
}
