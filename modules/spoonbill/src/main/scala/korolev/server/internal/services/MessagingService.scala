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
import scala.util.control.NonFatal
import spoonbill.server.DeflateCompressionService
import spoonbill.web.Request.Head
import spoonbill.web.Response
import spoonbill.web.Response.Status
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

/**
 * @param pollWait
 *   how long a long poll waits for a frame at most, half the idle timeout of a
 *   session. It is then answered with `204`.
 */
private[spoonbill] final class MessagingService[F[_]: Effect](
  reporter: Reporter,
  commonService: CommonService[F],
  sessionsService: SessionsService[F, _, _],
  compressionSupport: Option[DeflateCompressionService[F]],
  pollWait: FiniteDuration,
  webSocketCompressionEnabled: Boolean = false,
  pollTiming: Option[LongPollingDelivery.Timing[F]] = None
) {

  import MessagingService._

  /**
   * The long-polling entry of a session: its page, the input of its view, and
   * the delivery of the view's frames. Only a first attach makes an entry; a
   * message never does.
   */
  private final case class TopicEntry(page: UUID, incoming: Queue[F, String], delivery: LongPollingDelivery[F])

  private val timing = pollTiming.getOrElse(new LongPollingDelivery.Timing[F] {
    private val scheduler = Scheduler[F]
    def scheduleOnce(delay: FiniteDuration)(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]] =
      scheduler.scheduleOnce(delay)(job)
  })

  /**
   * Poll message from session's ongoing queue.
   */
  private def messageCode(message: String): String =
    message
      .dropWhile(_ == '[')
      .takeWhile(ch => ch != ',' && ch != ']' && !ch.isWhitespace)

  /** The page id and the cursor that a long-polling request names. */
  private def cursor(rh: Head): Option[PollAdmissionMachine.Cursor] =
    PollAdmissionMachine.cursor(rh.param("page"), rh.param("seen"))

  /**
   * One poll. The page names itself and the last frame that it applied, and
   * the delivery answers with the frame after it. Everything that does not fit
   * is told to reload. See [[PollAdmissionMachine.route]].
   */
  def longPollingSubscribe(qsid: Qsid, rh: Head): F[HttpResponse[F]] = poll(qsid, rh, cursor(rh))

  private def poll(qsid: Qsid, rh: Head, request: Option[PollAdmissionMachine.Cursor]): F[HttpResponse[F]] = {
    val entry = longPollingTopics.get(qsid)
    (PollAdmissionMachine.route(request, entry.map(_.page)), request, entry) match {
      case (PollAdmissionMachine.Route.Deliver, Some(value), Some(current)) =>
        current.delivery.poll(value.seen).flatMap(answer(qsid, _))
      case (PollAdmissionMachine.Route.FirstAttach, Some(value), _) => firstAttach(qsid, rh, value)
      case _                                                        => answer(qsid, LongPollingDelivery.PollAnswer.Reload)
    }
  }

  /**
   * A first attach: one step that a cancelled request cannot stop half way.
   * The entry goes into the table first; a poll that loses that race decides
   * again and finds it. The session service builds the view for the entry from
   * the stored state, after it claimed the fresh-page record of the page. Every
   * other outcome takes the entry out again, and the polls that joined it are
   * told to reload.
   */
  private def firstAttach(qsid: Qsid, rh: Head, request: PollAdmissionMachine.Cursor): F[HttpResponse[F]] = {
    val created = TopicEntry(request.page, Queue[F, String](), new LongPollingDelivery[F](pollWait, timing, reporter))
    def leave(): F[Unit] =
      Effect[F].delay { longPollingTopics.remove(qsid, created); () } *>
        created.delivery.abandon() *>
        created.incoming.close()
    Effect[F].uncancelable {
      Effect[F].delay(longPollingTopics.putIfAbsent(qsid, created)).flatMap {
        case Some(_) => Effect[F].pure(FirstAttach.Lost)
        case None =>
          // The entry leaves the table when the view cancels its input, as before.
          created.incoming.cancelSignal.runAsync(_ => longPollingTopics.remove(qsid, created))
          sessionsService
            .createAppIfNeeded(
              qsid,
              rh,
              created.incoming.stream,
              PollAdmissionMachine.Transport.Poll,
              Some(request.page),
              Some(created.delivery.progress)
            )
            .flatMap {
              case SessionStart.Owned(frames, _) => created.delivery.own(frames).map(_ => FirstAttach.Owns)
              case _                             => leave().map(_ => FirstAttach.Gone)
            }
            .recoverF { case error => leave().flatMap(_ => Effect[F].fail[FirstAttach](error)) }
      }
    }.flatMap {
      case FirstAttach.Owns => created.delivery.poll(request.seen).flatMap(answer(qsid, _))
      // The poll that lost the race decides again, with the entry that won.
      case FirstAttach.Lost => poll(qsid, rh, Some(request))
      case FirstAttach.Gone => answer(qsid, LongPollingDelivery.PollAnswer.Reload)
    }
  }

  private def answer(qsid: Qsid, value: LongPollingDelivery.PollAnswer): F[HttpResponse[F]] = value match {
    case LongPollingDelivery.PollAnswer.Frame(message, number, dom) =>
      Effect[F].delay {
        reporter.debug(s"Long-polling send to $qsid: frame=$number code=${messageCode(message)} length=${message.length}")
      } *> HttpResponse(
        status = Response.Status.Ok,
        text = message,
        headers = commonResponseHeaders ++ Seq(FrameHeader -> number.toString, DomHeader -> dom.toString)
      )
    case LongPollingDelivery.PollAnswer.Reload =>
      HttpResponse(status = Response.Status.Ok, text = Frontend.ReloadMessage, headers = commonResponseHeaders)
    case LongPollingDelivery.PollAnswer.Stale     => Effect[F].pure(commonGoneResponse)
    case LongPollingDelivery.PollAnswer.NoContent => Effect[F].pure(commonNoContentResponse)
  }

  /**
   * One message of a page. It goes to the view of the page's entry, and it
   * never makes an entry. `410`: no view of this page can take it, its queue is
   * closed, or it claims more frames than the view has handed out.
   */
  def longPollingPublish(qsid: Qsid, rh: Head, data: Stream[F, Bytes]): F[HttpResponse[F]] =
    (cursor(rh), longPollingTopics.get(qsid)) match {
      case (Some(request), Some(entry)) if PollAdmissionMachine.accepts(Some(request), Some(entry.page)) =>
        for {
          message <- data.fold(Bytes.empty)(_ ++ _).map(_.asUtf8String)
          _ <- Effect[F].delay {
                 reporter.debug(
                   s"Long-polling publish received for $qsid: code=${messageCode(message)} length=${message.length}"
                 )
               }
          taken <- entry.delivery.message(request.seen) match {
                     case PollDeliveryMachine.Message.Deliver => entry.incoming.offer(message)
                     case PollDeliveryMachine.Message.Gone    => Effect[F].pure(false)
                   }
        } yield if (taken) commonOkResponse else commonGoneResponse
      case _ => data.cancel().recover { case NonFatal(_) => () }.map(_ => commonGoneResponse)
    }

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
      if (MessagingService.selectedProtocol(protocols, webSocketCompressionEnabled) == ProtocolJsonDeflate) {
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
    val incoming = incomingMessages.mapAsync(decoder)
    sessionsService.createAppIfNeeded(qsid, rh, incoming) flatMap {
      case SessionStart.Attached(outgoing, releaseSession) =>
        val output = outgoing.mapAsync(encoder)
        // The session owns its output. mapAsync adds no resource to dispose,
        // so avoid canceling the same frontend a second time after cleanup.
        val release = WebSocketResponse.releaseOnce[F](releaseSession)
        Effect[F].pure(WebSocketResponse.Duplex(output, selectedProtocol, release))
      case SessionStart.Refused() | SessionStart.Owned(_, _) =>
        // A poll owns the view of this session. The socket gets the reload
        // frame and closes; the view goes on. A second document with this
        // session id so reloads once, into a session of its own, through the
        // reload guard. The page that moved on to long polling ignores the
        // frame: that socket is not its transport any more. Decided by the user
        // on 2026-11-22, after the review of PR 42; it got nothing before, and
        // a second document whose WebSocket works reconnected every 200 ms.
        Stream(Frontend.ReloadMessage).mat().map { messages =>
          val output = messages.mapAsync(encoder)
          val release = WebSocketResponse.releaseOnce[F](() =>
            incoming.cancel().recover { case NonFatal(_) => () } *>
              output.cancel().recover { case NonFatal(_) => () }
          )
          WebSocketResponse.SendThenClose(output, selectedProtocol, release)
        }
      case SessionStart.Reload() =>
        // The view is gone after a restart, or guarded recovery declined it.
        // The finite reload frame is independent of application input.
        Stream(Frontend.ReloadMessage).mat().map { messages =>
          val output = messages.mapAsync(encoder)
          val release = WebSocketResponse.releaseOnce[F](() =>
            incoming.cancel().recover { case NonFatal(_) => () } *>
              output.cancel().recover { case NonFatal(_) => () }
          )
          WebSocketResponse.SendThenClose(output, selectedProtocol, release)
        }
    }
  }

  /**
   * Sessions created via long polling subscription takes messages from topics
   * stored in this table.
   */
  private val longPollingTopics = TrieMap.empty[Qsid, TopicEntry]

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

  /** A poll that is answered with nothing yet. The page polls again with the same cursor. */
  private val commonNoContentResponse = Response(
    status = Response.Status(204, "No Content"),
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

}

private[spoonbill] object MessagingService {

  /** How a first attach of a poll ended. */
  private enum FirstAttach {

    /** Another poll put its entry into the table first. */
    case Lost

    /** This entry owns the view that the attach built. */
    case Owns

    /** The attach built no view for this entry. The page reloads. */
    case Gone
  }

  /** The number of a frame of a long-polling answer. */
  final val FrameHeader = "spoonbill-frame"

  /** The DOM revision after that frame: what the page shows once it has applied it. */
  final val DomHeader = "spoonbill-dom"

  private val ProtocolJsonDeflate = "json-deflate"
  private val ProtocolJson        = "json"

  private[services] def selectedProtocol(protocols: Seq[String], compressionEnabled: Boolean): String =
    if (compressionEnabled && protocols.contains(ProtocolJsonDeflate)) ProtocolJsonDeflate else ProtocolJson
}
