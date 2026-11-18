package spoonbill.auth

import BrowserCoordinatorGateMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BrowserCoordinatorGateMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Coordinator decisions" should "reserve cleanup and close past capacity while permanently refusing ordinary work after close" in {
    for (queued <- 0 to 4; closure <- Closure.values) {
      for (kind <- Vector(Admission.Ordinary, Admission.Read("key"))) {
        admission(closure, queued, kind, 2) shouldBe
          (if (closure != Closure.Open || queued >= 2) Decision.Reject else Decision.Enqueue)
      }
      for (kind <- Vector(Admission.Cleanup, Admission.Close)) {
        admission(closure, queued, kind, 2) shouldBe (closure match {
          case Closure.Open    => Decision.Enqueue
          case Closure.Closing => Decision.JoinClose
          case Closure.Closed  => Decision.ReplayClose
        })
      }
    }
    requested(Closure.Open, Admission.Close) shouldBe Closure.Closing
    completed(Closure.Closing) shouldBe Closure.Closed
    for (kind <- Vector(Admission.Ordinary, Admission.Cleanup, Admission.Close, Admission.Read("key")))
      requested(Closure.Closed, kind) shouldBe Closure.Closed
  }

  it should "distinguish observed key waits from capacity stops and impose a writer barrier" in {
    val first = readerStarted(Occupancy.Idle, "key")
    readerDecision(first, "key", 1) shouldBe ReaderDecision.Stop
    readerDecision(first, "key", 2) shouldBe ReaderDecision.Blocked
    readerDecision(first, "other", 2) shouldBe ReaderDecision.Grant
    writerDecision(first, noBlockedReads = true) shouldBe WriterDecision.Stop
    val both = readerStarted(first, "other")
    runningReads(both) shouldBe 2
    val none = finished(finished(both, Turn.Read("key")), Turn.Read("other"))
    none shouldBe Occupancy.Idle
    writerDecision(none, noBlockedReads = false) shouldBe WriterDecision.Stop
    writerDecision(none, noBlockedReads = true) shouldBe WriterDecision.GrantAndStop
    readerDecision(writerStarted, "late", 3) shouldBe ReaderDecision.Stop
  }

  it should "ignore invalid completions without losing existing key ownership" in {
    finished(Occupancy.Idle, Turn.Write) shouldBe Occupancy.Idle
    val reading = readerStarted(Occupancy.Idle, "key")
    finished(reading, Turn.Read("missing")) shouldBe reading
    finished(reading, Turn.Write) shouldBe reading
    finished(writerStarted, Turn.Read("key")) shouldBe writerStarted
    finished(writerStarted, Turn.Write) shouldBe Occupancy.Idle
  }

  it should "conserve read ownership through generated admissions and completions without overlapping writers" in {
    forAll(Gen.listOf(Gen.zip(Gen.choose(0, 3), Gen.choose(0, 4)))) { events =>
      events.foldLeft((Occupancy.Idle: Occupancy, Set.empty[Int], false)) {
        case ((state, owned, writing), (event, key)) =>
          val next = event match {
            case 0 if readerDecision(state, key, 3) == ReaderDecision.Grant =>
              writing shouldBe false
              owned should not contain key
              (readerStarted(state, key), owned + key, false)
            case 1 => (finished(state, Turn.Read(key)), owned - key, writing)
            case 2 if writerDecision(state, noBlockedReads = true) == WriterDecision.GrantAndStop =>
              owned shouldBe empty
              writing shouldBe false
              (writerStarted, owned, true)
            case 3 => (finished(state, Turn.Write), owned, false)
            case _ => (state, owned, writing)
          }
          runningReads(next._1) shouldBe next._2.size
          runningReads(next._1) should be <= 3
          (next._1 == Occupancy.Writing) shouldBe next._3
          next
      }
    }
  }
}
