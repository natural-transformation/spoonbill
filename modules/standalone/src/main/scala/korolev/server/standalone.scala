package spoonbill.server

import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.data.syntax.*
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.io.{RawDataSocket, ServerSocket}
import spoonbill.effect.syntax.*
import spoonbill.http.HttpServer
import spoonbill.http.protocol.WebSocketProtocol
import spoonbill.web.{Headers, Request, Response}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import java.util.concurrent.TimeoutException

object standalone {

  def buildServer[F[_]: Effect, B: BytesLike](
    service: SpoonbillService[F],
    address: SocketAddress,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean,
    wsSetupTimeout: FiniteDuration = 30.seconds,
    wsMaxMessageBytes: Int = spoonbill.web.WebSocketLimits.DefaultMaxMessageBytes,
    wsMessageTimeout: FiniteDuration = 30.seconds,
    maxRequestHeaderBytes: Int = 16 * 1024,
    requestHeaderTimeout: FiniteDuration = 30.seconds,
    requestBodyTimeout: FiniteDuration = 30.seconds,
    maxChunkLineBytes: Int = 1024,
    maxTrailerBytes: Int = 16 * 1024,
    maxConnections: Int = 1024,
    closeNotificationWorkers: Int = 2
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {
    require(wsSetupTimeout > Duration.Zero, "wsSetupTimeout must be positive")
    require(wsMessageTimeout > Duration.Zero, "wsMessageTimeout must be positive")
    val webSocketProtocol = new WebSocketProtocol[B](wsMaxMessageBytes)
    HttpServer.withConnection[F, B](address, group = group, gracefulShutdown = gracefulShutdown,
      maxRequestHeaderBytes = maxRequestHeaderBytes, requestHeaderTimeout = requestHeaderTimeout,
      requestBodyTimeout = requestBodyTimeout, maxChunkLineBytes = maxChunkLineBytes, maxTrailerBytes = maxTrailerBytes,
      maxConnections = maxConnections, closeNotificationWorkers = closeNotificationWorkers) { (request, client, upgradeInput) =>
      val protocols = request
        .header(Headers.SecWebSocketProtocol)
        .toSeq
        .flatMap(_.split(','))
        .filterNot(_.isBlank)
      webSocketProtocol.findIntention(request) match {
        case Some(_) if request.contentLength.exists(_ > 0) || request.header(Headers.TransferEncoding).nonEmpty =>
          Effect[F].pure(Response(Response.Status.BadRequest, Stream.empty[F, B], Nil, Some(0L)))
        case Some(intention) =>
          val messageDeadline = new MessageDeadline(wsMessageTimeout, () => client.shutdownFromDeadline())
          val detach = client.subscribeClose(_ => messageDeadline.close())
          val f = webSocketProtocol.upgrade[F](intention, () => messageDeadline.start(), () => messageDeadline.complete()) { frameRequest =>
            attachWebSocket(service, frameRequest, protocols, client, wsSetupTimeout)
          }
          f(request.copy(body = upgradeInput)).recoverF { case NonFatal(error) =>
            messageDeadline.close()
            detach()
            Effect[F].fail(error)
          }
        case _ =>
          // This is just HTTP query
          service
            .http(request.copy(body = request.body.map(Bytes.wrap(_))))
            .map(response => response.copy(body = response.body.map(_.as[B])))
      }
    }
  }

  /** The frame stream is the transport. Application cancellation during setup
    * must not close it before the disposition is known.
    */
  private def attachWebSocket[F[_]: Effect, B: BytesLike](
    service: SpoonbillService[F],
    request: Request[Stream[F, WebSocketProtocol.Frame.Merged[B]]],
    protocols: Seq[String],
    client: RawDataSocket[F, B],
    setupTimeout: FiniteDuration
  )(implicit ec: ExecutionContext): F[Response[Stream[F, WebSocketProtocol.Frame.Merged[B]]]] = {
    val handoff = new SetupInput(request.body)
    val deadline = new SetupDeadline(setupTimeout, () => handoff.releaseApplication(),
      () => client.shutdown(), () => client.shutdownFromDeadline())
    var peerClose: () => F[Unit] = () => Effect[F].unit
    // This is the existing binary-frame conversion path. Control-frame
    // termination does not add an effect continuation to ordinary input.
    val bytes = new Stream[F, Bytes] {
      def pull(): F[Option[Bytes]] = handoff.stream.pull().flatMap {
        case Some(WebSocketProtocol.Frame.Binary(message, _)) => Effect[F].pure(Some(message.as[Bytes]))
        case Some(frame) if isPeerClose(frame) => peerClose().as(None)
        case Some(_) => pull()
        case None => Effect[F].none
      }.recoverF { case NonFatal(error) =>
        // Decoder rejection must close an attached socket even when application
        // output is idle and its caller does not cancel the failed input.
        // A user finalizer may await this read, so do not await it here.
        handoff.releaseApplication().start *> Effect[F].fail(error)
      }
      def cancel(): F[Unit] = handoff.stream.cancel()
    }
    val wsRequest = WebSocketRequest(request.copy(body = bytes), protocols)
    client.subscribeCloseEffect(_ => deadline.transportEnded())
      .flatMap(detach => Effect[F].delay(deadline.onFinished(detach))) *>
      Effect[F].delayAsync(service.ws(wsRequest)).flatMap { response =>
      val closeFrame =
        WebSocketProtocol.Frame.ConnectionClose.asInstanceOf[WebSocketProtocol.Frame.Merged[B]]
      // The direction gate also suppresses an
      // application element that was already in flight when the peer ended.
      val lifecycle = new WebSocketOutputCell
      val cleanupFinished = scala.concurrent.Promise[Unit]()
      def awaitCleanup(): F[Unit] = Effect[F].promise { callback =>
        cleanupFinished.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
      }
      val cancelOutput = WebSocketResponse.releaseOnce[F](() => response.output.cancel())
      // Claim before invoking user cleanup: releasing a session can cancel its
      // input recursively. That callback must not wait on its own release.
      def close(abort: Boolean): F[Unit] = Effect[F].delayAsync {
        if (lifecycle.claimCleanup(abort)) {
          // The normal path arrives only after writing Close. Close the channel
          // before user cleanup, which may await an outstanding application read.
          (client.shutdown() *>
            cancelOutput().recover { case NonFatal(_) => () } *>
            Effect[F].delayAsync(response.release()).recover { case NonFatal(_) => () })
            .recover { case NonFatal(_) => () }
            .flatMap(_ => Effect[F].delay { cleanupFinished.trySuccess(()); () })
        } else Effect[F].unit
      }
      def requestGracefulClose(): F[Unit] = Effect[F].delayAsync {
        if (lifecycle.requestGraceful()) {
          // Do not await finalizers (or cancellation waiting for this very read)
          // inside inbound Close conversion. The output writer owns completion.
          Effect[F].start(cancelOutput()).as(())
        } else Effect[F].unit
      }
      def finish(): F[Unit] = close(abort = false)
      def abort(): F[Unit] = close(abort = true)
      val output = finishAfter[F, Bytes, WebSocketProtocol.Frame.Merged[B]](
        response.output,
        message => WebSocketProtocol.Frame.Binary(message.as[B]),
        () => finish(),
        () => abort(),
        () => deadline.attach(),
        closeFrame,
        lifecycle
      )
      peerClose = () => requestGracefulClose()
      deadline.acquire(() => abort() *> awaitCleanup()).flatMap { acquired =>
        if (!acquired) Effect[F].fail(new TimeoutException("WebSocket setup ended before attachment"))
        else response match {
        case WebSocketResponse.Duplex(_, protocol, _) =>
          // Input cancellation, EOF, and read failure share one teardown.
          // Register it before couple so a resumed setup read can see EOF.
          handoff.onInputEnd(() => if (lifecycle.current.direction == WebSocketOutputMachine.Direction.Open) abort() else Effect[F].unit)
          handoff.couple().flatMap { terminated =>
            if (terminated) abort().as(socketResponse(protocol, Stream.empty))
            else Effect[F].pure(socketResponse(protocol, output))
          }
        case WebSocketResponse.SendThenClose(_, protocol, _) =>
          // Peer EOF, a close frame, or a read failure must cancel output that
          // is still pending. Graceful completion sets the same gate first so
          // the drain does not turn a normal close into a second abort.
          handoff.releaseApplication() *>
            handoff.drain(isPeerClose, () => requestGracefulClose()).start *>
            Effect[F].pure(socketResponse(protocol, output))
        }
      }
    }.recoverF { case NonFatal(error) => deadline.reject() *> Effect[F].fail(error) }
  }

  private def isPeerClose(frame: WebSocketProtocol.Frame.Merged[?]): Boolean =
    frame == WebSocketProtocol.Frame.ConnectionClose

  private[server] final class WebSocketOutputCell {
    import WebSocketOutputMachine.*
    private val state = new AtomicReference(initial)
    def current: State = state.get()

    @tailrec final def advance(change: State => State): State = {
      val before = state.get()
      val after = change(before)
      if ((before eq after) || state.compareAndSet(before, after)) before
      else advance(change)
    }

    def requestGraceful(): Boolean = advance(graceful).direction == Direction.Open
    def claimCleanup(abort: Boolean): Boolean = advance(finalizeClose(_, abort)).cleanupClaimed == false
  }

  private def socketResponse[F[_], B](
    protocol: String,
    body: Stream[F, WebSocketProtocol.Frame.Merged[B]]
  ): Response[Stream[F, WebSocketProtocol.Frame.Merged[B]]] =
    Response(
      status = Response.Status.Ok,
      body = body,
      headers = Seq(Headers.SecWebSocketProtocol -> protocol),
      contentLength = None
    )

  /** Emit a close frame, then run `close` on the following pull so the frame is
    * written before the transport is released. HttpServer cancels this stream
    * after failed pulls, encoding, or writes. Finalization runs once. When
    * aborted, end without that frame so the peer observes TCP EOF.
    */
  private[server] def finishAfter[F[_]: Effect, A, B](
    frames: Stream[F, A],
    convert: A => B,
    close: () => F[Unit],
    abort: () => F[Unit],
    attach: () => F[Boolean],
    closeFrame: B,
    lifecycle: WebSocketOutputCell
  ): Stream[F, B] =
    new Stream[F, B] {
      import WebSocketOutputMachine.*
      private val mapFrame: Option[A] => Option[B] = frame =>
        lifecycle.current.direction match {
          case Direction.Aborted =>
            lifecycle.advance(ended)
            None
          case Direction.Open if frame.nonEmpty => frame.map(convert)
          case _ =>
            lifecycle.advance(closeEmitted)
            Some(closeFrame)
        }

      def pull(): F[Option[B]] =
        if (lifecycle.current.output == Output.Pulling) Effect[F].map(frames.pull())(mapFrame)
        else pullLifecycle()

      private def pullLifecycle(): F[Option[B]] =
        lifecycle.current.output match {
          case Output.Unattached => attach().flatMap { accepted =>
            lifecycle.advance(attached(_, accepted))
            if (accepted) {
              pull()
            } else {
              Effect[F].pure(None)
            }
          }
          case Output.Ended => Effect[F].pure(None)
          case Output.CloseEmitted => Effect[F].delayAsync {
            lifecycle.advance(ended)
            close().as(None)
          }
          case Output.Pulling => Effect[F].map(frames.pull())(mapFrame)
        }
      def cancel(): F[Unit] = Effect[F].delayAsync {
        lifecycle.advance(ended)
        abort()
      }
    }
}
