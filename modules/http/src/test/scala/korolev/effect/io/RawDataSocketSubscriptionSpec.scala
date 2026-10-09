package spoonbill.effect.io

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect

final class RawDataSocketSubscriptionSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private def withSocket(f: RawDataSocket[Future, Array[Byte]] => Unit): Unit = {
    val group = AsynchronousChannelGroup.withFixedThreadPool(1, Executors.defaultThreadFactory())
    val channel = AsynchronousSocketChannel.open(group)
    val socket = new RawDataSocket[Future, Array[Byte]](channel, ByteBuffer.allocate(8), ByteBuffer.allocate(8))
    try f(socket)
    finally {
      channel.close()
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
    }
  }

  "Socket close subscriptions" should "remove repeated rejected-upgrade observers before the connection closes" in {
    withSocket { socket =>
      val called = new AtomicInteger()
      for (_ <- 0 until 1000) {
        val detach = socket.subscribeClose(_ => { called.incrementAndGet(); () })
        socket.closeObserverCount shouldBe 1
        detach()
        detach()
        socket.closeObserverCount shouldBe 0
      }
      Await.result(socket.shutdown(), 3.seconds)
      called.get() shouldBe 0
    }
  }

  it should "release observer storage when close wins and notify only once" in {
    withSocket { socket =>
      val called = new AtomicInteger()
      val detach = socket.subscribeClose(_ => { called.incrementAndGet(); () })
      Await.result(socket.shutdown(), 3.seconds)
      detach()
      Await.result(socket.shutdown(), 3.seconds)
      called.get() shouldBe 1
      socket.closeObserverCount shouldBe 0
      socket.subscribeClose(_ => { called.incrementAndGet(); () })()
      called.get() shouldBe 2
      socket.closeObserverCount shouldBe 0
    }
  }
}
