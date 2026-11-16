package spoonbill.http

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, AsynchronousSocketChannel}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.Effect
import spoonbill.effect.io.{RawDataSocket, SocketCloseDispatcher}

class HttpClientClosedSetupSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private given ExecutionContext            = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  "Connection setup on an already closed transport" should "report closure rather than a deadline that has not elapsed" in {
    val channel     = AsynchronousSocketChannel.open()
    val raw         = new RawDataSocket[Future, Bytes](channel, ByteBuffer.allocate(128), ByteBuffer.allocate(128))
    val dispatcher  = new SocketCloseDispatcher(1, 1)
    val reservation = dispatcher.reserve().get
    raw.admitCloseNotifications(reservation) shouldBe true
    reservation.connectionFinished()
    try {
      await(raw.shutdown())
      await(reservation.awaitReleased[Future]())
      // Late subscription invokes the close callback inline. Time never advances.
      intercept[AsynchronousCloseException] {
        await(HttpClient.withSetupDeadline(raw, reservation, 1.minute, () => 0L)(Future.unit))
      }
      channel.isOpen shouldBe false
      val replacement = dispatcher.reserve().get
      replacement.cancel()
    } finally {
      await(raw.shutdown())
      await(reservation.awaitReleased[Future]())
      dispatcher.stopAdmission()
    }
  }
}
