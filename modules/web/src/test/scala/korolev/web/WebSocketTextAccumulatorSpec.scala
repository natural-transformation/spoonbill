package spoonbill.web

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WebSocketTextAccumulatorSpec extends AnyFlatSpec with Matchers {
  "WebSocket text accounting" should "count complete UTF-8 code points across every chunk boundary" in {
    val text = "a\u00e9\u20ac\ud83d\ude00"
    for (split <- 0 to text.length) {
      val accumulated = new WebSocketLimits.TextAccumulator(10)
      accumulated.append(text.substring(0, split)).append(text.substring(split)).result() shouldBe text
      intercept[WebSocketLimits.Exceeded] {
        new WebSocketLimits.TextAccumulator(9).append(text.substring(0, split)).append(text.substring(split))
      }
    }
  }

  it should "reserve a split surrogate's full byte cost and allow a rejected result to finish later" in {
    val accumulated = new WebSocketLimits.TextAccumulator(4)
    accumulated.append("\ud83d")
    intercept[IllegalArgumentException](accumulated.result())
    intercept[IllegalArgumentException](accumulated.append("x"))
    accumulated.append("").append("\ude00").result() shouldBe "\ud83d\ude00"
    intercept[WebSocketLimits.Exceeded](new WebSocketLimits.TextAccumulator(3).append("\ud83d"))
  }

  it should "reject malformed chunks before appending text without rolling back their counted prefix" in {
    val accumulated = new WebSocketLimits.TextAccumulator(3)
    accumulated.append("a")
    intercept[IllegalArgumentException](accumulated.append("b\udc00"))
    accumulated.result() shouldBe "a"
    accumulated.append("c").result() shouldBe "ac"
    intercept[WebSocketLimits.Exceeded](accumulated.append("d"))
    accumulated.result() shouldBe "ac"
  }

  it should "reject an oversized chunk before retaining its contents" in {
    val accumulated = new WebSocketLimits.TextAccumulator(4)
    accumulated.append("ok")
    intercept[WebSocketLimits.Exceeded](accumulated.append("x" * 10000))
    accumulated.result() shouldBe "ok"
    accumulated.append("").result() shouldBe "ok"
  }

  it should "reject a nonpositive byte budget" in {
    intercept[IllegalArgumentException](new WebSocketLimits.TextAccumulator(0))
    intercept[IllegalArgumentException](new WebSocketLimits.TextAccumulator(-1))
  }
}
