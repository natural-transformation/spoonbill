package spoonbill.effect.io

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect

class SocketCloseReservationSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  "A close reservation" should "return an uninstalled permit once and reject later dispatch" in {
    var submitted = 0
    var returned = 0
    val reservation = new SocketCloseDispatcher.Reservation(_ => submitted += 1, () => returned += 1)
    reservation.cancel()
    reservation.cancel()
    reservation.connectionFinished()
    reservation.dispatch(_ => fail("Canceled reservation dispatched"))
    Await.result(reservation.awaitReleased[Future](), 5.seconds)
    submitted shouldBe 0
    returned shouldBe 1
  }

  it should "retain a dispatched permit until both completion owners finish in either order" in {
    for (connectionFirst <- List(false, true)) {
      var task = Option.empty[Runnable]
      var cleanup = Option.empty[() => Unit]
      var returned = 0
      val reservation = new SocketCloseDispatcher.Reservation(value => task = Some(value), () => returned += 1)
      if (connectionFirst) reservation.connectionFinished()
      reservation.dispatch(done => cleanup = Some(done))
      reservation.dispatch(_ => fail("Dispatched twice"))
      reservation.cancel() // Installation already owns this permit.
      task.get.run()
      returned shouldBe 0
      reservation.awaitReleased[Future]().isCompleted shouldBe false
      cleanup.get.apply()
      if (!connectionFirst) {
        returned shouldBe 0
        reservation.connectionFinished()
      }
      Await.result(reservation.awaitReleased[Future](), 5.seconds)
      cleanup.get.apply()
      reservation.connectionFinished()
      reservation.cancel()
      returned shouldBe 1
    }
  }

  it should "return once when both owners complete concurrently" in {
    for (_ <- 1 to 100) {
      val returned = new AtomicInteger()
      var cleanup = Option.empty[() => Unit]
      val reservation = new SocketCloseDispatcher.Reservation(_.run(), () => { returned.incrementAndGet(); () })
      reservation.dispatch(done => cleanup = Some(done))
      val start = new CountDownLatch(1)
      val done = new CountDownLatch(2)
      val workers = List[() => Unit](() => reservation.connectionFinished(), cleanup.get).map { finish =>
        new Thread(() => {
          try {
            assert(start.await(5, TimeUnit.SECONDS))
            (1 to 10).foreach(_ => finish())
          } finally done.countDown()
        })
      }
      workers.foreach(_.start())
      start.countDown()
      done.await(5, TimeUnit.SECONDS) shouldBe true
      workers.foreach(_.join(1000))
      Await.result(reservation.awaitReleased[Future](), 5.seconds)
      returned.get() shouldBe 1
    }
  }
}
