package spoonbill.http

import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{Decoder, Effect, Stream}
import spoonbill.effect.io.{RawDataSocket, ServerSocket}
import spoonbill.effect.syntax._
import spoonbill.http.protocol.Http11
import spoonbill.web.{Request, Response}
import scala.concurrent.ExecutionContext
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
    gracefulShutdown: Boolean = false
  )(
    f: Request[Stream[F, B]] => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] =
    withConnection[F, B](address, backlog, bufferSize, group, gracefulShutdown)((request, _) => f(request))

  /** Adapter boundary for observing and aborting an upgraded connection. */
  private[spoonbill] def withConnection[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean = false
  )(
    f: (Request[Stream[F, B]], RawDataSocket[F, B]) => F[Response[Stream[F, B]]]
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {

    val InternalServerErrorMessage = BytesLike[B].ascii("Internal server error")
    val http11                     = new Http11[B]

    ServerSocket.accept[F, B](address, backlog, bufferSize, group, gracefulShutdown) { client =>
      http11
        .decodeRequest(Decoder(client.stream))
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
                  endWebSocket(response, client) *>
                  Effect[F].fail(error)
              }
            }
          } yield ()
        }
    }
  }

  private def endWebSocket[F[_]: Effect, B](response: Response[_], client: RawDataSocket[F, B]): F[Unit] =
    if (response.status == Response.Status.SwitchingProtocols) client.shutdown()
    else Effect[F].unit
}
