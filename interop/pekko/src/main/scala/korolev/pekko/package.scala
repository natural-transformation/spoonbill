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
import spoonbill.pekko.util.LoggingReporter
import spoonbill.server.{HttpRequest as SpoonbillHttpRequest, SpoonbillService, SpoonbillServiceConfig}
import spoonbill.server.{WebSocketRequest as SpoonbillWebSocketRequest, WebSocketResponse as SpoonbillWebSocketResponse}
import spoonbill.server.internal.BadRequestException
import spoonbill.state.{StateDeserializer, StateSerializer}
import spoonbill.web.{PathAndQuery, Request as SpoonbillRequest, Response as SpoonbillResponse}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink}
import org.apache.pekko.util.ByteString
import scala.concurrent.{ExecutionContext, Future}

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
      val wsRouter      = configureWsRoute(spoonbillServer, pekkoHttpConfig, actualConfig, wsLoggingEnabled)
      val httpRoute     = configureHttpRoute(spoonbillServer)

      wsRouter ~ httpRoute
  }

  private def configureWsRoute[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
    spoonbillServer: SpoonbillService[F],
    pekkoHttpConfig: PekkoHttpServerConfig,
    spoonbillServiceConfig: SpoonbillServiceConfig[F, S, M],
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

            complete {
              val spoonbillWsRequest = SpoonbillWebSocketRequest(spoonbillRequest, requestedProtocols)
              Effect[F]
                .toFuture(spoonbillServer.ws(spoonbillWsRequest))
                .map {
                  case SpoonbillWebSocketResponse(SpoonbillResponse(_, outStream, _, _), selectedProtocol) =>
                    val source = outStream.asPekkoSource
                      .map(text => BinaryMessage.Strict(text.as[ByteString]))
                    val sink = Flow[Message]
                      .mapAsync(pekkoHttpConfig.wsStreamedParallelism) {
                        case TextMessage.Strict(message) =>
                          Future.successful(Some(BytesLike[Bytes].utf8(message)))
                        case TextMessage.Streamed(stream) =>
                          stream
                            .completionTimeout(pekkoHttpConfig.wsStreamedCompletionTimeout)
                            .runFold("")(_ + _)
                            .map(message => Some(BytesLike[Bytes].utf8(message)))
                        case BinaryMessage.Strict(data) =>
                          Future.successful(Some(Bytes.wrap(data)))
                        case BinaryMessage.Streamed(stream) =>
                          stream
                            .completionTimeout(pekkoHttpConfig.wsStreamedCompletionTimeout)
                            .runFold(ByteString.empty)(_ ++ _)
                            .map(message => Some(Bytes.wrap(message)))
                      }
                      .recover { case ex =>
                        spoonbillServiceConfig.reporter.error(
                          s"WebSocket exception ${ex.getMessage}, shutdown output stream",
                          ex
                        )
                        outStream.cancel()
                        None
                      }
                      .collect { case Some(message) =>
                        message
                      }
                      .to(inSink)

                    upgrade.handleMessages(
                      if (wsLoggingEnabled) {
                        Flow.fromSinkAndSourceCoupled(sink, source).log("spoonbill-ws")
                      } else {
                        Flow.fromSinkAndSourceCoupled(sink, source)
                      },
                      Some(selectedProtocol)
                    )
                }
                .recover { case BadRequestException(message) =>
                  HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(message))
                }
            }
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
      contentLength = request.headers.find(_.is("content-length")).map(_.value().toLong),
      renderedCookie = request.headers.find(_.is("cookie")).map(_.value()).getOrElse(""),
      headers = {
        val contentType = request.entity.contentType
        val contentTypeHeaders =
          if (contentType.mediaType.isMultipart) Seq("content-type" -> contentType.toString) else Seq.empty
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
