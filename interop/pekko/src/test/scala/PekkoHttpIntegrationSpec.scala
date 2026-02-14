/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.pekko

import spoonbill.Context
import spoonbill.effect.Reporter
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery
import avocet.dsl.*
import avocet.dsl.html.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.{headers => pekkoHeaders}
import org.apache.pekko.http.scaladsl.model.ws.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/**
 * End-to-end integration tests for Pekko HTTP WebSocket support.
 * These tests verify that the Spoonbill service properly handles WebSocket connections.
 */
final class PekkoHttpIntegrationSpec extends AnyFreeSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(1, Seconds))

  implicit val system: ActorSystem        = ActorSystem("test-system")
  implicit val materializer: Materializer = Materializer(system)
  implicit val ec: ExecutionContext       = system.dispatcher

  private val ctx = Context[Future, String, Any]
  import ctx.*

  private val silentReporter: Reporter = new Reporter {
    def error(message: String, cause: Throwable): Unit                     = ()
    def error(message: String): Unit                                       = ()
    def warning(message: String, cause: Throwable): Unit                   = ()
    def warning(message: String): Unit                                     = ()
    def info(message: String): Unit                                        = ()
    def debug(message: String): Unit                                       = ()
    def debug(message: String, arg1: Any): Unit                            = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit                 = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit      = ()
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

  private val simpleConfig = SpoonbillServiceConfig[Future, String, Any](
    stateLoader = StateLoader.default("initial"),
    rootPath = PathAndQuery.Root,
    document = simpleDocument,
    reporter = silentReporter
  )

  private val pekkoHttpConfig = PekkoHttpServerConfig()

  private val sessionIdRegex = "window\\['kfg'\\]=\\{sid:'([^']+)'".r
  private val deviceIdRegex  = "deviceId=([^;]+)".r

  private def extractSessionId(html: String): Option[String] =
    sessionIdRegex.findFirstMatchIn(html).map(_.group(1))

  private def extractDeviceId(headers: Seq[HttpHeader]): Option[String] =
    headers
      .find(_.lowercaseName() == "set-cookie")
      .flatMap(h => deviceIdRegex.findFirstMatchIn(h.value()).map(_.group(1)))

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()
  }

  "PekkoHttpService" - {

    "should serve the root page with session information" in {
      val service = pekkoHttpService(simpleConfig)
      val route: Route = service(pekkoHttpConfig)
      val bindingFuture = Http().newServerAt("localhost", 0).bind(route)

      val result = for {
        binding <- bindingFuture
        port = binding.localAddress.getPort
        response <- Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/"))
        body     <- response.entity.toStrict(5.seconds).map(_.data.utf8String)
        _        <- binding.unbind()
      } yield (response.status, body)

      val (status, body) = result.futureValue

      status shouldBe StatusCodes.OK
      body should include("State: initial")
      extractSessionId(body) should not be empty
    }

    "should establish WebSocket connection and receive server frames" in {
      val service = pekkoHttpService(simpleConfig)
      val route: Route = service(pekkoHttpConfig)
      val bindingFuture = Http().newServerAt("localhost", 0).bind(route)

      val result = for {
        binding <- bindingFuture
        port = binding.localAddress.getPort

        // Step 1: Get session ID and device ID from the initial page
        response <- Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/"))
        body     <- response.entity.toStrict(5.seconds).map(_.data.utf8String)
        sessionId = extractSessionId(body).get
        deviceId  = extractDeviceId(response.headers.toSeq).get

        // Step 2: Create WebSocket connection
        receivedMessage = Promise[Message]()
        sentResponse    = Promise[Unit]()

        // WebSocket flow: receive first message, send heartbeat response, complete
        wsFlow = Flow[Message]
                   .take(1)
                   .map { msg =>
                     receivedMessage.success(msg)
                     // Send heartbeat callback: JSON array [CallbackType.Heartbeat]
                     TextMessage("[6]")
                   }
                   .watchTermination() { (_, done) =>
                     done.foreach(_ => sentResponse.trySuccess(()))
                     done
                   }

        wsRequest = WebSocketRequest(
                      uri = s"ws://localhost:$port/bridge/web-socket/$sessionId",
                      extraHeaders = List(
                        pekkoHeaders.Cookie("deviceId" -> deviceId),
                        pekkoHeaders.RawHeader("Sec-WebSocket-Protocol", "json")
                      ),
                      subprotocol = Some("json")
                    )

        wsResult = Http().singleWebSocketRequest(wsRequest, wsFlow)
        upgrade <- wsResult._1

        // Wait for message to be received
        message <- receivedMessage.future

        _ <- binding.unbind()
      } yield (upgrade.response.status, message)

      val (status, message) = result.futureValue

      // WebSocket upgrade should succeed
      status shouldBe StatusCodes.SwitchingProtocols

      // Should receive a binary or text message from the server
      message match {
        case TextMessage.Strict(text)   => text.nonEmpty shouldBe true
        case BinaryMessage.Strict(data) => data.nonEmpty shouldBe true
        case _: TextMessage.Streamed    => succeed // streamed is also valid
        case _: BinaryMessage.Streamed  => succeed // streamed is also valid
      }
    }

    "should support bidirectional WebSocket communication" in {
      val service = pekkoHttpService(simpleConfig)
      val route: Route = service(pekkoHttpConfig)
      val bindingFuture = Http().newServerAt("localhost", 0).bind(route)

      val result = for {
        binding <- bindingFuture
        port = binding.localAddress.getPort

        // Get session
        response <- Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/"))
        body     <- response.entity.toStrict(5.seconds).map(_.data.utf8String)
        sessionId = extractSessionId(body).get
        deviceId  = extractDeviceId(response.headers.toSeq).get

        // Track received messages and sent status
        receivedMessages = scala.collection.mutable.ListBuffer[String]()
        sentHeartbeat    = Promise[Unit]()

        // WebSocket flow: collect messages, send heartbeat after first message
        wsFlow = Flow[Message]
                   .mapAsync(1) {
                     case TextMessage.Strict(text) =>
                       Future.successful(text)
                     case TextMessage.Streamed(stream) =>
                       stream.runFold("")(_ + _)
                     case BinaryMessage.Strict(data) =>
                       Future.successful(data.utf8String)
                     case BinaryMessage.Streamed(stream) =>
                       stream.runFold(org.apache.pekko.util.ByteString.empty)(_ ++ _).map(_.utf8String)
                   }
                   .take(2) // Take up to 2 messages then complete
                   .map { text =>
                     receivedMessages += text
                     if (receivedMessages.size == 1) {
                       sentHeartbeat.success(())
                     }
                     // Send heartbeat response
                     TextMessage("[6]")
                   }

        wsRequest = WebSocketRequest(
                      uri = s"ws://localhost:$port/bridge/web-socket/$sessionId",
                      extraHeaders = List(
                        pekkoHeaders.Cookie("deviceId" -> deviceId),
                        pekkoHeaders.RawHeader("Sec-WebSocket-Protocol", "json")
                      ),
                      subprotocol = Some("json")
                    )

        wsResult = Http().singleWebSocketRequest(wsRequest, wsFlow)
        upgrade <- wsResult._1

        // Wait for heartbeat to be sent (confirms bidirectional communication)
        _ <- sentHeartbeat.future

        _ <- binding.unbind()
      } yield (upgrade.response.status, receivedMessages.toList)

      val (status, messages) = result.futureValue

      status shouldBe StatusCodes.SwitchingProtocols
      assert(messages.nonEmpty, "Expected to receive at least one message")
      // First message should contain server commands (non-empty JSON array)
      assert(messages.head.nonEmpty, "First message should not be empty")
    }

    "should reject WebSocket connections with unsupported protocols" in {
      val service = pekkoHttpService(simpleConfig)
      val route: Route = service(pekkoHttpConfig)
      val bindingFuture = Http().newServerAt("localhost", 0).bind(route)

      val result = for {
        binding <- bindingFuture
        port = binding.localAddress.getPort

        // Get session first
        response <- Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/"))
        body     <- response.entity.toStrict(5.seconds).map(_.data.utf8String)
        sessionId = extractSessionId(body).get
        deviceId  = extractDeviceId(response.headers.toSeq).get

        // Try to connect with unsupported protocol
        wsFlow = Flow[Message].map(identity)

        wsRequest = WebSocketRequest(
                      uri = s"ws://localhost:$port/bridge/web-socket/$sessionId",
                      extraHeaders = List(
                        pekkoHeaders.Cookie("deviceId" -> deviceId),
                        pekkoHeaders.RawHeader("Sec-WebSocket-Protocol", "unsupported-protocol")
                      ),
                      subprotocol = Some("unsupported-protocol")
                    )

        wsResult = Http().singleWebSocketRequest(wsRequest, wsFlow)
        upgrade <- wsResult._1

        _ <- binding.unbind()
      } yield upgrade.response.status

      val status = result.futureValue

      // Should reject with BadRequest since protocol is not supported
      status shouldBe StatusCodes.BadRequest
    }
  }
}
