package spoonbill.http

import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeoutException
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{Decoder, Effect, Stream}
import spoonbill.effect.io.{RawDataSocket, ServerSocket, SocketCloseDispatcher}
import spoonbill.effect.syntax._
import spoonbill.http.protocol.Http11
import spoonbill.web.{Request, Response}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

object HttpServer {

  /**
   * @see
   *   [[ServerSocket.bind]]
   */
  def apply[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean = false,
    maxRequestHeaderBytes: Int = 16 * 1024,
    requestHeaderTimeout: FiniteDuration = 30.seconds,
    requestBodyTimeout: FiniteDuration = 30.seconds,
    maxChunkLineBytes: Int = 1024,
    maxTrailerBytes: Int = 16 * 1024,
    maxConnections: Int = 1024,
    closeNotificationWorkers: Int = 2
  )(
    f: Request[Stream[F, B]] => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] =
    withConnection[F, B](address, backlog, bufferSize, group, gracefulShutdown,
      maxRequestHeaderBytes, requestHeaderTimeout, requestBodyTimeout, maxChunkLineBytes, maxTrailerBytes,
      maxConnections, closeNotificationWorkers)((request, _, _) => f(request))

  /** Adapter boundary for observing and aborting an upgraded connection. */
  private[spoonbill] def withConnection[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean = false,
    maxRequestHeaderBytes: Int = 16 * 1024,
    requestHeaderTimeout: FiniteDuration = 30.seconds,
    requestBodyTimeout: FiniteDuration = 30.seconds,
    maxChunkLineBytes: Int = 1024,
    maxTrailerBytes: Int = 16 * 1024,
    maxConnections: Int = 1024,
    closeNotificationWorkers: Int = 2
  )(
    f: (Request[Stream[F, B]], RawDataSocket[F, B], Stream[F, B]) => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {

    require(requestHeaderTimeout > Duration.Zero, "requestHeaderTimeout must be positive")
    require(requestBodyTimeout > Duration.Zero, "requestBodyTimeout must be positive")
    val InternalServerErrorMessage = BytesLike[B].ascii("Internal server error")
    val http11                     = new Http11[B](maxRequestHeaderBytes, maxChunkLineBytes, maxTrailerBytes)
    val closeDispatcher = new SocketCloseDispatcher(maxConnections, closeNotificationWorkers)

    ServerSocket.accept[F, B](address, backlog, bufferSize, group, gracefulShutdown) { client =>
      closeDispatcher.reserve() match {
        case None => client.shutdown()
        case Some(reservation) if !client.admitCloseNotifications(reservation) =>
          reservation.cancel()
          client.shutdown()
        case Some(reservation) =>
          val deadline = new RequestDeadline(requestHeaderTimeout, () => client.shutdownFromDeadline())
          val bodyDeadline = new RequestDeadline(requestBodyTimeout, () => client.shutdownFromDeadline())
          val bodyState = new Object
          var activeBody: AnyRef = null
          var bodyAbandoned = false
          def bodyPending: Boolean = bodyState.synchronized(activeBody != null || bodyAbandoned)
          val detach = client.subscribeClose { _ => deadline.close(); bodyDeadline.close() }
          val input = Decoder(client.stream)
          val decoded = http11.decodeRequest(input,
            () => bodyState.synchronized { activeBody = new Object; bodyDeadline.start() },
            () => bodyState.synchronized {
              if (!bodyDeadline.complete()) throw new TimeoutException("HTTP request body assembly expired")
              activeBody = null
            })
          val requests = new Stream[F, Request[Stream[F, B]]] {
            def pull(): F[Option[Request[Stream[F, B]]]] = Effect[F].delayAsync {
              // Never reinterpret an unread/canceled body as the next request.
              if (bodyPending) client.shutdown().as(None)
              else {
                deadline.start()
                decoded.pull().map { request =>
                  if (!deadline.complete() && request.nonEmpty) throw new TimeoutException("HTTP request header assembly expired")
                  request.map { value =>
                    val owner = bodyState.synchronized(activeBody)
                    if (owner == null) value
                    else {
                      val input = value.body
                      @volatile var canceled = false
                      value.copy(body = new Stream[F, B] {
                        def pull(): F[Option[B]] =
                          if (canceled) Effect[F].none
                          else input.pull().recoverF { case NonFatal(error) => client.shutdown() *> Effect[F].fail(error) }
                        // Retain transport ownership so an early refusal can still
                        // send its response, then close instead of reusing framing.
                        def cancel(): F[Unit] = Effect[F].delay {
                          bodyState.synchronized {
                            if (activeBody eq owner) bodyAbandoned = true
                            canceled = true
                          }
                        }
                      })
                    }
                  }
                }.recoverF { case error: Http11.InvalidFraming =>
                  client.write(BytesLike[B].ascii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")) *>
                    client.shutdown() *> Effect[F].fail(error)
                }
              }
            }
            def cancel(): F[Unit] = Effect[F].delay(deadline.close()) *> client.shutdown()
          }
          def finish(): F[Unit] = Effect[F].delay {
            deadline.close()
            bodyDeadline.close()
            detach()
          } *> client.shutdown()
          val serving = requests
            .foreach { request =>
              for {
                response <- f(request, client, input).recoverF { case NonFatal(error) =>
                              ec.reportFailure(error)
                              Stream(InternalServerErrorMessage).mat() map { body =>
                                Response(
                                  Response.Status.InternalServerError,
                                  body,
                                  Nil,
                                  Some(InternalServerErrorMessage.length)
                                )
                              }
                            }
                _ <- {
                  val sending = for {
                    byteStream <- http11.renderResponse(response)
                    _          <- byteStream.foreach(client.write)
                    _          <- endWebSocket(response, client)
                  } yield ()
                  // A failed render, pull, or write still owns the response body.
                  sending.recoverF { case NonFatal(error) =>
                    response.body.cancel().recover { case NonFatal(_) => () } *>
                      client.shutdown() *>
                      Effect[F].fail(error)
                  }
                }
              } yield ()
            }.recoverF { case NonFatal(error) => finish() *> Effect[F].fail(error) } *> finish()
          def settled(): F[Unit] = Effect[F].delay(reservation.connectionFinished()) *> reservation.awaitReleased[F]()
          serving.recoverF { case NonFatal(error) => settled() *> Effect[F].fail(error) } *> settled()
      }
    }.map { server =>
      ServerSocket.ServerSocketHandler.beforeStop(server, () => Effect[F].delay(closeDispatcher.stopAdmission()))
    }.recoverF { case NonFatal(error) =>
      Effect[F].delay(closeDispatcher.stopAdmission()) *> Effect[F].fail(error)
    }
  }

  private def endWebSocket[F[_]: Effect, B](response: Response[_], client: RawDataSocket[F, B]): F[Unit] =
    if (response.status == Response.Status.SwitchingProtocols) client.shutdown()
    else Effect[F].unit
}
