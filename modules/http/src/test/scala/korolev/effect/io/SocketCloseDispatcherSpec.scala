package spoonbill.effect.io

import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.Effect

final class SocketCloseDispatcherSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  "Close notification admission" should "bound queued callbacks without running them on the submitting thread" in {
    val dispatcher = new SocketCloseDispatcher(2, 1)
    val first = dispatcher.reserve().get
    val second = dispatcher.reserve().get
    val entered = new CountDownLatch(1)
    val unblock = new CountDownLatch(1)
    val secondRan = Promise[String]()
    try {
      first.dispatch { done =>
        entered.countDown()
        try unblock.await(5L, TimeUnit.SECONDS) finally done()
      }
      entered.await(2L, TimeUnit.SECONDS) shouldBe true
      second.dispatch { done => secondRan.success(Thread.currentThread().getName); done() }
      dispatcher.inUse shouldBe 2
      dispatcher.queued shouldBe 1
      dispatcher.reserve() shouldBe None
      secondRan.isCompleted shouldBe false
      first.connectionFinished()
      second.connectionFinished()
      dispatcher.stopAdmission()
      unblock.countDown()
      Await.result(first.awaitReleased[Future](), 3.seconds)
      Await.result(second.awaitReleased[Future](), 3.seconds)
      Await.result(secondRan.future, 3.seconds) should startWith("spoonbill-socket-close-")
      dispatcher.inUse shouldBe 0
      dispatcher.reserve() shouldBe None
    } finally { unblock.countDown(); dispatcher.stopAdmission() }
  }

  it should "retain admission through asynchronous cleanup and serving completion independently" in {
    val dispatcher = new SocketCloseDispatcher(1, 1)
    val reservation = dispatcher.reserve().get
    val started = Promise[() => Unit]()
    try {
      reservation.dispatch(done => started.success(done))
      val finishCleanup = Await.result(started.future, 3.seconds)
      reservation.connectionFinished()
      reservation.awaitReleased[Future]().isCompleted shouldBe false
      dispatcher.reserve() shouldBe None
      finishCleanup()
      finishCleanup() // Repeated cleanup notification does not return two slots.
      Await.result(reservation.awaitReleased[Future](), 3.seconds)
      dispatcher.inUse shouldBe 0
      val next = dispatcher.reserve().get
      next.dispatch(done => done())
      next.awaitReleased[Future]().isCompleted shouldBe false
      next.connectionFinished()
      Await.result(next.awaitReleased[Future](), 3.seconds)
      dispatcher.inUse shouldBe 0
    } finally dispatcher.stopAdmission()
  }

  it should "close native sockets before dispatching a blocked observer and retain asynchronous finalizers" in {
    val listener = AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))
    val dispatcher = new SocketCloseDispatcher(2, 1)
    val entered = new CountDownLatch(1)
    val unblock = new CountDownLatch(1)
    val cleanup = Promise[Unit]()
    var peers = List.empty[Socket]
    var sockets = List.empty[RawDataSocket[Future, Array[Byte]]]
    def connect(): (Socket, RawDataSocket[Future, Array[Byte]], SocketCloseDispatcher.Reservation) = {
      val accepted = listener.accept()
      val peer = new Socket()
      peers ::= peer
      peer.connect(listener.getLocalAddress, 2000)
      peer.setSoTimeout(2000)
      val socket = new RawDataSocket[Future, Array[Byte]](accepted.get(2L, TimeUnit.SECONDS),
        ByteBuffer.allocate(16), ByteBuffer.allocate(16))
      sockets ::= socket
      val reserved = dispatcher.reserve().get
      socket.admitCloseNotifications(reserved) shouldBe true
      (peer, socket, reserved)
    }
    try {
      val (firstPeer, first, firstReservation) = connect()
      val (secondPeer, second, secondReservation) = connect()
      first.subscribeClose { _ => entered.countDown(); unblock.await(5L, TimeUnit.SECONDS); () }
      Await.result(first.subscribeCloseEffect(_ => cleanup.future), 3.seconds)
      first.shutdownFromDeadline()
      entered.await(2L, TimeUnit.SECONDS) shouldBe true
      firstPeer.getInputStream.read() shouldBe -1
      second.shutdownFromDeadline()
      secondPeer.getInputStream.read() shouldBe -1
      firstReservation.connectionFinished()
      secondReservation.connectionFinished()
      dispatcher.reserve() shouldBe None
      unblock.countDown()
      Await.result(secondReservation.awaitReleased[Future](), 3.seconds)
      dispatcher.inUse shouldBe 1
      firstReservation.awaitReleased[Future]().isCompleted shouldBe false
      cleanup.success(())
      Await.result(firstReservation.awaitReleased[Future](), 3.seconds)
      dispatcher.inUse shouldBe 0
    } finally {
      unblock.countDown()
      cleanup.trySuccess(())
      peers.foreach(_.close())
      sockets.foreach(socket => Await.result(socket.shutdown(), 3.seconds))
      listener.close()
      dispatcher.stopAdmission()
    }
  }
}
