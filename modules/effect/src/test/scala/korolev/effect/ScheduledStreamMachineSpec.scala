package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScheduledStreamMachineSpec extends AnyFlatSpec with Matchers {
  import ScheduledStreamMachine.*

  "Scheduled tick ownership" should "settle each registered ticket exactly once across either close order" in {
    for (fireFirst <- List(false, true)) {
      val first                = new Ticket
      val second               = new Ticket
      val registered           = register(register(initial, first)._1, second)._1
      val (beforeClose, fired) = if (fireFirst) settle(registered, first) else (registered, false)
      val (closed, canceled)   = close(beforeClose)
      (canceled ++ (if (fired) Set(first) else Set.empty)) shouldBe Set(first, second)
      if (fired) canceled should not contain first
      settle(closed, first) shouldBe (closed, false)
      settle(closed, second) shouldBe (closed, false)
      close(closed) shouldBe (closed, Set.empty)
      register(closed, new Ticket) shouldBe (closed, false)
    }
  }

  it should "ignore stale completion while another ticket remains pending" in {
    val first              = new Ticket
    val second             = new Ticket
    val registered         = register(register(initial, first)._1, second)._1
    val (remaining, fired) = settle(registered, first)
    fired shouldBe true
    settle(remaining, first) shouldBe (remaining, false)
    close(remaining)._2 shouldBe Set(second)
  }
}
