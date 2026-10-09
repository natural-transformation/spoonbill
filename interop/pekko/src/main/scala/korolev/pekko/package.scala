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

package spoonbill

import spoonbill.data.{Bytes, BytesLike}
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.effect.syntax.*
import spoonbill.pekko.util.LoggingReporter
import spoonbill.server.{HttpRequest as SpoonbillHttpRequest, SpoonbillService, SpoonbillServiceConfig}
import spoonbill.server.{WebSocketRequest as SpoonbillWebSocketRequest, WebSocketResponse as SpoonbillWebSocketResponse}
import spoonbill.server.internal.BadRequestException
import spoonbill.state.{StateDeserializer, StateSerializer}
import spoonbill.web.{PathAndQuery, Request as SpoonbillRequest, Response as SpoonbillResponse}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{RawHeader, `Timeout-Access`}
import org.apache.pekko.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketUpgrade}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink}
import org.apache.pekko.util.ByteString
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

package object pekko {

  type PekkoHttpService = PekkoHttpServerConfig => Route

  import instances._

  private val SupportedProtocols = Set("json", "json-deflate")

  private[pekko] def acceptsProtocols(protocols: Seq[String]): Boolean =
    protocols.exists(SupportedProtocols.contains)

  def pekkoHttpService[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
    config: SpoonbillServiceConfig[F, S, M],
    wsLoggingEnabled: Boolean = false
  )(implicit actorSystem: ActorSystem, materializer: Materializer, ec: ExecutionContext): PekkoHttpService = {
    pekkoHttpConfig =>
      // If reporter wasn't overridden, use pekko-logging reporter.
      val actualConfig =
        if (config.reporter != Reporter.PrintReporter) config
        else config.copy(reporter = new LoggingReporter(actorSystem))

      val spoonbillServer = spoonbill.server.spoonbillService(actualConfig)
      val wsRouter      = configureWsRoute(spoonbillServer, pekkoHttpConfig, actualConfig.reporter, wsLoggingEnabled)
      val httpRoute     = configureHttpRoute(spoonbillServer)

      wsRouter ~ httpRoute
  }

  private[pekko] def configureWsRoute[F[_]: Effect](
    spoonbillServer: SpoonbillService[F],
    pekkoHttpConfig: PekkoHttpServerConfig,
    reporter: Reporter,
    wsLoggingEnabled: Boolean
  )(implicit materializer: Materializer, ec: ExecutionContext): Route =
    extractRequest { request =>
      extractUnmatchedPath { path =>
        extractWebSocketUpgrade { upgrade =>
          val requestedProtocols = upgrade.requestedProtocols
          if (!acceptsProtocols(requestedProtocols)) {
            // Reject non-Spoonbill WebSocket clients early for cost and security.
            complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity("Unsupported websocket subprotocol.")))
          } else {
            // inSink - consume messages from the client
            // outSource - push messages to the client
            val (inStream, inSink) = Sink.spoonbillStream[F, Bytes].preMaterialize()
            val spoonbillRequest     = mkSpoonbillRequest(request, path.toString, inStream)
            val ownership          = new UpgradeOwnership[F](request, inStream, pekkoHttpConfig.wsSetupTimeout)

            complete {
              val spoonbillWsRequest = SpoonbillWebSocketRequest(spoonbillRequest, requestedProtocols)
              Effect[F]
                .toFuture(Effect[F].delayAsync(spoonbillServer.ws(spoonbillWsRequest)))
                .transformWith {
                  case Failure(error) =>
                    Effect[F].toFuture(ownership.abort()).flatMap(_ => rejectedWebSocket(inStream, error))
                  case Success(response) =>
                    ownership.finish(response)(
                      acceptedWebSocket(
                        upgrade,
                        inStream,
                        inSink,
                        response,
                        pekkoHttpConfig,
                        reporter,
                        wsLoggingEnabled,
                        ownership.attached
                      )
                    )
                }
            }
          }
        }
      }
    }

  /** Failure stays a rejection. The pre-materialized input is released through
    * the effect so a lazy `F` cannot drop the finalizer.
    */
  private def rejectedWebSocket[F[_]: Effect](inStream: Stream[F, Bytes], error: Throwable)(implicit
    ec: ExecutionContext
  ): Future[HttpResponse] =
    Effect[F].toFuture(inStream.cancel().recover { case NonFatal(_) => () }).flatMap { _ =>
      error match {
        case BadRequestException(message) =>
          Future.successful(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(message)))
        case other => Future.failed(other)
      }
    }

  /** Choose the transport once from the public variant. `SendThenClose` keeps
    * the already-canceled application sink out of the graph and drains the
    * peer on a fresh sink. Output completion still closes that coupled flow.
    */
  private def acceptedWebSocket[F[_]: Effect](
    upgrade: WebSocketUpgrade,
    inStream: Stream[F, Bytes],
    inSink: Sink[Bytes, _],
    response: SpoonbillWebSocketResponse[F],
    httpConfig: PekkoHttpServerConfig,
    reporter: Reporter,
    wsLoggingEnabled: Boolean,
    onAttached: () => Unit
  )(implicit materializer: Materializer, ec: ExecutionContext): Future[HttpResponse] = {
    response match {
      case SpoonbillWebSocketResponse.SendThenClose(outStream, selectedProtocol, release) =>
        Effect[F].toFuture(inStream.cancel().recover { case NonFatal(_) => () }).flatMap { _ =>
          Future.successful(
            upgrade.handleMessages(
              terminalFlow(outStream, httpConfig, wsLoggingEnabled, release, onAttached),
              Some(selectedProtocol)
            )
          )
        }
      case SpoonbillWebSocketResponse.Duplex(outStream, selectedProtocol, release) =>
        Future.successful(
          upgrade.handleMessages(
            duplexFlow(inSink, outStream, httpConfig, reporter, wsLoggingEnabled, release, onAttached),
            Some(selectedProtocol)
          )
        )
    }
  }

  private def logFrames[Mat](flow: Flow[Message, Message, Mat], enabled: Boolean): Flow[Message, Message, Mat] =
    if (enabled) flow.log("spoonbill-ws", (_: Message) => "frame")
    else flow

  private def duplexFlow[F[_]: Effect](
    inSink: Sink[Bytes, _],
    outStream: Stream[F, Bytes],
    httpConfig: PekkoHttpServerConfig,
    reporter: Reporter,
    wsLoggingEnabled: Boolean,
    release: () => F[Unit],
    onAttached: () => Unit
  )(implicit materializer: Materializer, ec: ExecutionContext): Flow[Message, Message, _] = {
    val source = outStream.asPekkoSource.map(text => BinaryMessage.Strict(text.as[ByteString]))
    val sink = Flow[Message]
      .mapAsync(httpConfig.wsStreamedParallelism) {
        case TextMessage.Strict(message) =>
          Future.successful(Some(BytesLike[Bytes].utf8(message)))
        case TextMessage.Streamed(stream) =>
          stream
            .completionTimeout(httpConfig.wsStreamedCompletionTimeout)
            .runFold("")(_ + _)
            .map(message => Some(BytesLike[Bytes].utf8(message)))
        case BinaryMessage.Strict(data) =>
          Future.successful(Some(Bytes.wrap(data)))
        case BinaryMessage.Streamed(stream) =>
          stream
            .completionTimeout(httpConfig.wsStreamedCompletionTimeout)
            .runFold(ByteString.empty)(_ ++ _)
            .map(message => Some(Bytes.wrap(message)))
      }
      .recover { case ex =>
        reporter.error("WebSocket input stream failed; shutting down output stream")
        Effect[F].runAsync(outStream.cancel().recover { case NonFatal(_) => () })(_ => ())
        None
      }
      .collect { case Some(message) =>
        message
      }
      .to(inSink)
    whenTerminated(logFrames(Flow.fromSinkAndSourceCoupled(sink, source), wsLoggingEnabled), release, onAttached)
  }

  /** Release application resources after the output flow terminates. Emitted
    * frames are already owned by the transport; terminal input is detached.
    */
  private def whenTerminated[F[_]: Effect, Mat](
    flow: Flow[Message, Message, Mat],
    release: () => F[Unit],
    onAttached: () => Unit
  )(implicit ec: ExecutionContext): Flow[Message, Message, Mat] =
    flow.watchTermination() { (mat, done) =>
      // Materialization is the handoff. Before this, timeout still owns cleanup.
      onAttached()
      done.onComplete(_ => Effect[F].runAsync(release())(_ => ()))(ec)
      mat
    }

  /** Drain discarded streamed bodies incrementally under the existing limit. */
  private def terminalFlow[F[_]: Effect](
    outStream: Stream[F, Bytes],
    httpConfig: PekkoHttpServerConfig,
    wsLoggingEnabled: Boolean,
    release: () => F[Unit],
    onAttached: () => Unit
  )(implicit materializer: Materializer, ec: ExecutionContext): Flow[Message, Message, _] = {
    val source = outStream.asPekkoSource.map(text => BinaryMessage.Strict(text.as[ByteString]))
    val drain = Flow[Message]
      .mapAsync(httpConfig.wsStreamedParallelism) {
        case TextMessage.Streamed(stream) =>
          stream.completionTimeout(httpConfig.wsStreamedCompletionTimeout).runWith(Sink.ignore).map(_ => ())
        case BinaryMessage.Streamed(stream) =>
          stream.completionTimeout(httpConfig.wsStreamedCompletionTimeout).runWith(Sink.ignore).map(_ => ())
        case _ =>
          Future.successful(())
      }
      .to(Sink.ignore)
    whenTerminated(logFrames(Flow.fromSinkAndSourceCoupled(drain, source), wsLoggingEnabled), release, onAttached)
  }

  /** The routing API has no pre-upgrade peer-disconnect notification. Keep
    * setup ownership bounded even when HTTP request timeouts are disabled, and
    * never replace the caller's timeout handler or inspect its implementation.
    * All synchronization is confined to setup/termination, never frame pulls.
    */
  private[pekko] final class UpgradeOwnership[F[_]: Effect](
    request: HttpRequest,
    input: Stream[F, Bytes],
    setupTimeout: FiniteDuration
  )(implicit materializer: Materializer, ec: ExecutionContext) {
    private sealed trait State
    private case object Waiting extends State
    private final case class Prepared(release: () => F[Unit]) extends State
    private case object Attached extends State
    private case object Abandoned extends State
    private var state: State = Waiting
    private var cancelTimer: () => Unit = () => ()
    private val releaseInput = SpoonbillWebSocketResponse.releaseOnce[F](() => input.cancel())
    private val deadline: FiniteDuration = request.header[`Timeout-Access`].map(_.timeoutAccess.timeout) match {
      case Some(value: FiniteDuration) if value > Duration.Zero => if (value < setupTimeout) value else setupTimeout
      case _ => setupTimeout
    }
    private val scheduled = materializer.scheduleOnce(deadline, new Runnable {
      def run(): Unit = Effect[F].runAsync(abort())(_ => ())
    })
    synchronized {
      if (state == Abandoned) scheduled.cancel()
      else cancelTimer = () => { scheduled.cancel(); () }
    }

    def attached(): Unit = synchronized {
      state match {
        case Prepared(_) => state = Attached; cancelTimer()
        case _ => throw new TimeoutException("WebSocket setup ended before materialization")
      }
    }

    def abort(): F[Unit] = Effect[F].delay {
      synchronized {
        val cleanup = state match {
          case Waiting => releaseInput
          case Prepared(release) => () => releaseInput() *> Effect[F].delayAsync(release()).recover { case NonFatal(_) => () }
          case _ => () => Effect[F].unit
        }
        if (state != Attached) state = Abandoned
        cancelTimer()
        cleanup
      }
    }.flatMap(cleanup => cleanup())

    def finish(response: SpoonbillWebSocketResponse[F])(http: => Future[HttpResponse]): Future[HttpResponse] = {
      val accepted = synchronized {
        state match {
          case Waiting => state = Prepared(response.release); true
          case _ => false
        }
      }
      if (!accepted)
        Effect[F].toFuture(Effect[F].delayAsync(response.release()).recover { case NonFatal(_) => () })
          .flatMap(_ => Future.failed(new TimeoutException("WebSocket setup expired")))
      else {
        val built = try http catch { case NonFatal(error) => Future.failed(error) }
        built.recoverWith { case NonFatal(error) =>
          Effect[F].toFuture(abort()).flatMap(_ => Future.failed(error))
        }
      }
    }
  }

  private def configureHttpRoute[F[_]](
    spoonbillServer: SpoonbillService[F]
  )(implicit mat: Materializer, async: Effect[F], ec: ExecutionContext): Route =
    extractUnmatchedPath { path =>
      extractRequest { request =>
        val sink = Sink.spoonbillStream[F, Bytes]
        val body =
          if (request.method == HttpMethods.GET) {
            Stream.empty[F, Bytes]
          } else {
            request.entity.dataBytes
              .map(Bytes.wrap(_))
              .toMat(sink)(Keep.right)
              .run()
          }
        val spoonbillRequest = mkSpoonbillRequest(request, path.toString, body)
        val responseF      = handleHttpResponse(spoonbillServer, spoonbillRequest)
        complete(responseF)
      }
    }

  private def mkSpoonbillRequest[F[_], Body](request: HttpRequest, path: String, body: Body): SpoonbillRequest[Body] =
    SpoonbillRequest(
      pq = PathAndQuery.fromString(path).withParams(request.uri.rawQueryString),
      method = SpoonbillRequest.Method.fromString(request.method.value),
      contentLength = request.entity.contentLengthOption,
      renderedCookie = request.headers.find(_.is("cookie")).map(_.value()).getOrElse(""),
      headers = {
        val contentType = request.entity.contentType
        val contentTypeHeaders =
          if (contentType != ContentTypes.NoContentType) Seq("content-type" -> contentType.toString) else Seq.empty
        request.headers.map(h => (h.name(), h.value())) ++ contentTypeHeaders
      },
      body = body
    )

  private def handleHttpResponse[F[_]: Effect](spoonbillServer: SpoonbillService[F], spoonbillRequest: SpoonbillHttpRequest[F])(
    implicit ec: ExecutionContext
  ): Future[HttpResponse] =
    Effect[F].toFuture(spoonbillServer.http(spoonbillRequest)).map {
      case response @ SpoonbillResponse(status, body, responseHeaders, _) =>
        val (contentTypeOpt, otherHeaders) = getContentTypeAndResponseHeaders(responseHeaders)
        val bytesSource                    = body.asPekkoSource.map(_.as[ByteString])
        HttpResponse(
          StatusCode.int2StatusCode(status.code),
          otherHeaders,
          response.contentLength match {
            case Some(bytesLength) =>
              HttpEntity(contentTypeOpt.getOrElse(ContentTypes.NoContentType), bytesLength, bytesSource)
            case None => HttpEntity(contentTypeOpt.getOrElse(ContentTypes.NoContentType), bytesSource)
          }
        )
    }

  private def getContentTypeAndResponseHeaders(
    responseHeaders: Seq[(String, String)]
  ): (Option[ContentType], List[HttpHeader]) = {
    val headers = responseHeaders.map { case (name, value) =>
      HttpHeader.parse(name, value) match {
        case HttpHeader.ParsingResult.Ok(header, _) => header
        case _                                      => RawHeader(name, value)
      }
    }
    val (contentTypeHeaders, otherHeaders) = headers.partition(_.lowercaseName() == "content-type")
    val contentTypeOpt                     = contentTypeHeaders.headOption.flatMap(h => ContentType.parse(h.value()).toOption)
    (contentTypeOpt, otherHeaders.toList)
  }
}
