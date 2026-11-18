package spoonbill.internal

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future

class ExtensionStartupRaceSpec extends AnyFlatSpec with Matchers {
  "Extension startup" should "fold racing writes into ordered tails and deliver every message after the attach state" in {
    for (_ <- 1 to 100) {
      val f = new ExtensionRuntimeFixture()(f => List(f.recorder("observer")))
      f.result(f.setup(-1))
      f.taken()
      val ready   = new CountDownLatch(4)
      val race    = new CountDownLatch(1)
      val started = new CountDownLatch(1)
      val threads = (0 until 4).map { writer =>
        new Thread(() => {
          def write(index: Int): Unit = {
            f.runtime.state(writer * 1000 + index)
            f.runtime.message(s"$writer:$index")
          }
          (1 to 10).foreach(write)
          ready.countDown()
          assert(race.await(5, TimeUnit.SECONDS))
          (11 to 100).foreach(write)
          assert(started.await(5, TimeUnit.SECONDS))
          write(101)
        })
      }
      threads.foreach(_.start())
      ready.await(5, TimeUnit.SECONDS) shouldBe true
      race.countDown()
      // Do not drain the manual worker until every producer has finished.
      scala.concurrent.Await.result(f.runtime.start(), scala.concurrent.duration.Duration(5, "seconds"))
      started.countDown()
      threads.foreach(_.join(5000))
      threads.exists(_.isAlive) shouldBe false
      val calls = f.taken()
      calls.head should startWith("observer.onState(")
      val states =
        calls.filter(_.startsWith("observer.onState(")).map(_.stripPrefix("observer.onState(").stripSuffix(")").toInt)
      val early = states.filter(_ % 1000 <= 10)
      early.size should be <= 1
      early.headOption.foreach(_ shouldBe states.head)
      for (writer <- 0 until 4) {
        val own = states.filter(_ / 1000 == writer).map(_ % 1000)
        own shouldBe (own.head to 101).toVector
        val messages = calls.filter(_.startsWith(s"observer.onMessage($writer:"))
        messages shouldBe (1 to 101).map(i => s"observer.onMessage($writer:$i)").toVector
      }
      f.result(f.runtime.destroy())
      f.errors shouldBe empty
    }
  }
}
