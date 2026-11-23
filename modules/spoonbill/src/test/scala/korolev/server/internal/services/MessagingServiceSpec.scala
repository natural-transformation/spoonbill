package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class MessagingServiceSpec extends AnyFlatSpec with Matchers {

  "WebSocket protocol selection" should "prefer plain JSON by default even when deflate is offered" in {
    MessagingService.selectedProtocol(Seq("json", "json-deflate"), compressionEnabled = false) shouldBe "json"
  }

  it should "negotiate deflate only when explicitly enabled" in {
    MessagingService.selectedProtocol(Seq("json", "json-deflate"), compressionEnabled = true) shouldBe "json-deflate"
  }

  // The long-polling routes are covered by LongPollingDeliverySpec. A message
  // never makes a topic entry any more, so the orphan timer and its cases went.
}
