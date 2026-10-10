package spoonbill.zio.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.data.Bytes
import spoonbill.effect.{Queue, Reporter}
import spoonbill.zio.Zio2Effect
import zio.http.{ChannelEvent, WebSocketFrame}
import zio.stream.ZStream
import zio.{Duration, Exit, Promise, RIO, Ref, Runtime, Unsafe, ZIO}

final class ZioHttpSocketOwnershipSpec extends AnyFlatSpec with Matchers {
  private type Task[A] = RIO[Any, A]
  implicit private val effect: Zio2Effect[Any, Throwable] =
    new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private val adapter = new ZioHttpSpoonbill[Any]
  private val reporter = Reporter.PrintReporter

  private def check[A](program: Task[A], expected: A): Unit = {
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(program.timeoutFail(new RuntimeException("Socket lifecycle did not finish"))(Duration.fromSeconds(3)))
    }
    result shouldBe Exit.succeed(expected)
  }

  "Duplex input cancellation" should "suppress output already canceled before handler attachment" in {
    val program = for {
      cancelled <- Promise.make[Nothing, Unit]
      pulls <- Ref.make(0)
      releases <- Ref.make(0)
      aborted <- Ref.make(false)
      _ <- cancelled.succeed(())
      input = Queue[Task, Bytes]()
      _ <- adapter.runSocket(
             _ => ZIO.unit,
             _ => ZIO.never,
             ZStream.fromZIO(pulls.update(_ + 1).as(WebSocketFrame.Text("forbidden"))),
             input,
             reporter,
             release = () => releases.update(_ + 1),
             inputCancelled = Some(cancelled),
             onAbort = aborted.set(true)
           )
      count <- pulls.get
      releaseCount <- releases.get
      didAbort <- aborted.get
      ended <- input.stream.pull()
    } yield (count, releaseCount, didAbort, ended)
    check(program, (0, 1, true, None))
  }

  it should "stop an active socket and settle pending input reads" in {
    val program = for {
      cancelled <- Promise.make[Nothing, Unit]
      attached <- Promise.make[Nothing, Unit]
      releases <- Ref.make(0)
      input = Queue[Task, Bytes]()
      read <- input.stream.pull().fork
      socket <- adapter.runSocket(
                  _ => ZIO.unit,
                  handle => handle(ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) *>
                    attached.succeed(()).unit *> ZIO.never,
                  ZStream.never,
                  input,
                  reporter,
                  release = () => releases.update(_ + 1),
                  inputCancelled = Some(cancelled)
                ).fork
      _ <- attached.await
      _ <- cancelled.succeed(())
      _ <- socket.join
      ended <- read.join
      releaseCount <- releases.get
    } yield (ended, releaseCount)
    check(program, (None, 1))
  }

  "SendThenClose" should "deliver all frames despite canceled application input" in {
    val program = for {
      cancelled <- Promise.make[Nothing, Unit]
      closed <- Promise.make[Nothing, Unit]
      sent <- Ref.make(Vector.empty[WebSocketFrame])
      releases <- Ref.make(0)
      _ <- cancelled.succeed(())
      _ <- adapter.runSocket(
             { case ChannelEvent.Read(frame) => sent.update(_ :+ frame); case _ => ZIO.unit },
             handle => handle(ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) *> closed.await,
             ZStream.fromIterable(List(WebSocketFrame.Text("one"), WebSocketFrame.Text("two"))),
             Queue[Task, Bytes](),
             reporter,
             onOutputComplete = closed.succeed(()).unit,
             discardInbound = true,
             release = () => releases.update(_ + 1),
             inputCancelled = Some(cancelled)
           )
      frames <- sent.get
      releaseCount <- releases.get
    } yield (frames, releaseCount)
    check(program, (Vector(WebSocketFrame.Text("one"), WebSocketFrame.Text("two")), 1))
  }

  "Output failure" should "abort without a normal completion signal" in {
    val program = for {
      aborted <- Promise.make[Nothing, Unit]
      normalCloses <- Ref.make(0)
      releases <- Ref.make(0)
      _ <- adapter.runSocket(
             _ => ZIO.unit,
             handle => handle(ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) *> aborted.await,
             ZStream.fail(new IllegalStateException("encoding failed")),
             Queue[Task, Bytes](),
             reporter,
             onOutputComplete = normalCloses.update(_ + 1),
             release = () => releases.update(_ + 1),
             onAbort = aborted.succeed(()).unit
           )
      normal <- normalCloses.get
      releaseCount <- releases.get
    } yield (normal, releaseCount)
    check(program, (0, 1))
  }
}
