package spoonbill.performance

import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.server.WebSocketResponse

object CoreResponseAccess {
  def output(response: WebSocketResponse[Future]): Stream[Future, Bytes] = response match {
    case WebSocketResponse.Duplex(output, "json", _) => output
    case _ => throw new AssertionError("Guarded bootstrap must produce a JSON duplex session")
  }
  def dispose(response: WebSocketResponse[Future], input: Stream[Future, Bytes]): Future[Unit] =
    response.release()
}
