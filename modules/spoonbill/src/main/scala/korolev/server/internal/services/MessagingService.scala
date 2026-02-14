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

package spoonbill.server.internal.services

import java.nio.charset.StandardCharsets
import java.util.zip.{Deflater, Inflater}
import spoonbill.Qsid
import spoonbill.data.{Bytes, BytesLike}
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, Stream}
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend
import spoonbill.server.{HttpResponse, WebSocketResponse}
import spoonbill.server.DeflateCompressionService
import spoonbill.web.Request.Head
import spoonbill.web.Response
import spoonbill.web.Response.Status
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

private[spoonbill] final class MessagingService[F[_]: Effect](
  reporter: Reporter,
  commonService: CommonService[F],
  sessionsService: SessionsService[F, _, _],
  compressionSupport: Option[DeflateCompressionService[F]],
  orphanTopicTimeout: FiniteDuration
) {

  import MessagingService._

  private final case class TopicEntry(
    queue: Queue[F, String],
    subscribed: AtomicBoolean,
    lastActivityMillis: AtomicLong,
    orphanCleanup: AtomicReference[Option[Scheduler.JobHandler[F, Unit]]]
  )

  private val scheduler = Scheduler[F]
  private def runAsyncForget(effect: F[Unit]): Unit =
    Effect[F].runAsync(effect) {
      case Left(err) => reporter.error("Unhandled error", err)
      case Right(_)  => ()
    }

  /**
   * Poll message from session's ongoing queue.
   */
  private def messageCode(message: String): String =
    message
      .dropWhile(_ == '[')
      .takeWhile(ch => ch != ',' && ch != ']' && !ch.isWhitespace)

  def longPollingSubscribe(qsid: Qsid, rh: Head): F[HttpResponse[F]] =

    for {
      _        <- sessionsService.createAppIfNeeded(qsid, rh, createTopic(qsid))
      maybeApp <- sessionsService.getApp(qsid)
      // See webSocketMessaging()
      maybeMessage <- maybeApp.fold(SomeReloadMessageF)(_.frontend.outgoingMessages.pull())
      response <- maybeMessage match {
                    case None => Effect[F].pure(commonGoneResponse)
                    case Some(message) =>
                      Effect[F].delay {
                        reporter.debug(
                          s"Long-polling send to $qsid: code=${messageCode(message)} length=${message.length}"
                        )
                      } *>
                      HttpResponse(
                        status = Response.Status.Ok,
            text = message,
                        headers = commonResponseHeaders
                      )
                  }
    } yield {
      response
    }

  /**
   * Push message to session's incoming queue.
   */
  def longPollingPublish(qsid: Qsid, data: Stream[F, Bytes]): F[HttpResponse[F]] =
    for {
      entry   <- Effect[F].delay(getOrCreateTopicUnsafe(qsid))
      _       <- scheduleOrphanCleanup(qsid, entry)
      message <- data.fold(Bytes.empty)(_ ++ _).map(_.asUtf8String)
      _ <- Effect[F].delay {
             reporter.debug(
               s"Long-polling publish received for $qsid: code=${messageCode(message)} length=${message.length}"
             )
           }
      _ <- entry.queue.enqueue(message)
    } yield commonOkResponse

  private lazy val inflaters = ThreadLocal.withInitial(() => new Inflater(true))
  private lazy val deflaters = ThreadLocal.withInitial(() => new Deflater(Deflater.DEFAULT_COMPRESSION, true))

  private lazy val wsJsonDeflateDecoder = (bytes: Bytes) => {
    val inflater   = inflaters.get()
    val inputArray = bytes.asArray

    val chunkSize          = 1024
    val outputBlocks       = new ListBuffer[Array[Byte]]()
    var totalBytesInflated = 0

    inflater.reset()
    inflater.setInput(inputArray)

    while (!inflater.finished()) {
      val outputArray   = new Array[Byte](chunkSize)
      val bytesInflated = inflater.inflate(outputArray)
      totalBytesInflated += bytesInflated

      // Store the block even if partially filled
      outputBlocks += java.util.Arrays.copyOf(outputArray, bytesInflated)
    }

    // Concatenate all the blocks to form the final decompressed string
    val finalOutputArray = outputBlocks.flatten.toArray
    Effect[F].pure(new String(finalOutputArray, 0, totalBytesInflated, StandardCharsets.UTF_8))
  }

  private lazy val wsJsonDeflateEncoder = (message: String) => {
    val deflater = deflaters.get()

    // Initialize input as a byte array from the string
    val inputArray = message.getBytes(StandardCharsets.UTF_8)

    // Clear and reset deflater
    deflater.reset()

    // Set the input data for the deflater
    deflater.setInput(inputArray)
    deflater.finish()

    // Temporary buffer to hold deflation result
    val tempOutputArray = new Array[Byte](1024)

    // Initialize a ListBuffer to hold multiple output blocks
    val outputBlocks = ListBuffer[Array[Byte]]()

    // Deflate the input in chunks
    while (!deflater.finished()) {
      val bytesDeflated = deflater.deflate(tempOutputArray)
      outputBlocks += java.util.Arrays.copyOf(tempOutputArray, bytesDeflated)
    }

    // Concatenate all blocks to form the final array
    val compressedArray = outputBlocks.flatten.toArray

    // Convert it back to spoonbill.data.Bytes
    Effect[F].pure(Bytes.wrap(compressedArray))
  }

  private val wsJsonDecoder = (bytes: Bytes) => Effect[F].pure(bytes.asUtf8String)
  private val wsJsonEncoder = (message: String) => Effect[F].pure(BytesLike[Bytes].utf8(message))

  def webSocketMessaging(
    qsid: Qsid,
    rh: Head,
    incomingMessages: Stream[F, Bytes],
    protocols: Seq[String]
  ): F[WebSocketResponse[F]] = {
    val (selectedProtocol, decoder, encoder) = {
      // Support for protocol compression. A client can tell us
      // it can decompress the messages.
      if (protocols.contains(ProtocolJsonDeflate)) {
        compressionSupport match {
          case Some(DeflateCompressionService(decoder, encoder)) =>
            (ProtocolJsonDeflate, decoder, encoder)
          case None =>
            (ProtocolJsonDeflate, wsJsonDeflateDecoder, wsJsonDeflateEncoder)
        }
      } else {
        (ProtocolJson, wsJsonDecoder, wsJsonEncoder)
      }
    }
    sessionsService.createAppIfNeeded(qsid, rh, incomingMessages.mapAsync(decoder)) flatMap { _ =>
      sessionsService.getApp(qsid) flatMap {
        case Some(app) =>
          val httpResponse = Response(Status.Ok, app.frontend.outgoingMessages.mapAsync(encoder), Nil, None)
          Effect[F].pure(WebSocketResponse(httpResponse, selectedProtocol))
        case None =>
          // Respond with reload message because app was not found.
          // In this case it means that server had ben restarted and
          // do not have an information about the state which had been
          // applied to render of the page on a client side.
          Stream(Frontend.ReloadMessage).mat().map { messages =>
            val httpResponse = Response(Status.Ok, messages.mapAsync(encoder), Nil, None)
            WebSocketResponse(httpResponse, selectedProtocol)
          }
      }
    }
  }

  /**
   * Sessions created via long polling subscription takes messages from topics
   * stored in this table.
   */
  private val longPollingTopics = TrieMap.empty[Qsid, TopicEntry]

  private[services] def topicExists(qsid: Qsid): Boolean =
    longPollingTopics.contains(qsid)

  /**
   * Same headers in all responses
   */
  private val commonResponseHeaders = Seq(
    "cache-control" -> "no-cache",
    "content-type"  -> "application/json"
  )

  /**
   * Same response for all 'publish' requests.
   */
  private val commonOkResponse = Response(
    status = Response.Status.Ok,
    body = Stream.empty[F, Bytes],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  /**
   * Same response for all 'subscribe' requests where outgoing stream is
   * consumed.
   */
  private val commonGoneResponse = Response(
    status = Response.Status.Gone,
    body = Stream.empty[F, Bytes],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  private def getOrCreateTopicUnsafe(qsid: Qsid): TopicEntry =
    longPollingTopics.get(qsid) match {
      case Some(existing) =>
        existing
      case None =>
        val entry = TopicEntry(
          queue = Queue[F, String](),
          subscribed = new AtomicBoolean(false),
          lastActivityMillis = new AtomicLong(System.currentTimeMillis()),
          orphanCleanup = new AtomicReference(None)
        )
        longPollingTopics.putIfAbsent(qsid, entry) match {
          case Some(existing) =>
            existing
          case None =>
            reporter.debug(s"Create long-polling topic for $qsid")
            entry.queue.cancelSignal.runAsync(_ => longPollingTopics.remove(qsid))
            entry
        }
    }

  private[services] def createTopic(qsid: Qsid): Stream[F, String] = {
    val entry = getOrCreateTopicUnsafe(qsid)
    if (entry.subscribed.compareAndSet(false, true)) {
      runAsyncForget(cancelOrphanCleanup(entry))
    }
    entry.queue.stream
  }

  private def cancelOrphanCleanup(entry: TopicEntry): F[Unit] =
    Effect[F].delay(entry.orphanCleanup.getAndSet(None)).flatMap {
      case Some(job) => job.cancel()
      case None      => Effect[F].unit
    }

  private def scheduleOrphanCleanup(qsid: Qsid, entry: TopicEntry): F[Unit] = {
    if (entry.subscribed.get()) {
      Effect[F].unit
    } else if (orphanTopicTimeout.toMillis <= 0) {
      Effect[F].unit
    } else {
      for {
        _   <- cancelOrphanCleanup(entry)
        _   <- Effect[F].delay(entry.lastActivityMillis.set(System.currentTimeMillis()))
        job <- scheduler.scheduleOnce(orphanTopicTimeout) {
                 Effect[F].delay {
                   if (!entry.subscribed.get()) {
                     val elapsed = System.currentTimeMillis() - entry.lastActivityMillis.get()
                     if (elapsed >= orphanTopicTimeout.toMillis) {
                       reporter.debug(s"Remove orphan long-polling topic for $qsid")
                       longPollingTopics.remove(qsid)
                       ()
                     }
                   }
                 }
               }
        _ <- Effect[F].delay(entry.orphanCleanup.set(Some(job)))
      } yield ()
    }
  }
}

private[spoonbill] object MessagingService {

  private val ProtocolJsonDeflate = "json-deflate"
  private val ProtocolJson        = "json"

  def SomeReloadMessageF[F[_]: Effect]: F[Option[String]] =
    Effect[F].pure(Option(Frontend.ReloadMessage))
}
