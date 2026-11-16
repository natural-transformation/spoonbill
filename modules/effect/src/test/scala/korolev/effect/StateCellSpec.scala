package spoonbill.effect

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

class StateCellSpec extends AnyFlatSpec with Matchers {
  "A state cell" should "publish one immutable value and return the winning transition's result" in {
    val cell = new StateCell(Vector.empty[Int])
    cell.modify(xs => (xs :+ 1, xs.size)) shouldBe 0
    cell.get shouldBe Vector(1)
    intercept[IllegalArgumentException](cell.modify[Unit](_ => throw new IllegalArgumentException))
    cell.get shouldBe Vector(1)
  }

  it should "linearize contending updates without losing values or returning duplicate results" in {
    val cell    = new StateCell(0)
    val start   = new CountDownLatch(1)
    val done    = new CountDownLatch(8)
    val results = new ConcurrentLinkedQueue[Int]()
    val workers = (1 to 8).map { _ =>
      new Thread(() => {
        try {
          assert(start.await(5, TimeUnit.SECONDS))
          (1 to 5000).foreach(_ => results.add(cell.modify(n => (n + 1, n))))
        } finally done.countDown()
      })
    }
    workers.foreach(_.start())
    start.countDown()
    done.await(10, TimeUnit.SECONDS) shouldBe true
    workers.foreach(_.join(1000))
    cell.get shouldBe 40000
    results.asScala.toVector.sorted shouldBe (0 until 40000).toVector
  }
}
