package spoonbill.server.internal.services

import TopicOpeningMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class TopicOpeningMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "A long-polling request" should "get its topic by the opening that it names" in {
    val cases = Table[Option[Long], Option[Long], Decision](
      ("the topic serves", "the request names", "decision"),
      (None, None, Decision.Create),
      (None, Some(1L), Decision.Create),
      (Some(1L), Some(1L), Decision.Use),
      (Some(1L), Some(2L), Decision.Replace),
      (Some(1L), Some(5L), Decision.Replace),
      (Some(2L), Some(1L), Decision.Stale),
      // A request of an older client never ends a topic and is never refused.
      (Some(2L), None, Decision.Use),
      (Some(Unnumbered), None, Decision.Use),
      // A page that numbers its openings replaces a topic without a number.
      (Some(Unnumbered), Some(1L), Decision.Replace)
    )
    forAll(cases)((serving, requested, decision) => decide(serving, requested) shouldBe decision)
  }

  it should "end a topic only for a higher number, whatever came before" in {
    // One session through arbitrary requests. The number of its topic never
    // goes down, and exactly the requests below it are stale.
    forAll(Gen.listOf(Gen.option(Gen.choose(1L, 6L)))) { requests =>
      requests.foldLeft(Option.empty[Long]) { (serving, requested) =>
        val decision = decide(serving, requested)
        val next = decision match {
          case Decision.Create | Decision.Replace => Some(requested.getOrElse(Unnumbered))
          case Decision.Use | Decision.Stale      => serving
        }
        val below = serving.exists(topic => requested.exists(_ < topic))
        (decision == Decision.Stale) shouldBe below
        (decision == Decision.Replace) shouldBe serving.exists(topic => requested.exists(_ > topic))
        for (before <- serving; after <- next) after should be >= before
        next
      }
    }
  }

  "The opening of a request" should "be a plain positive number, or none" in {
    val cases = Table[Option[String], Option[Long]](
      ("parameter", "opening"),
      (None, None),
      (Some("1"), Some(1L)),
      (Some("42"), Some(42L)),
      (Some("007"), Some(7L)),
      (Some("999999999999999999"), Some(999999999999999999L)),
      (Some("0"), None),
      (Some(""), None),
      (Some("-1"), None),
      (Some("+1"), None),
      (Some("1.0"), None),
      (Some(" 1"), None),
      (Some("1e3"), None),
      // A digit, but not one of 0 to 9.
      (Some("٣"), None),
      // One digit more than a Long is sure to hold.
      (Some("1234567890123456789"), None)
    )
    forAll(cases)((raw, opening) => parse(raw) shouldBe opening)
  }
}
