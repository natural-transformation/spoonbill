package spoonbill.performance

import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.server.WebSocketResponse

object CoreResponseAccess {
  def output(response: WebSocketResponse[Future]): Stream[Future, Bytes] = {
    require(response.selectedProtocol == "json")
    response.httpResponse.body
  }
  def dispose(response: WebSocketResponse[Future], input: Stream[Future, Bytes]): Future[Unit] =
    input.cancel()
}
