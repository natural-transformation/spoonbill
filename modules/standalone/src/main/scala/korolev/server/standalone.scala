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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.TimeoutException

object standalone {

  def buildServer[F[_]: Effect, B: BytesLike](
    service: SpoonbillService[F],
    address: SocketAddress,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean,
    wsSetupTimeout: FiniteDuration = 30.seconds
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {
    require(wsSetupTimeout > Duration.Zero, "wsSetupTimeout must be positive")
    val webSocketProtocol = new WebSocketProtocol[B]
    HttpServer.withConnection[F, B](address, group = group, gracefulShutdown = gracefulShutdown) { (request, client) =>
      val protocols = request
        .header(Headers.SecWebSocketProtocol)
        .toSeq
        .flatMap(_.split(','))
        .filterNot(_.isBlank)
      webSocketProtocol.findIntention(request) match {
        case Some(intention) =>
          val f = webSocketProtocol.upgrade[F](intention) { frameRequest =>
            attachWebSocket(service, frameRequest, protocols, client, wsSetupTimeout)
          }
          f(request)
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
    val deadline = new SetupDeadline(setupTimeout, () => handoff.releaseApplication(), () => client.shutdown())
    var peerClose: () => F[Unit] = () => Effect[F].unit
    // This is the existing binary-frame conversion path. Control-frame
    // termination does not add an effect continuation to ordinary input.
    val bytes = new Stream[F, Bytes] {
      def pull(): F[Option[Bytes]] = handoff.stream.pull().flatMap {
        case Some(WebSocketProtocol.Frame.Binary(message, _)) => Effect[F].pure(Some(message.as[Bytes]))
        case Some(frame) if isPeerClose(frame) => peerClose().as(None)
        case Some(_) => pull()
        case None => Effect[F].none
      }
      def cancel(): F[Unit] = handoff.stream.cancel()
    }
    val wsRequest = WebSocketRequest(request.copy(body = bytes), protocols)
    Effect[F].delay {
      val detach = client.subscribeClose(_ => deadline.transportEnded().runAsync(_ => ()))
      deadline.onFinished(detach)
    } *>
      Effect[F].delayAsync(service.ws(wsRequest)).flatMap { response =>
      val closeFrame =
        WebSocketProtocol.Frame.ConnectionClose.asInstanceOf[WebSocketProtocol.Frame.Merged[B]]
      // 0 open, 1 graceful close, 2 abort. The gate also suppresses an
      // application element that was already in flight when the peer ended.
      val closing = new AtomicInteger(0)
      val finalized = new AtomicBoolean(false)
      val cancelOutput = WebSocketResponse.releaseOnce[F](() => response.output.cancel())
      // Claim before invoking user cleanup: releasing a session can cancel its
      // input recursively. That callback must not wait on its own release.
      def close(abort: Boolean): F[Unit] = Effect[F].delayAsync {
        if (abort) closing.set(2) else closing.compareAndSet(0, 1)
        if (finalized.compareAndSet(false, true)) {
          // The normal path arrives only after writing Close. Close the channel
          // before user cleanup, which may await an outstanding application read.
          client.shutdown() *>
            cancelOutput().recover { case NonFatal(_) => () } *>
            response.release().recover { case NonFatal(_) => () }
        } else Effect[F].unit
      }
      def requestGracefulClose(): F[Unit] = Effect[F].delayAsync {
        if (closing.compareAndSet(0, 1)) {
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
        closing
      )
      peerClose = () => requestGracefulClose()
      deadline.acquire(() => abort()).flatMap { acquired =>
        if (!acquired) Effect[F].fail(new TimeoutException("WebSocket setup ended before attachment"))
        else response match {
        case WebSocketResponse.Duplex(_, protocol, _) =>
          // Input cancellation, EOF, and read failure share one teardown.
          // Register it before couple so a resumed setup read can see EOF.
          handoff.onInputEnd(() => if (closing.get() == 0) abort() else Effect[F].unit)
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
  private def finishAfter[F[_]: Effect, A, B](
    frames: Stream[F, A],
    convert: A => B,
    close: () => F[Unit],
    abort: () => F[Unit],
    attach: () => F[Boolean],
    closeFrame: B,
    closing: AtomicInteger
  ): Stream[F, B] =
    new Stream[F, B] {
      @volatile private var phase = -1
      private val mapFrame: Option[A] => Option[B] = frame =>
        closing.get() match {
          case 2 =>
            phase = 2
            None
          case 0 if frame.nonEmpty => frame.map(convert)
          case _ =>
            phase = 1
            Some(closeFrame)
        }

      def pull(): F[Option[B]] =
        if (phase == 0) Effect[F].map(frames.pull())(mapFrame)
        else pullLifecycle()

      private def pullLifecycle(): F[Option[B]] =
        phase match {
          case -1 => attach().flatMap { attached =>
            if (attached) {
              phase = 0
              pull()
            } else {
              phase = 2
              Effect[F].pure(None)
            }
          }
          case 2 => Effect[F].pure(None)
          case 1 => Effect[F].delayAsync {
            phase = 2
            close().as(None)
          }
          case _ => Effect[F].map(frames.pull())(mapFrame)
        }
      def cancel(): F[Unit] = Effect[F].delayAsync {
        phase = 2
        abort()
      }
    }
}
