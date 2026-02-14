package spoonbill.server

import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.data.syntax.*
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.io.ServerSocket
import spoonbill.effect.syntax.*
import spoonbill.http.HttpServer
import spoonbill.http.protocol.WebSocketProtocol
import spoonbill.web.{Headers, Request}
import scala.concurrent.ExecutionContext

object standalone {

  def buildServer[F[_]: Effect, B: BytesLike](
    service: SpoonbillService[F],
    address: SocketAddress,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean
  )(implicit ec: ExecutionContext): F[ServerSocket.ServerSocketHandler[F]] = {
    val webSocketProtocol = new WebSocketProtocol[B]
    HttpServer[F, B](address, group = group, gracefulShutdown = gracefulShutdown) { request =>
      val protocols = request
        .header(Headers.SecWebSocketProtocol)
        .toSeq
        .flatMap(_.split(','))
        .filterNot(_.isBlank)
      webSocketProtocol.findIntention(request) match {
        case Some(intention) =>
          val f = webSocketProtocol.upgrade[F](intention) {
            (request: Request[Stream[F, WebSocketProtocol.Frame.Merged[B]]]) =>
              val b2 = request.body.collect { case WebSocketProtocol.Frame.Binary(message, _) =>
                message.as[Bytes]
              }
              // TODO service.ws should work with websocket frame
              val wsRequest = WebSocketRequest(request.copy(body = b2), protocols)
              service.ws(wsRequest).map { wsResponse =>
                val response = wsResponse.httpResponse
                val updatedBody: Stream[F, WebSocketProtocol.Frame.Merged[B]] =
                  response.body.map(m => WebSocketProtocol.Frame.Binary(m.as[B]))
                val updatedHeaders = (Headers.SecWebSocketProtocol -> wsResponse.selectedProtocol) +: response.headers
                response.copy(body = updatedBody, headers = updatedHeaders)
              }
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

}
