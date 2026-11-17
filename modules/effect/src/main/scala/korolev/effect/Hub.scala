/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.effect

/**
 * Lossless fanout. A full subscriber buffer backpressures the shared reader.
 * Subscriber cancellation detaches only that subscriber, including native pull
 * cancellation before the pull's result settles. The independent reader owns
 * upstream fiber context. EOF drains accepted values; failure fails all
 * streams.
 */
final class Hub[F[_]: Effect, T](upstream: Stream[F, T], bufferSize: Int) {
  private val runtime = StreamRoutingRuntime(upstream, StreamRoutingMachine.Mode.Fanout, bufferSize)

  def newStreamUnsafe(): Stream[F, T] = runtime.stream(runtime.newLane())
  def newStream(): F[Stream[F, T]]    = Effect[F].delay(newStreamUnsafe())
}

object Hub {

  /**
   * @see
   *   Hub
   */
  def apply[F[_]: Effect, T](stream: Stream[F, T], bufferSize: Int = Int.MaxValue): Hub[F, T] =
    new Hub(stream, bufferSize)
}
