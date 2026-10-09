package spoonbill.performance

import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.server.WebSocketResponse
import spoonbill.web.Response

object WebSocketBenchmarkResponse {
  def apply(input: Stream[Future, Bytes]): WebSocketResponse[Future] =
    WebSocketResponse(Response(Response.Status.Ok, input, Nil, None), "json")
}
