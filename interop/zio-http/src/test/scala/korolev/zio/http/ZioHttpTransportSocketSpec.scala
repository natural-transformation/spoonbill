package spoonbill.zio.http

import java.net.{Socket, SocketException}
import java.nio.charset.StandardCharsets
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Duration, Promise, Ref, Runtime, Task, Unsafe, ZIO}
import zio.http.*

final class ZioHttpTransportSocketSpec extends AnyFlatSpec with Matchers {
  private def connect(port: Int, path: String): Socket = {
    val socket = new Socket("127.0.0.1", port)
    socket.setSoTimeout(5000)
    socket.getOutputStream.write((
      s"GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
      "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: json\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII))
    socket.getOutputStream.flush()
    val headers = new StringBuilder
    while (!headers.toString.endsWith("\r\n\r\n")) {
      val next = socket.getInputStream.read()
      if (next == -1) throw new IllegalStateException("Upgrade failed")
      headers.append(next.toChar)
    }
    assert(headers.toString.startsWith("HTTP/1.1 101"))
    socket
  }

  /** Only EOF/reset at a frame boundary, or one bounded failure Close, is legal.
    * Every caller additionally awaits the server's close future BEFORE closing
    * this client socket. A reset by itself is never sufficient evidence.
    */
  private def expectClosed(socket: Socket, expectedCode: Int): Unit = {
    val input = socket.getInputStream
    def terminalRead(): Int =
      try input.read()
      catch {
        case error: SocketException if Option(error.getMessage).exists(_.toLowerCase.contains("connection reset")) => -2
      }
    val first = terminalRead()
    if (first >= 0) {
      first shouldBe 0x88 // final, unmasked Close; no application output
      val length = input.read()
      assert(length >= 2 && length <= 125, "Invalid server Close frame")
      val payload = input.readNBytes(length)
      payload.length shouldBe length
      ((payload(0) & 0xff) * 256 + (payload(1) & 0xff)) shouldBe expectedCode
      val terminal = terminalRead()
      assert(terminal == -1 || terminal == -2, "Multiple Close frames or application data after Close")
    }
  }

  private def witnessed(channel: WebSocketChannel, closed: Promise[Nothing, Unit])(body: Task[Unit]): Task[Unit] =
    ZIO.acquireReleaseWith((channel.awaitShutdown *> closed.succeed(()).unit).fork) { watcher =>
      // An exception can finish receiveAll before Netty finishes flushing its
      // Close. Keep the witness alive through that bounded shutdown interval.
      watcher.await.timeout(Duration.fromSeconds(3)).unit *> watcher.interrupt.unit
    }(_ => body)

  private def awaitClosed(closed: Promise[Nothing, Unit]): Task[Unit] =
    closed.await.timeoutFail(new RuntimeException("Server did not complete channel shutdown"))(Duration.fromSeconds(3))

  private def onSocket[A](port: Int, path: String)(body: Socket => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(connect(port, path)))(socket => ZIO.attemptBlocking(socket.close()).orDie)(body)

  private def echo(closed: Promise[Nothing, Unit]): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      witnessed(channel, closed) {
        channel.receiveAll {
          case ChannelEvent.Read(frame) => channel.send(ChannelEvent.Read(frame))
          case _ => ZIO.unit
        }
      }
    }.withConfig(new ZioHttpSpoonbill[Any].socketConfig(Some("json"), 64))

  "The installed ZIO transport" should "close a saturated pre-handler queue and preserve subsequent connections" in {
    val program = ZIO.scoped {
      for {
        consume <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        releaseCount <- Ref.make(0)
        saturatedClosed <- Promise.make[Nothing, Unit]
        echoClosed <- Promise.make[Nothing, Unit]
        fragmentClosed <- Promise.make[Nothing, Unit]
        utf8Closed <- Promise.make[Nothing, Unit]
        sizeClosed <- Promise.make[Nothing, Unit]
        paused = Handler.webSocket { channel =>
          witnessed(channel, saturatedClosed) {
            (consume.await *> channel.receiveAll {
              case ChannelEvent.ExceptionCaught(error) => ZIO.fail(error)
              case _ => ZIO.unit
            }).ensuring(releaseCount.update(_ + 1) *> released.succeed(()).unit)
          }
        }.withConfig(new ZioHttpSpoonbill[Any].socketConfig(Some("json"), 64))
        routes = Routes.singleton(Handler.fromFunctionZIO[(Path, Request)] { case (path, _) =>
          val socket = path.encode match {
            case "/paused" => paused
            case "/fragment" => echo(fragmentClosed)
            case "/utf8" => echo(utf8Closed)
            case "/size" => echo(sizeClosed)
            case _ => echo(echoClosed)
          }
          Response.fromSocketApp(socket)
        })
        port <- Server.install(routes)
        _ <- onSocket(port, "/paused") { socket =>
          ZIO.attemptBlocking {
            val frames = Array.fill(1000)(Array[Byte](0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 1)).flatten
            socket.getOutputStream.write(frames)
            socket.getOutputStream.flush()
            expectClosed(socket, 1013)
          } *> awaitClosed(saturatedClosed)
          // The witness must complete while both the handler gate AND this
          // client socket remain open. Client cleanup cannot create the proof.
        }.ensuring(consume.succeed(()).unit *> released.await.timeout(Duration.fromSeconds(3)).unit)
        _ <- released.await.timeoutFail(new RuntimeException("Terminal ingress did not settle"))(Duration.fromSeconds(3))
        count <- releaseCount.get
        _ <- ZIO.attempt(count shouldBe 1)
        _ <- onSocket(port, "/echo") { socket =>
          ZIO.attemptBlocking {
            socket.getOutputStream.write(Array[Byte](0x82.toByte, 0x81.toByte, 0, 0, 0, 0, 7))
            socket.getOutputStream.flush()
            socket.getInputStream.readNBytes(3).toSeq shouldBe Seq[Byte](0x82.toByte, 1, 7)
          }
        }
        _ <- ZIO.foreachDiscard(List(
          ("/fragment", Array[Byte](0x01), 1008, fragmentClosed),
          ("/utf8", Array[Byte](0x81.toByte, 0x82.toByte, 0, 0, 0, 0, 0xc3.toByte, 0x28), 1007, utf8Closed),
          ("/size", Array[Byte](0x82.toByte, 0xc1.toByte), 1009, sizeClosed)
        )) { case (path, frame, code, closed) =>
          onSocket(port, path) { socket =>
            ZIO.attemptBlocking {
              socket.getOutputStream.write(frame)
              socket.getOutputStream.flush()
              expectClosed(socket, code)
            } *> awaitClosed(closed)
          }
        }
      } yield ()
    }.provide(Server.defaultWith(_.onAnyOpenPort))
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(program).getOrThrowFiberFailure() }
  }
}
