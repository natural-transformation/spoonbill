package spoonbill.effect.io

import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, AsynchronousServerSocketChannel}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect

class RawDataSocketReadStateSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private def connected(run: (RawDataSocket[Future, Array[Byte]], Socket) => Unit): Unit = {
    val listener = AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))
    val peer = new Socket()
    var owned = Option.empty[RawDataSocket[Future, Array[Byte]]]
    try {
      val accepted = listener.accept()
      peer.connect(listener.getLocalAddress, 3000)
      peer.setSoTimeout(3000)
      val socket = new RawDataSocket[Future, Array[Byte]](
        accepted.get(3, TimeUnit.SECONDS), ByteBuffer.allocate(8), ByteBuffer.allocate(8)
      )
      owned = Some(socket)
      run(socket, peer)
    } finally {
      owned.foreach(socket => result(socket.shutdown()))
      peer.close()
      listener.close()
    }
  }

  "Raw socket read ownership" should "reject overlap and admit another read after the owner finishes" in {
    connected { (socket, peer) =>
      val first = socket.read(ByteBuffer.allocate(1))
      first.isCompleted shouldBe false
      intercept[IllegalStateException](result(socket.read(ByteBuffer.allocate(1))))
      peer.getOutputStream.write(7)
      result(first) shouldBe 1
      val next = socket.read(ByteBuffer.allocate(1))
      peer.getOutputStream.write(8)
      result(next) shouldBe 1
    }
  }

  it should "make EOF terminal and notify close observers once" in {
    connected { (socket, peer) =>
      val closed = new AtomicInteger()
      socket.subscribeClose(_ => { closed.incrementAndGet(); () })
      val first = socket.read(ByteBuffer.allocate(1))
      peer.shutdownOutput()
      result(first) shouldBe -1
      result(socket.read(ByteBuffer.allocate(1))) shouldBe -1
      result(socket.shutdown())
      closed.get() shouldBe 1
      socket.closeObserverCount shouldBe 0
    }
  }

  it should "keep cancellation closed when the previously admitted native read later completes" in {
    connected { (socket, peer) =>
      val closed = new AtomicInteger()
      socket.subscribeClose(_ => { closed.incrementAndGet(); () })
      val first = socket.read(ByteBuffer.allocate(1))
      result(socket.stream.cancel())
      result(socket.read(ByteBuffer.allocate(1))) shouldBe -1
      peer.getOutputStream.write(7)
      result(first) shouldBe 1
      result(socket.read(ByteBuffer.allocate(1))) shouldBe -1
      closed.get() shouldBe 1
    }
  }

  it should "close the channel before settling a pending read on physical shutdown" in {
    connected { (socket, peer) =>
      val closed = new AtomicInteger()
      socket.subscribeClose(_ => { closed.incrementAndGet(); () })
      val first = socket.read(ByteBuffer.allocate(1))
      result(socket.shutdown())
      result(first.failed) shouldBe a[AsynchronousCloseException]
      peer.getInputStream.read() shouldBe -1
      result(socket.read(ByteBuffer.allocate(1))) shouldBe -1
      closed.get() shouldBe 1
    }
  }
}
