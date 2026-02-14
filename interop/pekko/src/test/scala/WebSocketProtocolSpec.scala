package spoonbill.pekko

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class WebSocketProtocolSpec extends AnyFreeSpec with Matchers {

  "acceptsProtocols" - {
    "accepts spoonbill subprotocols" in {
      acceptsProtocols(Seq("json")) shouldBe true
      acceptsProtocols(Seq("json-deflate")) shouldBe true
      acceptsProtocols(Seq("json", "other")) shouldBe true
    }

    "rejects unsupported or missing protocols" in {
      acceptsProtocols(Seq("other")) shouldBe false
      acceptsProtocols(Nil) shouldBe false
    }
  }
}
