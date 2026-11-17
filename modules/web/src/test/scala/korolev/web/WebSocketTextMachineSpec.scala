package spoonbill.web

import java.nio.charset.StandardCharsets.UTF_8
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class WebSocketTextMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import WebSocketTextMachine.*

  "Text measurement" should "name split surrogates, invalid input, and over-budget prefixes" in {
    scan(initial, "a\u00e9\u20ac", 6) shouldBe State(6)
    scan(initial, "\ud83d", 4) shouldBe State(4, true)
    scan(State(4, true), "\ude00", 4) shouldBe State(4)
    scan(State(4, true), "x", 4) shouldBe State(4, true, Outcome.InvalidUtf16)
    scan(initial, "\udc00", 4) shouldBe State(0, false, Outcome.InvalidUtf16)
    scan(initial, "abcdef", 3) shouldBe State(4, false, Outcome.Exceeded)
    scan(initial, "\ud83d", 3) shouldBe State(4, true, Outcome.Exceeded)
    scan(State(2, false, Outcome.InvalidUtf16), "", 3) shouldBe State(2)
  }

  it should "count valid text independently of chunk boundaries in generated histories" in {
    forAll(Gen.listOf(Gen.oneOf("a", "\u00e9", "\u20ac", "\ud83d\ude00"))) { pieces =>
      val text = pieces.mkString
      val expected = text.getBytes(UTF_8).length.toLong
      val whole = scan(initial, text, Int.MaxValue)
      val fragmented = text.foldLeft(initial) { (state, char) =>
        val next = scan(state, char.toString, Int.MaxValue)
        scan(state.bytes, state.pendingHigh, char.toString, Int.MaxValue) shouldBe next
        next
      }
      whole shouldBe State(expected)
      fragmented shouldBe whole
      scan(initial, text, Int.MaxValue) shouldBe whole
    }
  }

  it should "make compact ASCII counting equivalent to the checked model, including late Unicode and rejection" in {
    val chunks = Gen.listOf(Gen.oneOf("a", "\u00e9", "\u20ac", "\ud83d", "\ude00"))
    forAll(chunks, Gen.choose(0L, 40L), Gen.oneOf(false, true), Gen.choose(1, 80)) {
      (pieces, bytes, pending, maximum) =>
        val chunk = pieces.mkString
        val full = scan(bytes, pending, chunk, maximum)
        val compact = if (asciiEligible(bytes, pending, chunk, maximum)) {
          val prefix = asciiPrefix(chunk)
          val counted = countedAscii(bytes, prefix)
          if (prefix == chunk.length) State(counted)
          else scanFrom(counted, false, chunk, prefix, maximum)
        } else if (completeEligible(bytes, pending, chunk, maximum)) {
          val counted = completeBytes(bytes, chunk, maximum)
          if (counted == CheckedScanRequired) full else State(counted)
        } else full
        compact shouldBe full
    }
    val mixed = "a" * 1000 + "\u20ac"
    asciiPrefix(mixed) shouldBe 1000
    scanFrom(countedAscii(0, 1000), false, mixed, 1000, 1002) shouldBe scan(initial, mixed, 1002)
    completeBytes(0, "\ud83d\ude00", 4) shouldBe 4
    completeBytes(0, "\ud83d", 4) shouldBe CheckedScanRequired
    completeBytes(0, "\udc00", 4) shouldBe CheckedScanRequired
    completeBytes(0, "\ud83d\ude00", 3) shouldBe CheckedScanRequired
    completeEligible(Long.MaxValue, false, "", Int.MaxValue) shouldBe false
    completeBytes(Long.MaxValue, "a", Int.MaxValue) shouldBe CheckedScanRequired
  }
}
