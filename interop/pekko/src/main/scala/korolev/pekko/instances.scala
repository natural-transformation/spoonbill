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

package spoonbill.pekko

import spoonbill.data.BytesLike
import spoonbill.effect.{Effect, Stream}
import spoonbill.pekko.util.{SpoonbillStreamPublisher, SpoonbillStreamSubscriber, PekkoByteStringBytesLike}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.reactivestreams.Publisher
import scala.concurrent.ExecutionContext

object instances {

  implicit final class SinkCompanionOps(value: Sink.type) {
    def spoonbillStream[F[_]: Effect, T]: Sink[T, Stream[F, T]] = {
      val subscriber = new SpoonbillStreamSubscriber[F, T]()
      Sink
        .fromSubscriber(subscriber)
        .mapMaterializedValue(_ => subscriber)
    }
  }

  implicit final class StreamCompanionOps(value: Stream.type) {
    def fromPublisher[F[_]: Effect, T](publisher: Publisher[T]): Stream[F, T] = {
      val result = new SpoonbillStreamSubscriber[F, T]()
      publisher.subscribe(result)
      result
    }
  }

  implicit final class SpoonbillStreamsOps[F[_]: Effect, T](stream: Stream[F, T]) {

    /**
     * Converts spoonbill [[spoonbill.effect.Stream]] to [[Publisher]].
     *
     * If `fanout` is `true`, the `Publisher` will support multiple
     * `Subscriber`s and the size of the `inputBuffer` configured for this
     * operator becomes the maximum number of elements that the fastest
     * [[org.reactivestreams.Subscriber]] can be ahead of the slowest one before
     * slowing the processing down due to back pressure.
     *
     * If `fanout` is `false` then the `Publisher` will only support a single
     * `Subscriber` and reject any additional `Subscriber`s with
     * [[spoonbill.pekko.util.SpoonbillStreamPublisher.MultipleSubscribersProhibitedException]].
     */
    def asPublisher(fanout: Boolean = false)(implicit ec: ExecutionContext): Publisher[T] =
      new SpoonbillStreamPublisher(stream, fanout)

    def asPekkoSource(implicit ec: ExecutionContext): Source[T, NotUsed] = {
      val publisher = new SpoonbillStreamPublisher(stream, fanout = false)
      Source.fromPublisher(publisher)
    }
  }

  implicit final val pekkoByteStringBytesLike: BytesLike[ByteString] =
    new PekkoByteStringBytesLike()
}
