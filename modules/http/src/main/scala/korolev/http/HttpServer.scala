package spoonbill.http

import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeoutException
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{Decoder, Effect, Stream}
import spoonbill.effect.io.{RawDataSocket, ServerSocket}
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
    requestHeaderTimeout: FiniteDuration = 30.seconds
  )(
    f: Request[Stream[F, B]] => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] =
    withConnection[F, B](address, backlog, bufferSize, group, gracefulShutdown,
      maxRequestHeaderBytes, requestHeaderTimeout)((request, _) => f(request))

  /** Adapter boundary for observing and aborting an upgraded connection. */
  private[spoonbill] def withConnection[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean = false,
    maxRequestHeaderBytes: Int = 16 * 1024,
    requestHeaderTimeout: FiniteDuration = 30.seconds
  )(
    f: (Request[Stream[F, B]], RawDataSocket[F, B]) => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {

    require(requestHeaderTimeout > Duration.Zero, "requestHeaderTimeout must be positive")
    val InternalServerErrorMessage = BytesLike[B].ascii("Internal server error")
    val http11                     = new Http11[B](maxRequestHeaderBytes)

    ServerSocket.accept[F, B](address, backlog, bufferSize, group, gracefulShutdown) { client =>
      val deadline = new RequestHeaderDeadline[F](requestHeaderTimeout, () => client.shutdown())
      val detach = client.subscribeClose(_ => deadline.close())
      val decoded = http11.decodeRequest(Decoder(client.stream))
      val requests = new Stream[F, Request[Stream[F, B]]] {
        def pull(): F[Option[Request[Stream[F, B]]]] = Effect[F].delayAsync {
          deadline.start()
          decoded.pull().map { request =>
            if (!deadline.complete() && request.nonEmpty) throw new TimeoutException("HTTP request header assembly expired")
            request
          }
        }
        def cancel(): F[Unit] = Effect[F].delay(deadline.close()) *> client.shutdown()
      }
      def finish(): F[Unit] = Effect[F].delay {
        deadline.close()
        detach()
      } *> client.shutdown()
      requests
        .foreach { request =>
          for {
            response <- f(request, client).recoverF { case NonFatal(error) =>
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
    }
  }

  private def endWebSocket[F[_]: Effect, B](response: Response[_], client: RawDataSocket[F, B]): F[Unit] =
    if (response.status == Response.Status.SwitchingProtocols) client.shutdown()
    else Effect[F].unit
}
