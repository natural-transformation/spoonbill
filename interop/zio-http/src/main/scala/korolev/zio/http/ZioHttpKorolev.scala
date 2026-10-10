package spoonbill.zio.http

import _root_.zio.{Chunk, Promise, RIO, ZIO}
import _root_.zio.http.*
import _root_.zio.http.codec.PathCodec
import _root_.zio.stream.ZStream
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.effect.{Queue, Reporter, Stream as KStream}
import spoonbill.server.{
  HttpRequest as SpoonbillHttpRequest,
  SpoonbillService,
  SpoonbillServiceConfig,
  WebSocketRequest,
  WebSocketResponse
}
import spoonbill.server.internal.Cookies
import spoonbill.state.{StateDeserializer, StateSerializer}
import spoonbill.web.{PathAndQuery as PQ, Request as SpoonbillRequest, Response as SpoonbillResponse}
import spoonbill.zio.ChunkBytesLike
import spoonbill.zio.Zio2Effect
import spoonbill.zio.streams.*
import scala.concurrent.duration.*

class ZioHttpSpoonbill[R] {

  type ZEffect = Zio2Effect[R, Throwable]

  def service[S: StateSerializer: StateDeserializer, M](
    config: SpoonbillServiceConfig[RIO[R, *], S, M],
    wsSetupTimeout: FiniteDuration = 30.seconds
  )(implicit eff: ZEffect): Routes[R, Response] = {

    require(wsSetupTimeout > Duration.Zero, "wsSetupTimeout must be positive")

    val spoonbillServer = spoonbill.server.spoonbillService(config)

    val rootPath = Path.decode(config.rootPath.mkString)

    val handler = Handler.fromFunctionZIO[(Path, Request)] { case (path, req) =>
      val subPath = path.encode
      val isWebSocket = matchWebSocket(req)
      if (!isWebSocket && isWebSocketBridgePath(subPath)) {
        // Helpful to detect missing upgrade headers or proxy stripping.
        config.reporter.warning(
          s"WebSocket upgrade headers missing for $subPath (${upgradeHeaderSummary(req)})"
        )
      }
      val response =
        if (isWebSocket)
          routeWsRequest(req, subPath, spoonbillServer, config.reporter, config.webSocketProtocolsEnabled, wsSetupTimeout)
        else routeHttpRequest(subPath, req, spoonbillServer)
      response.mapError(Response.fromThrowable)
    }

    Routes.singleton(handler).nest(prefixCodec(rootPath))
  }

  private def matchWebSocket(req: Request): Boolean =
    req.method == Method.GET && containsUpgradeHeader(req)

  private def routeHttpRequest(subPath: String, req: Request, spoonbillServer: SpoonbillService[RIO[R, *]])(implicit
    eff: ZEffect
  ): ZIO[R, Throwable, Response] = {
    req match {
      case req if req.method == Method.GET =>
        val body           = KStream.empty[RIO[R, *], Bytes]
        val spoonbillRequest = mkSpoonbillRequest(req, subPath, body)
        handleHttpResponse(spoonbillServer, spoonbillRequest)

      case req =>
        for {
          stream        <- toSpoonbillBody(req.body)
          spoonbillRequest = mkSpoonbillRequest(req, subPath, stream)
          response      <- handleHttpResponse(spoonbillServer, spoonbillRequest)
        } yield {
          response
        }
    }
  }

  // Build a literal prefix codec so this Routes only matches under rootPath.
  private def prefixCodec(prefix: Path): PathCodec[Unit] =
    prefix.segments.foldLeft(PathCodec.empty: PathCodec[Unit]) { (codec, segment) =>
      codec / PathCodec.literal(segment)
    }

  private def containsUpgradeHeader(req: Request): Boolean = {
    val found = for {
      _ <- req.rawHeader(Header.Connection).filter(_.toLowerCase.indexOf("upgrade") > -1)
      _ <- req.rawHeader(Header.Upgrade).filter(_.toLowerCase.indexOf("websocket") > -1)
    } yield {}
    found.isDefined
  }

  private def isWebSocketBridgePath(path: String): Boolean =
    path.contains("/bridge/web-socket/")

  private def upgradeHeaderSummary(req: Request): String = {
    val connection = req.rawHeader(Header.Connection).getOrElse("<missing>")
    val upgrade    = req.rawHeader(Header.Upgrade).getOrElse("<missing>")
    s"connection=$connection upgrade=$upgrade"
  }

  private def protocolSummary(protocols: Seq[String]): String =
    if (protocols.isEmpty) "<none>" else protocols.mkString(",")

  private def hasDeviceIdCookie(req: Request): Boolean =
    req.rawHeader(Header.Cookie).exists(_.contains(s"${Cookies.DeviceId}="))

  private[http] def parseProtocols(req: Request): Seq[String] =
    parseProtocolsValues(req.headers.getAll(Header.SecWebSocketProtocol).map(_.renderedValue))

  private[http] def parseProtocolsValues(values: Seq[String]): Seq[String] =
    values
      .flatMap(_.split(',').iterator.map(_.trim))
      .filter(_.nonEmpty)

  private val ProtocolJson        = "json"
  private val ProtocolJsonDeflate = "json-deflate"
  private val SupportedProtocols  = Set(ProtocolJson, ProtocolJsonDeflate)

  private[http] def sanitizeProtocols(protocols: Seq[String]): Seq[String] =
    protocols.filter(SupportedProtocols.contains)

  private[http] def acceptsProtocols(protocols: Seq[String]): Boolean =
    sanitizeProtocols(protocols).nonEmpty

  private def routeWsRequest[S: StateSerializer: StateDeserializer, M](
    req: Request,
    fullPath: String,
    spoonbillServer: SpoonbillService[RIO[R, *]],
    reporter: Reporter,
    webSocketProtocolsEnabled: Boolean,
    wsSetupTimeout: FiniteDuration
  )(implicit eff: ZEffect): ZIO[R, Throwable, Response] = {

    val fromClientKQueue = Queue[RIO[R, *], Bytes]()
    val protocols = parseProtocols(req)
    // When protocol negotiation is disabled, force JSON to keep codecs aligned.
    val sanitizedProtocols =
      if (webSocketProtocolsEnabled) sanitizeProtocols(protocols) else Seq(ProtocolJson)
    if (webSocketProtocolsEnabled && sanitizedProtocols.isEmpty) {
      reporter.warning(
        s"Reject WebSocket upgrade for $fullPath: protocols=${protocolSummary(protocols)} " +
          s"deviceIdCookie=${hasDeviceIdCookie(req)}"
      )
      ZIO.succeed(Response(status = Status.BadRequest))
    } else {
      for {
        inputCancelled <- Promise.make[Nothing, Unit]
        input = new KStream[RIO[R, *], Bytes] {
                  def pull(): RIO[R, Option[Bytes]] = fromClientKQueue.stream.pull()
                  def cancel(): RIO[R, Unit] =
                    fromClientKQueue.stream.cancel() *> inputCancelled.succeed(()).unit
                }
        spoonbillRequest = mkSpoonbillRequest[KStream[RIO[R, *], Bytes]](req, fullPath, input)
        setup <- WebSocketSetup.make(input.cancel(), wsSetupTimeout)
        route <- ZIO.uninterruptibleMask { restore =>
          restore(spoonbillServer.ws(WebSocketRequest(spoonbillRequest, sanitizedProtocols))).flatMap { response =>
            setup.prepare(response.release).flatMap { admitted =>
              if (!admitted) ZIO.fail(new java.util.concurrent.TimeoutException("WebSocket setup expired"))
              else restore {
                for {
                  // Cancel the unused application input without coupling it to terminal output.
                  discardInbound <- response match {
                                      case WebSocketResponse.SendThenClose(_, _, _) => input.cancel().as(true)
                                      case WebSocketResponse.Duplex(_, _, _)        => ZIO.succeed(false)
                                    }
                  toClient = response.output.map(out => WebSocketFrame.Binary(out.as[Chunk[Byte]])).toZStream
                  effectiveProtocol = if (webSocketProtocolsEnabled) Some(response.selectedProtocol) else None
                  route <- buildSocket(
                             toClient,
                             fromClientKQueue,
                             reporter,
                             effectiveProtocol,
                             discardInbound = discardInbound,
                             release = response.release,
                             inputCancelled = Some(inputCancelled),
                             attach = setup.attach
                           )
                } yield {
                  reporter.debug(
                    s"WebSocket upgrade accepted for $fullPath: selectedProtocol=${response.selectedProtocol} " +
                      s"protocols=${protocolSummary(protocols)} deviceIdCookie=${hasDeviceIdCookie(req)}"
                  )
                  route
                }
              }
            }
          }
        }.onError(_ => setup.abandon.ignore)
      } yield route
    }
  }

  private[http] def buildSocket(
    toClientStream: ZStream[R, Throwable, WebSocketFrame],
    fromClientKQueue: Queue[RIO[R, *], Bytes],
    reporter: Reporter,
    selectedProtocol: Option[String] = None,
    discardInbound: Boolean = false,
    release: () => RIO[R, Unit] = () => ZIO.unit,
    inputCancelled: Option[Promise[Nothing, Unit]] = None,
    attach: RIO[R, Boolean] = ZIO.succeed(true)
  ): RIO[R, Response] = {
    val socket =
      Handler.webSocket { channel =>
        ZIO.acquireReleaseWith(attach) { attached =>
          if (attached) fromClientKQueue.close().ignore *> ZIO.suspendSucceed(release()).ignore
          else ZIO.unit
        } {
          case false => channel.shutdown
          case true =>
            runSocket(
              channel.send,
              channel.receiveAll,
              toClientStream,
              fromClientKQueue,
              reporter,
              // Close only after the data frames; shutdown would discard an unflushed last frame.
              onOutputComplete = channel.send(ChannelEvent.Read(WebSocketFrame.close(1000, None))).ignore,
              discardInbound = discardInbound,
              inputCancelled = inputCancelled,
              onAbort = channel.shutdown
            )
        }
      }

    // Apply WebSocketConfig with subprotocol if one was selected.
    // This is required for the Sec-WebSocket-Protocol header to be included in the
    // WebSocket upgrade response - using Response.addHeader() does not work because
    // Netty's WebSocketServerProtocolHandler builds its own response from the config.
    val configuredSocket = selectedProtocol match {
      case Some(protocol) =>
        socket.withConfig(WebSocketConfig.default.subProtocol(Some(protocol)))
      case None =>
        socket
    }

    Response.fromSocketApp(configuredSocket)
  }

  private[http] def runSocket(
    send: ChannelEvent[WebSocketFrame] => RIO[R, Unit],
    receiveAll: PartialFunction[ChannelEvent[WebSocketFrame], RIO[R, Unit]] => RIO[R, Unit],
    toClientStream: ZStream[R, Throwable, WebSocketFrame],
    fromClientKQueue: Queue[RIO[R, *], Bytes],
    reporter: Reporter,
    onOutputComplete: RIO[R, Unit] = ZIO.unit,
    discardInbound: Boolean = false,
    release: () => RIO[R, Unit] = () => ZIO.unit,
    inputCancelled: Option[Promise[Nothing, Unit]] = None,
    onAbort: RIO[R, Unit] = ZIO.unit
  ): RIO[R, Unit] = {
    val admitBinary: Chunk[Byte] => RIO[R, Unit] =
      if (discardInbound) _ => ZIO.unit
      else bytes => fromClientKQueue.offer(Bytes.wrap(bytes)).unit
    val admitText: String => RIO[R, Unit] =
      if (discardInbound) _ => ZIO.unit
      else text => fromClientKQueue.offer(BytesLike[Bytes].utf8(text)).unit

    val handled =
      for {
        // Use a promise to gate sending until handshake completes.
        // zio-http 3.x will warn "WebSocket send before handshake completed" and may close the connection
        // if we try to send before the handshake is done.
        handshakeComplete <- zio.Promise.make[Nothing, Boolean]
        sender = handshakeComplete.await
                       .flatMap {
                         case true =>
                           toClientStream
                             .mapZIO(frame => send(ChannelEvent.Read(frame)))
                             .runDrain
                             .foldCauseZIO(
                               cause =>
                                 cause.failureOption.orElse(cause.dieOption) match {
                                   case Some(err) =>
                                     ZIO.succeed(reporter.error("WebSocket send failed", err)) *> onAbort
                                   case None => onAbort
                                 },
                               _ => onOutputComplete
                             )
                         case false =>
                           ZIO.unit
                       }
        // Acquisition is masked; explicitly restore cancellation in the child.
        _ <- ZIO.acquireReleaseWith(sender.interruptible.forkDaemon)(_.interrupt) { _ =>
             receiveAll {
               case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                 // Signal that handshake is done; the send fiber can now start sending.
                 handshakeComplete.succeed(true).unit
               case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                 // Complete the promise so the send fiber can exit if handshake never happened.
                 handshakeComplete
                   .succeed(false)
                   .flatMap { completed =>
                     if (completed) fromClientKQueue.close() else ZIO.unit
                   }
               case ChannelEvent.Read(WebSocketFrame.Binary(bytes)) =>
                 handshakeComplete.succeed(true).unit *> admitBinary(bytes)
               case ChannelEvent.Read(WebSocketFrame.Text(t)) =>
                 handshakeComplete.succeed(true).unit *> admitText(t)
               case ChannelEvent.Read(WebSocketFrame.Close(_, _)) =>
                 handshakeComplete.succeed(false).unit *> fromClientKQueue.close()
               case ChannelEvent.ExceptionCaught(cause) =>
                 handshakeComplete.succeed(false).unit *> fromClientKQueue.close() *> ZIO.fail(cause)
               case ChannelEvent.Unregistered =>
                 // Unregistered can happen without a close frame; close the queue to unblock cleanup.
                 handshakeComplete.succeed(false).unit *> fromClientKQueue.close()
               case frame =>
                 ZIO.fail(new Exception(s"Invalid frame type ${frame.getClass.getName}"))
             }
             }
      } yield ()
    // Session cleanup runs when the handler ends, including peer close and
    // output failure. It is not run while a duplex session is still attached.
    val coupled = inputCancelled.filterNot(_ => discardInbound) match {
      case None => handled
      case Some(cancelled) =>
        cancelled.isDone.flatMap {
          case true => onAbort
          case false => handled.raceFirst(cancelled.await *> onAbort)
        }
    }
    coupled.ensuring(fromClientKQueue.close().ignore *> release().ignore)
  }

  private def mkSpoonbillRequest[B](request: Request, path: String, body: B): SpoonbillRequest[B] = {
    val cookies = request.rawHeader(Header.Cookie)
    val params  = request.url.queryParams.map.collect { case (k, v) if v.nonEmpty => (k, v.head) }
    SpoonbillRequest(
      pq = PQ.fromString(path).withParams(params),
      method = SpoonbillRequest.Method.fromString(request.method.name),
      renderedCookie = cookies.orNull,
      contentLength = request.header(Header.ContentLength).map(_.length),
      headers = {
        val contentType = request.header(Header.ContentType)
        val contentTypeHeader = {
          contentType.map { ct =>
            if (ct.renderedValue.contains("multipart")) Headers(ct) else Headers.empty
          }.getOrElse(Headers.empty)
        }
        (request.headers.toList ++ contentTypeHeader).map(header => header.headerName -> header.renderedValue)
      },
      body = body
    )
  }

  private def handleHttpResponse(
    spoonbillServer: SpoonbillService[RIO[R, *]],
    spoonbillRequest: SpoonbillHttpRequest[RIO[R, *]]
  ): ZIO[R, Throwable, Response] =
    spoonbillServer.http(spoonbillRequest).flatMap { case SpoonbillResponse(status, stream, responseHeaders, contentLength) =>
      val headers = Headers(responseHeaders.map { case (name, value) => Header.Custom(name, value) })
      val body: ZStream[R, Throwable, Byte] =
        stream.toZStream.flatMap { (bytes: Bytes) =>
          ZStream.fromIterable(bytes.as[Array[Byte]])
        }

      val bodyZio =
        contentLength match {
          case Some(length) => Body.fromStreamEnv(body, length)
          case None         => Body.fromStreamChunkedEnv(body)
        }

      bodyZio.map { body =>
        Response(
          status = HttpStatusConverter.fromSpoonbillStatus(status),
          headers = headers,
          body = body
        )
      }
    }

  private def toSpoonbillBody(body: Body)(implicit eff: ZEffect): RIO[R, KStream[RIO[R, *], Bytes]] =
    ZStreamOps[R, Byte](body.asStream).toSpoonbill(eff).map { kStream =>
      kStream.map(bytes => Bytes.wrap(bytes.toArray))
    }

}
