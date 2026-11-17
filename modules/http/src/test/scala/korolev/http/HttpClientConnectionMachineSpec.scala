package spoonbill.http

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class HttpClientConnectionMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import HttpClientConnectionMachine.*

  "Client exchange ownership" should "fence stale completion and keep physical close terminal" in {
    val first = new Owner
    val second = new Owner
    val borrowed = begin(initial, first)
    canBegin(borrowed) shouldBe false
    finish(borrowed, second) shouldBe borrowed
    val next = begin(finish(borrowed, first), second)
    canFinish(next, first) shouldBe false
    canFinish(next, second) shouldBe true
    val closed = close(next)
    canBegin(closed) shouldBe false
    canFinish(closed, second) shouldBe false
    finish(closed, second) shouldBe closed
    begin(closed, first) shouldBe closed
    close(closed) shouldBe closed
  }

  it should "match the previous monitor rules over generated exchange and close histories" in {
    val owners = Vector(new Owner, new Owner)
    forAll(Gen.listOf(Gen.choose(0, 4))) { events =>
      events.foldLeft((initial, false, Option.empty[Owner])) { case ((state, closed, active), event) =>
        canBegin(state) shouldBe (!closed && active.isEmpty)
        owners.foreach(owner => canFinish(state, owner) shouldBe (!closed && active.contains(owner)))
        event match {
          case 0 | 1 =>
            val next = if (!closed && active.isEmpty) Some(owners(event)) else active
            (begin(state, owners(event)), closed, next)
          case 2 | 3 =>
            val owner = owners(event - 2)
            val next = if (!closed && active.contains(owner)) None else active
            (finish(state, owner), closed, next)
          case _ => (close(state), true, None)
        }
      }
    }
  }
}
