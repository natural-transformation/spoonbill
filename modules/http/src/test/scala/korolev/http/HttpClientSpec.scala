package spoonbill.http

import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Source}
import java.io.ByteArrayInputStream
import java.net.{BindException, InetSocketAddress, URI}
import java.util.zip.GZIPInputStream
import spoonbill.data.Bytes
import spoonbill.data.syntax._
import spoonbill.effect.{Queue, Reporter, Stream}
import spoonbill.effect.Reporter.PrintReporter
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.{Headers, PathAndQuery, Request}
import spoonbill.web.PathAndQuery._
import spoonbill.web.Request.Method
import spoonbill.web.Response.Status
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.Future

class HttpClientSpec extends AsyncFreeSpec {

  import HttpClientSpec._

  "HttpClient" - {
    "should properly send GET (content-length: 0) requests" in withServer(helloWorldRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      new InetSocketAddress("localhost", port),
                      request = Request(Method.Get, Root / "hello", Nil, Some(0), Stream.empty[Future, Array[Byte]])
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body.contains("Hello world") && response.status == Status.Ok)
      }
    }

    "should properly send GET requests over TLS" in {
      for {
        client             <- HttpClient.create[Future, Array[Byte]]()
        // Use a stable public domain with a valid TLS certificate.
        response           <- client(Method.Get, URI.create("https://example.com/"), Seq.empty, None, Stream.empty)
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body.contains("Example Domain") && response.status == Status.Ok)
      }
    }

    "should receive gzipped bodies well" in withServer(gzippedRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      new InetSocketAddress("localhost", port),
                      request = Request(
                        Method.Get,
                        Root / "gz",
                        Vector(Headers.AcceptEncoding -> "gzip"),
                        Some(0),
                        Stream.empty[Future, Array[Byte]]
                      )
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = uncompressByteArray(strictResponseBody).asUtf8String
      } yield {
        assert(utf8Body.contains("Hello world") && response.status == Status.Ok)
      }
    }

    "should receive chunked bodies well" in withServer(chunkedRoute) { port =>
      for {
        client <- HttpClient.create[Future, Array[Byte]]()
        response <- client.http(
                      address = new InetSocketAddress("localhost", port),
                      request = Request(
                        Method.Get,
                        Root / "chunked",
                        Vector.empty,
                        Some(0),
                        Stream.empty[Future, Array[Byte]]
                      )
                    )
        strictResponseBody <- response.body.fold(Array.empty[Byte])(_ ++ _)
        utf8Body            = strictResponseBody.asUtf8String
      } yield {
        assert(utf8Body.contains("123") && response.status == Status.Ok)
      }
    }

    "should properly send/receive WebSocket frames" in withServer(wsEchoRoute) { port =>

      val wsSample1 = Frame.Text(Bytes.wrap("Hello!".getBytes))
      val wsSample2 = Frame.Text(Bytes.wrap("I'm cow!".getBytes))

      for {
        client <- HttpClient.create[Future, Bytes]()
        queue  <- Future.successful(Queue[Future, Frame[Bytes]]())
        response <- client.webSocket(
                      address = new InetSocketAddress("localhost", port),
                      path = (Root / "echo"): PathAndQuery,
                      outgoingFrames = queue.stream,
                      cookie = Map.empty,
                      headers = Map.empty
                    )
        _     <- queue.offer(wsSample1)
        echo1 <- response.body.pull()
        _     <- queue.offer(wsSample2)
        echo2 <- response.body.pull()
        _     <- response.body.cancel()
        _     <- queue.close()
      } yield {
        assert(echo1.contains(wsSample1) && echo2.contains(wsSample2))
      }
    }

  }

}

object HttpClientSpec {

  import akka.actor.typed.ActorSystem
  import akka.actor.typed.scaladsl.Behaviors
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server.Directives._

  // Http server stubs

  val helloWorldRoute: Route =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello world"))
      }
    }

  val gzippedRoute: Route =
    path("gz") {
      get {
        encodeResponse {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello world"))
        }
      }
    }

  val chunkedRoute: Route =
    path("chunked") {
      get {
        val xs = Vector(
          HttpEntity.ChunkStreamPart("1"),
          HttpEntity.ChunkStreamPart("2"),
          HttpEntity.ChunkStreamPart("3")
        )
        complete(HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source(xs)))
      }
    }

  val wsEchoRoute: Route = {
    def echo: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case _: BinaryMessage => Nil
        case tm: TextMessage =>
          TextMessage(tm.textStream) :: Nil
      }
    path("echo") {
      handleWebSocketMessages(echo)
    }
  }

  // Utils

  def uncompressByteArray(from: Array[Byte]): Array[Byte] = {
    val stream = new GZIPInputStream(new ByteArrayInputStream(from.asArray), from.length)
    val buffer = new Array[Byte](50)
    var result = new Array[Byte](0)
    var n      = 0
    while ({
      { n = stream.read(buffer) };
      n > 0
    }) {
      result = result ++ buffer.slice(0, n)
    }
    result
  }

  def withServer[T](route: Route)(f: Int => Future[T]): Future[T] = {

    implicit val system           = ActorSystem(Behaviors.empty, "http-client-test-system")
    implicit val executionContext = system.executionContext

    // Bind on port 0 so the OS picks a free ephemeral port.
    // This avoids flaky "Address already in use" failures caused by random-port selection.
    Http()
      .newServerAt("localhost", 0)
      .bind(route)
      .flatMap { binding =>
        val port = binding.localAddress.getPort
        f(port).transformWith { tryResult =>
          for {
            _      <- binding.unbind()
            _       = system.terminate()
            result <- Future.fromTry(tryResult)
          } yield result
        }
      }
  }
}
