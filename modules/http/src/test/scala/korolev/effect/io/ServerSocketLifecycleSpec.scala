package spoonbill.effect.io

import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.Effect

final class ServerSocketLifecycleSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def connect(address: java.net.SocketAddress): Socket = {
    val socket = new Socket()
    socket.setSoTimeout(3000)
    socket.connect(address, 3000)
    socket
  }

  "Graceful connection tracking" should "retain only an active count and one failure after many completed handlers" in {
    val tracker = new ServerSocket.ActiveConnections
    for (_ <- 0 until 10000) { tracker.started(); tracker.finished(None) }
    tracker.retainedState shouldBe (0 -> 0)
    val first = new IllegalStateException("First handler failure")
    for (index <- 0 until 10000) {
      tracker.started()
      tracker.finished(Some(if (index == 0) first else new IllegalArgumentException("Later failure")))
    }
    tracker.retainedState shouldBe (0 -> 1)
    tracker.started()
    tracker.stopped(None)
    val stopped = tracker.awaitFinished[Future]()
    stopped.isCompleted shouldBe false
    tracker.finished(None)
    intercept[IllegalStateException](await(stopped)) should be theSameInstanceAs first
    tracker.retainedState shouldBe (0 -> 1)
  }

  it should "wait for the remaining live socket before propagating an earlier handler failure" in {
    val count = new AtomicInteger()
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val expected = new IllegalStateException("Synthetic handler failure")
    val server = await(ServerSocket.accept[Future, Bytes](new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = true) { connection =>
      count.getAndIncrement() match {
        case index if index < 128 => connection.shutdown()
        case 128 =>
          entered.trySuccess(())
          release.future.flatMap(_ => connection.shutdown())
        case _ => Future.failed(expected)
      }
    })
    var held: Socket = null
    try {
      for (_ <- 0 until 128) {
        val socket = connect(server.localAddress)
        try socket.getInputStream.read() shouldBe -1
        finally socket.close()
      }
      held = connect(server.localAddress)
      await(entered.future)
      val failed = connect(server.localAddress)
      try failed.getInputStream.read() shouldBe -1
      finally failed.close()
      await(server.stopServingRequests())
      val stopped = server.awaitShutdown()
      stopped.isCompleted shouldBe false
      release.success(())
      intercept[IllegalStateException](await(stopped)) should be theSameInstanceAs expected
      held.getInputStream.read() shouldBe -1
      count.get() shouldBe 130
    } finally {
      release.trySuccess(())
      if (held != null) held.close()
      await(server.stopServingRequests())
    }
  }

  "Non-graceful shutdown" should "finish listening without retaining or awaiting a live handler" in {
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val finished = Promise[Unit]()
    val server = await(ServerSocket.accept[Future, Bytes](new InetSocketAddress("127.0.0.1", 0)) { connection =>
      entered.trySuccess(())
      release.future.flatMap(_ => connection.shutdown()).map { _ => finished.trySuccess(()); () }
    })
    val socket = connect(server.localAddress)
    try {
      await(entered.future)
      await(server.stopServingRequests())
      await(server.awaitShutdown())
      finished.isCompleted shouldBe false
      release.success(())
      await(finished.future)
      socket.getInputStream.read() shouldBe -1
    } finally {
      release.trySuccess(())
      socket.close()
      await(server.stopServingRequests())
    }
  }

  Seq(false, true).foreach { constructionFailure =>
    "Connection startup" should s"close and settle a failed launch when handler construction throws=$constructionFailure" in {
      val expected = new IllegalStateException("Synthetic startup failure")
      val refuse = new AtomicBoolean(!constructionFailure)
      given Effect[Future] = new Effect.FutureEffect {
        override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit =
          if (refuse.compareAndSet(true, false)) throw expected
          else super.runAsync(value)(callback)
      }
      val server = await(ServerSocket.accept[Future, Bytes](new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = true) { _ =>
        if (constructionFailure) throw expected else Future.unit
      })
      val socket = connect(server.localAddress)
      try {
        socket.getInputStream.read() shouldBe -1
        await(server.stopServingRequests())
        intercept[IllegalStateException](await(server.awaitShutdown())) should be theSameInstanceAs expected
      } finally {
        socket.close()
        await(server.stopServingRequests())
      }
    }
  }

  it should "release an installed reservation only after a failed launch and asynchronous close observers settle" in {
    val expected = new IllegalStateException("Runtime refused handler ownership")
    val unstarted: Future[Unit] = Future.never
    given Effect[Future] = new Effect.FutureEffect {
      override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit =
        if (value.asInstanceOf[AnyRef] eq unstarted.asInstanceOf[AnyRef]) throw expected
        else super.runAsync(value)(callback)
    }
    val dispatcher = new SocketCloseDispatcher(1, 1)
    val installed = Promise[SocketCloseDispatcher.Reservation]()
    val observing = Promise[Unit]()
    val finishObserver = Promise[Unit]()
    val server = await(ServerSocket.accept[Future, Bytes](new InetSocketAddress("127.0.0.1", 0), gracefulShutdown = true) { connection =>
      val reservation = dispatcher.reserve().get
      connection.admitCloseNotifications(reservation) shouldBe true
      await(connection.subscribeCloseEffect { _ => observing.trySuccess(()); finishObserver.future })
      installed.success(reservation)
      unstarted
    })
    val socket = connect(server.localAddress)
    try {
      socket.getInputStream.read() shouldBe -1
      val reservation = await(installed.future)
      await(observing.future)
      dispatcher.inUse shouldBe 1
      dispatcher.reserve() shouldBe None
      await(server.stopServingRequests())
      val stopped = server.awaitShutdown()
      stopped.isCompleted shouldBe false
      finishObserver.success(())
      await(reservation.awaitReleased[Future]())
      dispatcher.inUse shouldBe 0
      intercept[IllegalStateException](await(stopped)) should be theSameInstanceAs expected
    } finally {
      finishObserver.trySuccess(())
      socket.close()
      await(server.stopServingRequests())
      dispatcher.stopAdmission()
    }
  }
}
