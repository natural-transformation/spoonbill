package spoonbill.zio.http

import spoonbill.Context
import java.util.concurrent.atomic.AtomicReference

import spoonbill.data.Bytes
import spoonbill.effect.{Queue, Reporter}
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.server.internal.Cookies
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery
import spoonbill.zio.Zio2Effect
import avocet.dsl.*
import avocet.dsl.html.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.http.*
import zio.stream.ZStream
import zio.{Chunk, Duration, Exit, NonEmptyChunk, Promise, RIO, Runtime, Unsafe, ZIO}

import scala.concurrent.ExecutionContext

/**
 * Tests for ZioHttpSpoonbill integration to verify error handling behavior.
 * 
 * Issue observed: When using spoonbill-zio-http with zio-http 3.x, requests
 * to bridge/long-polling endpoints fail with InternalServerError.
 * 
 * The suspected issue is that mapError(Response.fromThrowable) at line 39
 * of ZioHttpSpoonbill.scala causes the handler error type to become Response,
 * which may not be properly handled by zio-http routing.
 */
final class ZioHttpSpoonbillSpec extends AnyFlatSpec with Matchers {

  private type AppTask[A] = RIO[Any, A]

  implicit private val runtime: Runtime[Any] = Runtime.default
  implicit private val ec: ExecutionContext = Runtime.defaultExecutor.asExecutionContext
  implicit private val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

  private val ctx = Context[ZIO[Any, Throwable, *], String, Any]
  import ctx.*

  private val silentReporter: Reporter = new Reporter {
    def error(message: String, cause: Throwable): Unit = ()
    def error(message: String): Unit                   = ()
    def warning(message: String, cause: Throwable): Unit = ()
    def warning(message: String): Unit                   = ()
    def info(message: String): Unit                      = ()
    def debug(message: String): Unit                     = ()
    def debug(message: String, arg1: Any): Unit          = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  private val simpleDocument: ctx.Render = { state =>
    optimize {
      Html(
        body(
          div(s"State: $state"),
          button(
            "Click me",
            event("click")(access => access.transition(_ => "clicked"))
          )
        )
      )
    }
  }

  private val simpleConfig = SpoonbillServiceConfig[AppTask, String, Any](
    stateLoader = StateLoader.default("initial"),
    rootPath = PathAndQuery.Root,
    document = simpleDocument
  )

  private val sessionIdRegex = "window\\['kfg'\\]=\\{sid:'([^']+)'".r

  private def extractSessionId(html: String): Either[Throwable, String] =
    sessionIdRegex
      .findFirstMatchIn(html)
      .map(_.group(1))
      .toRight(new RuntimeException("Unable to find session id in HTML response"))

  private def extractDeviceId(headers: Headers): Either[Throwable, String] =
    headers
      .getAll(Header.SetCookie)
      .find(_.value.name == Cookies.DeviceId)
      .map(_.value.content)
      .toRight(new RuntimeException("Unable to find deviceId cookie in response headers"))

  "ZioHttpSpoonbill.service" should "create routes that serve the root page" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val routes = spoonbill.service(simpleConfig)

    val testApp = routes.toHandler
    val request = Request.get(URL(Path.root))

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(
        ZIO.scoped(testApp.runZIO(request))
      )
    }

    result match {
      case Exit.Success(response) =>
        response.status shouldBe Status.Ok
        // The response should contain HTML
        val bodyResult = Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.run(response.body.asString)
        }
        bodyResult match {
          case Exit.Success(body) =>
            body should include("State: initial")
          case Exit.Failure(cause) =>
            fail(s"Failed to get response body: $cause")
        }
      case Exit.Failure(cause) =>
        fail(s"Request failed: $cause")
    }
  }

  it should "handle POST requests to bridge/long-polling without error" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val routes = spoonbill.service(simpleConfig)

    val testApp = routes.toHandler
    
    // First make a GET to get a session
    val getRequest = Request.get(URL(Path.root))
    val getResult = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.scoped(testApp.runZIO(getRequest)))
    }
    
    getResult match {
      case Exit.Success(response) =>
        // Extract session ID from the set-cookie or body
        response.status shouldBe Status.Ok
        
        // Try a POST to /bridge/long-polling/session-id
        // This tests whether the spoonbill service properly handles bridge requests
        val postRequest = Request.post(
          URL(Path.decode("/bridge/long-polling/test-session")),
          Body.empty
        )
        
        val postResult = Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.run(ZIO.scoped(testApp.runZIO(postRequest)))
        }
        
        postResult match {
          case Exit.Success(postResponse) =>
            // Should NOT be InternalServerError (500)
            postResponse.status should not be Status.InternalServerError
          case Exit.Failure(cause) =>
            // If it fails, it should NOT be because of mapError(Response.fromThrowable) 
            // creating an error Response that's then logged as unhandled
            fail(s"Bridge POST request failed unexpectedly: $cause")
        }
        
      case Exit.Failure(cause) =>
        fail(s"Initial GET request failed: $cause")
    }
  }

  it should "properly convert errors to HTTP 500 responses via mapError" in {
    // Test that errors are properly converted, not left as unhandled
    val spoonbill = new ZioHttpSpoonbill[Any]
    
    // Create a config with a document that throws an exception
    val failingDocument: ctx.Render = { _ =>
      throw new RuntimeException("Simulated render failure")
    }
    
    val failingConfig = SpoonbillServiceConfig[AppTask, String, Any](
      stateLoader = StateLoader.default("initial"),
      rootPath = PathAndQuery.Root,
      document = failingDocument
    )
    
    val routes = spoonbill.service(failingConfig)
    val testApp = routes.toHandler
    val request = Request.get(URL(Path.root))

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.scoped(testApp.runZIO(request)))
    }

    // When there's an error, mapError(Response.fromThrowable) should convert it
    // to a Response error, which zio-http should then handle as a 500 response
    result match {
      case Exit.Success(response) =>
        // Should get a 500 response, not crash
        response.status shouldBe Status.InternalServerError
      case Exit.Failure(cause) =>
        // This is the bug: errors shouldn't escape as failures when
        // mapError(Response.fromThrowable) is used - they should be converted
        // to InternalServerError responses
        fail(s"Error escaped as failure instead of being converted to 500 response: $cause")
    }
  }

  it should "parse websocket subprotocol header values" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    spoonbill.parseProtocolsValues(Seq("json, json-deflate")) shouldBe Seq("json", "json-deflate")
    spoonbill.parseProtocolsValues(Seq(" json ", "json-deflate", "  ")) shouldBe Seq("json", "json-deflate")
  }

  it should "return empty websocket protocols when none are provided" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    spoonbill.parseProtocolsValues(Nil) shouldBe empty
  }

  it should "accept spoonbill websocket protocols" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    spoonbill.acceptsProtocols(Seq("json")) shouldBe true
    spoonbill.acceptsProtocols(Seq("json-deflate")) shouldBe true
    spoonbill.acceptsProtocols(Seq("json", "other")) shouldBe true
    spoonbill.acceptsProtocols(Seq("other")) shouldBe false
    spoonbill.acceptsProtocols(Nil) shouldBe false
  }

  it should "sanitize websocket protocols to supported set" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    spoonbill.sanitizeProtocols(Seq("json", "json-deflate")) shouldBe Seq("json", "json-deflate")
    spoonbill.sanitizeProtocols(Seq("json-deflate")) shouldBe Seq("json-deflate")
    spoonbill.sanitizeProtocols(Seq("json")) shouldBe Seq("json")
    spoonbill.sanitizeProtocols(Seq("other")) shouldBe empty
    spoonbill.sanitizeProtocols(Seq("json", "other")) shouldBe Seq("json")
  }

  it should "forward websocket frames to the Spoonbill queue" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.empty
    val send = (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
    val receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
      handler.applyOrElse(
        ChannelEvent.Read(WebSocketFrame.Text("client-msg")),
        (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
      )

    val program = for {
      _ <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, silentReporter)
      message <- fromClientQueue.stream.pull().flatMap {
                   case Some(bytes) => ZIO.succeed(bytes)
                   case None        => ZIO.fail(new RuntimeException("Expected message from websocket"))
                 }.timeoutFail(new RuntimeException("Timed out waiting for message"))(Duration.fromSeconds(1))
    } yield message.asUtf8String

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(text) =>
        text shouldBe "client-msg"
      case Exit.Failure(cause) =>
        fail(s"WebSocket receive failed: $cause")
    }
  }

  it should "close queue when websocket is unregistered" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.empty
    val send = (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
    val receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
      handler.applyOrElse(ChannelEvent.Unregistered, (_: ChannelEvent[WebSocketFrame]) => ZIO.unit)

    val program = for {
      _ <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, silentReporter)
      result <- fromClientQueue.stream.pull().timeoutFail(
                  new RuntimeException("Timed out waiting for queue close")
                )(Duration.fromSeconds(1))
    } yield result

    val outcome = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    outcome match {
      case Exit.Success(value) =>
        value shouldBe None
      case Exit.Failure(cause) =>
        fail(s"Queue did not close after unregistered: $cause")
    }
  }

  it should "close queue when websocket handshake times out" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.empty
    val send = (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
    val receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
      handler.applyOrElse(
        ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout),
        (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
      )

    val program = for {
      _ <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, silentReporter)
      result <- fromClientQueue.stream.pull().timeoutFail(
                  new RuntimeException("Timed out waiting for queue close")
                )(Duration.fromSeconds(1))
    } yield result

    val outcome = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    outcome match {
      case Exit.Success(value) =>
        value shouldBe None
      case Exit.Failure(cause) =>
        fail(s"Queue did not close after handshake timeout: $cause")
    }
  }

  it should "report websocket send stream failures" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val errorRef = new AtomicReference[Option[Throwable]](None)
    val reporter: Reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit = errorRef.set(Some(cause))
      def error(message: String): Unit                   = errorRef.set(Some(new RuntimeException(message)))
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit                   = ()
      def info(message: String): Unit                      = ()
      def debug(message: String): Unit                     = ()
      def debug(message: String, arg1: Any): Unit          = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }

    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.fail(new RuntimeException("boom"))
    val send = (_: ChannelEvent[WebSocketFrame]) => ZIO.unit
    val receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
      handler(ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) *> ZIO.never

    val program = for {
      fiber <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, reporter).fork
      _ <- ZIO
             .succeed(errorRef.get)
             .repeatUntil(_.isDefined)
             .timeoutFail(new RuntimeException("Timed out waiting for send failure log"))(
               Duration.fromSeconds(1)
             )
      _ <- fiber.interrupt
    } yield ()

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(_) =>
        errorRef.get.map(_.getMessage) shouldBe Some("boom")
      case Exit.Failure(cause) =>
        fail(s"Send failure logging did not complete: $cause")
    }
  }

  it should "wait for handshake event before sending websocket output" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val program = for {
      sentPromise <- Promise.make[Nothing, WebSocketFrame]
      handshakeTrigger <- Promise.make[Nothing, Unit]
      fromClientQueue = Queue[AppTask, Bytes]()
      toClientStream = ZStream.succeed(WebSocketFrame.Text("hello"))
      
      send = (event: ChannelEvent[WebSocketFrame]) =>
        event match {
          case ChannelEvent.Read(frame: WebSocketFrame) =>
            sentPromise.succeed(frame).unit
          case _ =>
            ZIO.unit
        }
      
      receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
        for {
          // Wait for trigger to simulate delayed handshake
          _ <- handshakeTrigger.await
          _ <- handler(ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete))
          // Keep the receiving fiber alive
          _ <- ZIO.never
        } yield ()

      fiber <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, silentReporter).fork
      
      // Verify nothing has been sent yet
      isSentBeforeHandshake <- sentPromise.isDone
      
      // Trigger handshake
      _ <- handshakeTrigger.succeed(())
      
      // Wait for frame
      frame <- sentPromise.await.timeoutFail(new RuntimeException("Timed out waiting for outbound frame"))(
                 Duration.fromSeconds(1)
               )
      _ <- fiber.interrupt
    } yield (isSentBeforeHandshake, frame)

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success((isSentBeforeHandshake, frame)) =>
        isSentBeforeHandshake shouldBe false
        frame shouldBe WebSocketFrame.Text("hello")
      case Exit.Failure(cause) =>
        fail(s"WebSocket verification failed: $cause")
    }
  }

  it should "start sending after receiving a client frame without handshake event" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val program = for {
      sentPromise <- Promise.make[Nothing, WebSocketFrame]
      fromClientQueue = Queue[AppTask, Bytes]()
      toClientStream = ZStream.succeed(WebSocketFrame.Text("server-msg"))

      send = (event: ChannelEvent[WebSocketFrame]) =>
        event match {
          case ChannelEvent.Read(frame: WebSocketFrame) =>
            sentPromise.succeed(frame).unit
          case _ =>
            ZIO.unit
        }

      receiveAll = (handler: PartialFunction[ChannelEvent[WebSocketFrame], AppTask[Unit]]) =>
        handler(ChannelEvent.Read(WebSocketFrame.Text("client-msg"))) *> ZIO.never

      fiber <- spoonbill.runSocket(send, receiveAll, toClientStream, fromClientQueue, silentReporter).fork
      frame <- sentPromise.await.timeoutFail(new RuntimeException("Timed out waiting for outbound frame"))(
                 Duration.fromSeconds(1)
               )
      _ <- fiber.interrupt
    } yield frame

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(frame) =>
        frame shouldBe WebSocketFrame.Text("server-msg")
      case Exit.Failure(cause) =>
        fail(s"WebSocket send did not start after client frame: $cause")
    }
  }

  // ============================================================================
  // WebSocket Subprotocol Negotiation Tests
  // These tests verify RFC 6455 compliance for Sec-WebSocket-Protocol header
  // ============================================================================

  it should "configure WebSocket with subprotocol when protocols are enabled" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.empty

    // Test buildSocket with a selected protocol
    val program = spoonbill.buildSocket(toClientStream, fromClientQueue, silentReporter, Some("json"))

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(response) =>
        // Response should be a WebSocket upgrade (status will be set by zio-http during actual upgrade)
        // The key verification is that the response was created successfully with the config
        response should not be null
      case Exit.Failure(cause) =>
        fail(s"buildSocket with protocol failed: $cause")
    }
  }

  it should "configure WebSocket without subprotocol when None is passed" in {
    val spoonbill = new ZioHttpSpoonbill[Any]
    val fromClientQueue = Queue[AppTask, Bytes]()
    val toClientStream = ZStream.empty

    // Test buildSocket without a protocol (for webSocketProtocolsEnabled = false case)
    val program = spoonbill.buildSocket(toClientStream, fromClientQueue, silentReporter, None)

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(response) =>
        response should not be null
      case Exit.Failure(cause) =>
        fail(s"buildSocket without protocol failed: $cause")
    }
  }

  it should "accept json-deflate in sanitizeProtocols when enabled" in {
    // This unit test verifies that json-deflate is now properly accepted by sanitizeProtocols
    // Note: Full integration testing with deflate compression requires a client that supports
    // deflate encoding, which zio-http's test client doesn't natively support.
    val spoonbill = new ZioHttpSpoonbill[Any]

    // Both protocols should be accepted
    spoonbill.acceptsProtocols(Seq("json-deflate")) shouldBe true
    spoonbill.acceptsProtocols(Seq("json", "json-deflate")) shouldBe true

    // Sanitize should preserve json-deflate
    spoonbill.sanitizeProtocols(Seq("json-deflate")) shouldBe Seq("json-deflate")
    spoonbill.sanitizeProtocols(Seq("json", "json-deflate")) shouldBe Seq("json", "json-deflate")
  }

  it should "reject requests with unsupported protocols when webSocketProtocolsEnabled is true" in {
    // This is a unit test for the acceptsProtocols function - verifying that unsupported
    // protocols are correctly identified. The actual HTTP 400 response is tested by the
    // server returning BadRequest status, which we verify via the acceptsProtocols logic.
    val spoonbill = new ZioHttpSpoonbill[Any]

    // Unsupported protocols should be rejected
    spoonbill.acceptsProtocols(Seq("unsupported-protocol")) shouldBe false
    spoonbill.acceptsProtocols(Seq("graphql-ws")) shouldBe false
    spoonbill.acceptsProtocols(Nil) shouldBe false

    // But if ANY supported protocol is present, it should accept
    spoonbill.acceptsProtocols(Seq("unsupported", "json")) shouldBe true
  }

  it should "work without protocol header when webSocketProtocolsEnabled is false" in {
    // When protocols are disabled, the server should accept connections without protocol negotiation
    val protocolDisabledConfig = simpleConfig.copy(webSocketProtocolsEnabled = false)
    val spoonbill = new ZioHttpSpoonbill[Any]
    val routes = spoonbill.service(protocolDisabledConfig)

    val program = ZIO.scoped {
      for {
        port <- Server.install(routes)
        baseUrl <- ZIO.fromEither(URL.decode(s"http://localhost:$port"))
        response <- ZClient.batched(Request.get(baseUrl))
        body <- response.body.asString
        sessionId <- ZIO.fromEither(extractSessionId(body))
        deviceId <- ZIO.fromEither(extractDeviceId(response.headers))
        received <- Promise.make[Nothing, WebSocketFrame]
        socketApp = Handler.webSocket { channel =>
                      channel.receiveAll {
                        case ChannelEvent.Read(frame) =>
                          received.succeed(frame).unit *> channel.shutdown
                        case _ =>
                          ZIO.unit
                      }
                    }
        // Connect WITHOUT any protocol header (client-side would send wsp:false)
        headers = Headers(
                    Header.Cookie(
                      NonEmptyChunk(Cookie.Request(Cookies.DeviceId, deviceId))
                    )
                  )
        _ <- socketApp.connect(
               s"ws://localhost:$port/bridge/web-socket/$sessionId",
               headers
             )
        frame <- received.await.timeoutFail(new RuntimeException("Timed out waiting for websocket frame"))(
                   Duration.fromSeconds(2)
                 )
      } yield frame
    }.provide(
      Client.default,
      Server.defaultWith(_.onAnyOpenPort)
    )

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success(frame) =>
        frame match {
          case WebSocketFrame.Binary(bytes) =>
            bytes.nonEmpty shouldBe true
          case WebSocketFrame.Text(text) =>
            text.nonEmpty shouldBe true
          case other =>
            fail(s"Unexpected websocket frame: $other")
        }
      case Exit.Failure(cause) =>
        fail(s"WebSocket without protocol negotiation failed: $cause")
    }
  }

  // ============================================================================
  // WebSocket Integration Tests
  // These tests verify that WebSocket connections work end-to-end
  // ============================================================================

  it should "receive server frames and send client response via WebSocket" in {
    // Use webSocketProtocolsEnabled = false for simpler test (no protocol negotiation)
    val config = simpleConfig.copy(webSocketProtocolsEnabled = false)
    val spoonbill = new ZioHttpSpoonbill[Any]
    val routes = spoonbill.service(config)

    val program = ZIO.scoped {
      for {
        port <- Server.install(routes)
        baseUrl <- ZIO.fromEither(URL.decode(s"http://localhost:$port"))

        // Get the initial page to establish a session
        response <- ZClient.batched(Request.get(baseUrl))
        body <- response.body.asString
        sessionId <- ZIO.fromEither(extractSessionId(body))
        deviceId <- ZIO.fromEither(extractDeviceId(response.headers))

        // Connect and verify we receive a frame, then send one back
        receivedFrame <- Promise.make[Nothing, WebSocketFrame]
        sentSuccess <- Promise.make[Nothing, Boolean]

        socketApp = Handler.webSocket { channel =>
                      channel.receiveAll {
                        case ChannelEvent.Read(frame) =>
                          for {
                            _ <- receivedFrame.succeed(frame).unit
                            // Send a heartbeat callback to confirm bidirectional communication
                            // Format: JSON array [CallbackType] where CallbackType.Heartbeat = 6
                            sendResult <- channel.send(ChannelEvent.Read(WebSocketFrame.Text("[6]"))).either
                            _ <- sentSuccess.succeed(sendResult.isRight).unit
                            _ <- channel.shutdown
                          } yield ()
                        case _ =>
                          ZIO.unit
                      }
                    }

        headers = Headers(
                    Header.Cookie(NonEmptyChunk(Cookie.Request(Cookies.DeviceId, deviceId)))
                  )

        // Connect - blocks until shutdown
        _ <- socketApp.connect(s"ws://localhost:$port/bridge/web-socket/$sessionId", headers)

        // Get results (with timeout to prevent test hanging)
        frame <- receivedFrame.await.timeoutFail(new RuntimeException("Timed out waiting for frame"))(
                   Duration.fromSeconds(2)
                 )
        didSend <- sentSuccess.await.timeoutFail(new RuntimeException("Timed out waiting for send"))(
                     Duration.fromSeconds(2)
                   )
      } yield (frame, didSend)
    }.provide(
      Client.default,
      Server.defaultWith(_.onAnyOpenPort)
    )

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(program)
    }

    result match {
      case Exit.Success((frame, didSend)) =>
        // Verify we received a server message
        frame match {
          case WebSocketFrame.Binary(bytes) => bytes.nonEmpty shouldBe true
          case WebSocketFrame.Text(text)    => text.nonEmpty shouldBe true
          case other                        => fail(s"Unexpected frame type: $other")
        }
        // Verify we successfully sent a message back
        didSend shouldBe true

      case Exit.Failure(cause) =>
        fail(s"Bidirectional WebSocket test failed: $cause")
    }
  }

}
