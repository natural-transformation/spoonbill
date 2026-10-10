package spoonbill.performance

import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.WebSocketResponse

object WebSocketBenchmarkResponse {
  def apply(input: Stream[Future, Bytes]): WebSocketResponse[Future] = {
    implicit val effect: Effect[Future] = Effect.futureEffect
    WebSocketResponse.Duplex(input, "json", WebSocketResponse.releaseOnce(() => input.cancel()))
  }
}
