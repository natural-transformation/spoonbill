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

package spoonbill.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.nio.charset.StandardCharsets
import java.util.UUID
import spoonbill.Context.FileHandler
import spoonbill.Metrics
import spoonbill.Metrics
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, Stream}
import spoonbill.effect.syntax._
import spoonbill.web.{FormData, PathAndQuery}
import avocet.Id
import avocet.events.EventId
import avocet.impl.DiffRenderContext.ChangesPerformer
import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.SessionAccessDenied

/**
 * Typed interface to client side
 */
final class Frontend[F[_]: Effect](
  incomingMessages: Stream[F, String],
  heartbeatLimit: Option[Int],
  connectionId: Option[ConnectionId] = None,
  authorize: Option[() => F[Unit]] = None,
  beforeUserCallback: Option[() => F[Unit]] = None,
  captureOutputAuthorization: Option[() => F[() => F[Unit]]] = None,
  rpcSettings: Frontend.RpcSettings = Frontend.defaultRpcSettings,
  rpcDeadlineScheduler: Option[Frontend.RpcDeadlineScheduler] = None,
  viewRecoveryEpoch: Option[spoonbill.security.Versions.ViewOwnershipEpoch] = None,
  captureSensitiveAuthorization: Option[spoonbill.sensitive.Purpose => F[SensitiveAuthorization[F]]] = None
)(implicit
  reporter: Reporter,
  ec: ExecutionContext
) {

  import Frontend._

  private val actionConnectionId = connectionId.getOrElse(ConnectionId.fromUuid(UUID.randomUUID()))
  private val outputClosed = new AtomicBoolean(false)
  private[spoonbill] def guarded: Boolean = authorize.nonEmpty
  private[spoonbill] def recoversView: Boolean = viewRecoveryEpoch.nonEmpty
  private val renderRevision = new AtomicLong(0L)
  private val sensitiveNavigation = new AtomicLong(0L)
  private val sensitiveDeparture = new AtomicLong(0L)
  private val handledSensitiveDeparture = new AtomicLong(0L)
  private val recoveryStarted = new AtomicBoolean(false)
  private val renderPublication = new Object

  private[spoonbill] def selectFromPublishedView[A](expected: Option[Long])(select: => A): F[A] =
    Effect[F].delay {
      renderPublication.synchronized {
        expected.foreach { value =>
          if (!recoveryStarted.get() || value != renderRevision.get()) throw new SessionAccessDenied
        }
        select
      }
    }.recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }

  private def checkRenderRevision(expected: Option[Long]): F[Unit] = selectFromPublishedView(expected)(())

  private def requireOpen(): F[Unit] = Effect[F].delay {
    if (outputClosed.get()) throw new SessionAccessDenied
  }

  private[spoonbill] def authorizeInteraction(): F[Unit] =
    requireOpen().flatMap { _ =>
      authorize.fold(Effect[F].unit)(check => Effect[F].delayAsync(check()))
    }.flatMap(_ => requireOpen()).recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }

  private[spoonbill] def newAuthenticatedActionBinding(expectedDeparture: Option[Long] = None): F[spoonbill.action.InvocationBinding] =
    if (authorize.isEmpty) Effect[F].fail(new SessionAccessDenied)
    else newActionBinding(expectedDeparture).flatMap { binding =>
      // Bind before awaiting authorization; it must never adopt a newer browser
      // generation merely because this check completed after departure.
      authorizeInteraction().as(binding)
    }

  private[spoonbill] def completeAuthentication(completionId: UUID): F[Unit] =
    if (authorize.isEmpty) Effect[F].fail(new SessionAccessDenied)
    else sensitiveRuntime.clearAll(spoonbill.sensitive.ClearReason.Navigation)
      .flatMap(_ => send(Procedure.CompleteAuthentication.code, completionId.toString))

  private[spoonbill] def presentSensitive(region: spoonbill.sensitive.RegionId, purpose: spoonbill.sensitive.Purpose,
    payload: spoonbill.sensitive.SensitivePayload, lifetime: FiniteDuration): F[spoonbill.sensitive.DisclosureOutcome] =
    captureSensitiveAuthorization.filter(_ => guarded) match {
      case None => Effect[F].fail(new SessionAccessDenied)
      case Some(capture) => authorizeInteraction().flatMap(_ => Effect[F].delayAsync(capture(purpose))).flatMap { permit =>
        sensitiveRuntime.present(region, purpose, payload, lifetime, permit) { id => Effect[F].delay {
          // The ordinary output guard runs here; the dedicated runtime performs
          // the sensitive producer/audience check once at the actual handoff.
          if (outputClosed.get() || !outgoingQueue.offerUnsafe(Outgoing("", Some(() => Effect[F].unit),
              () => sensitiveRuntime.pending(id), Some(id)))) throw new SessionAccessDenied
        }}
      }
    }

  private[spoonbill] def clearSensitive(region: spoonbill.sensitive.RegionId): F[Unit] = sensitiveRuntime.clearRegion(region)

  /** Called only after the serialized history action has completed. A generic
    * DOM patch cannot reopen a client navigation fence.
    */
  private[spoonbill] def completeSensitiveNavigation(counter: Long): F[Unit] =
    sensitiveRuntime.clearAll(spoonbill.sensitive.ClearReason.Navigation)
      .flatMap(_ => sendRaw(s"[24,$counter]", protectedOutput = false, rejectIfFull = true))
      .recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }

  private[spoonbill] def close(): F[Unit] =
    Effect[F].delay {
      outputClosed.set(true)
      val waiting = pendingUserActions.values.toList
      pendingUserActions.clear()
      waiting.foreach(_.apply())
      val downloads = downloadFiles.values.toList
      downloadFiles.clear()
      downloads.foreach(meta => Effect[F].delayAsync(meta.stream.cancel()).runAsyncForget)
    }
      .flatMap(_ => stringRequests.close())
      .flatMap(_ => formRequests.close())
      .flatMap(_ => fileRequests.close())
      .flatMap(_ => fileNameRequests.close())
      .flatMap(_ => Effect[F].delay(sensitiveOutcomes.close()))
      .flatMap(_ => sensitiveRuntime.close())
      .flatMap(_ => outgoingQueue.stream.cancel())
      .flatMap(_ => userActions.stream.cancel())
      .flatMap(_ => guardedRacks.toList.flatten.map(_.close()).sequence.unit)
      .flatMap(_ => incomingMessages.cancel())

  /** One admission/execution queue for DOM, history and user callbacks. Browser
    * replies and liveness are processed outside it so a handler can await RPC.
    */
  private[spoonbill] def runUserAction[A](operation: => F[A], expectedRevision: Option[Long] = None): F[A] =
    enqueueUserAction(operation, expectedRevision, () => None)

  /** A queued browser action from before departure is discarded before selecting
    * its revision or constructing a domain effect. Already running actions finish
    * before the serialized recovery barrier; browser RPC replies remain live.
    */
  private[spoonbill] def runBrowserAction(operation: => F[Unit], expectedRevision: Option[Long], departure: Long): F[Unit] =
    enqueueUserAction(operation, expectedRevision, () => Option.when(departure != sensitiveDeparture.get())(()))

  private def enqueueUserAction[A](operation: => F[A], expectedRevision: Option[Long], staleResult: () => Option[A]): F[A] =
    if (!guarded) Effect[F].delayAsync(operation)
    else Effect[F].promise[A] { callback =>
      val id = UUID.randomUUID()
      val completed = new AtomicBoolean(false)
      def finish(result: Either[Throwable, A]): Unit =
        if (completed.compareAndSet(false, true)) {
          pendingUserActions.remove(id)
          callback(result)
        }
      pendingUserActions.put(id, () => finish(Left(new SessionAccessDenied)))
      val job = () => Effect[F].delay(staleResult()).flatMap {
        case Some(result) => Effect[F].pure(result)
        case None => beforeUserCallback.fold(Effect[F].unit)(ready => Effect[F].delayAsync(ready()))
          .flatMap(_ => checkRenderRevision(expectedRevision))
          .flatMap(_ => authorizeInteraction()).flatMap(_ => checkRenderRevision(expectedRevision))
          .flatMap(_ => Effect[F].delayAsync {
            // Readiness/authorization may have yielded to departure. This last
            // check shares its thunk with construction of the application effect.
            staleResult() match {
              case Some(result) => Effect[F].pure(result)
              case None => operation
            }
          })
      }
        .map(result => finish(Right(result))).recover { case error => finish(Left(error)) }
      if (outputClosed.get() || !userActions.offerUnsafe(job)) {
        close().runAsyncForget
        finish(Left(new SessionAccessDenied))
      }
    }

  private[spoonbill] def browserAdmission(departure: Long): spoonbill.action.InvocationAdmission =
    new spoonbill.action.InvocationAdmission(() =>
      !outputClosed.get() && (!guarded || departure == sensitiveDeparture.get()))

  private[spoonbill] def newActionBinding(expectedDeparture: Option[Long] = None): F[spoonbill.action.InvocationBinding] =
    Effect[F].delay(new spoonbill.action.InvocationBinding(
      spoonbill.security.Identifiers.InvocationId.fromUuid(java.util.UUID.randomUUID()),
      actionConnectionId,
      Option.when(guarded && captureSensitiveAuthorization.nonEmpty)(sensitiveOutcomes),
      browserAdmission(expectedDeparture.getOrElse(sensitiveDeparture.get()))
    ))

  private val lastDescriptor            = new AtomicLong(0)
  private val avgDiffTime               = new AtomicLong(0)
  private val remoteDomChangesPerformer = new RemoteDomChangesPerformer()
  private val scheduler                 = Scheduler[F]

  private val customCallbacks  = mutable.Map.empty[String, String => F[Unit]]
  private val downloadFiles    = TrieMap.empty[String, DownloadFileMeta[F]]
  private val rpcDeadlines = rpcDeadlineScheduler.getOrElse(new RpcDeadlineScheduler {
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
      val callback = new java.util.concurrent.atomic.AtomicReference(Option(expire))
      val runtimeEffect = Effect[F]
      val task = scheduler.unsafeScheduleOnce(delay)(runtimeEffect.delay(callback.getAndSet(None).foreach(_.apply())))
      // A canceled TimerTask may stay in the scheduler queue until its due time;
      // do not let that retain the request callback or the owning connection.
      () => { callback.set(None); task.unsafeCancel() }
    }
  })
  private val stringRequests = new OwnedBrowserRequests[F, String](rpcDeadlines, rpcSettings.maxPendingPerKind, () => new SessionAccessDenied)
  private lazy val sensitiveOutcomes = new SensitiveDisclosureScope(rpcDeadlines)
  private val formRequests = new OwnedBrowserRequests[F, FormData](rpcDeadlines, rpcSettings.maxPendingPerKind, () => new SessionAccessDenied)
  private val fileRequests = new OwnedBrowserRequests[F, Stream[F, Bytes]](rpcDeadlines, rpcSettings.maxPendingPerKind, () => new SessionAccessDenied)
  private val fileNameRequests = new OwnedBrowserRequests[F, List[(String, Long)]](rpcDeadlines, rpcSettings.maxPendingPerKind, () => new SessionAccessDenied)

  private case class Outgoing(message: String, producer: Option[() => F[Unit]], pending: () => Boolean,
    sensitiveId: Option[spoonbill.sensitive.PresentationId] = None) {
    override def toString: String = "Outgoing(<redacted>)"
  }
  private val outgoingQueue = if (authorize.nonEmpty) Queue[F, Outgoing](256) else Queue[F, Outgoing]()
  private lazy val sensitiveRuntime = new SensitiveRuntime[F](actionConnectionId, rpcDeadlines, (id, region) =>
    sendRaw(s"""[23,"$actionConnectionId","${id.value}","${region.value}"]""",
      protectedOutput = false, rejectIfFull = true).recoverF { case _ => close() })
  private val pendingUserActions = TrieMap.empty[UUID, () => Unit]
  private val userActions = Queue[F, () => F[Unit]](128)
  if (guarded) userActions.stream.foreach(job => job()).runAsyncForget

  private case class Incoming(code: Int, args: String, revision: Option[Long] = None, departure: Long = 0L) {
    override def toString: String = s"Incoming($code,<redacted>)"
  }
  private val guardedRacks = Option.when(guarded)(Vector.fill(3)(Queue[F, Incoming](128)))
  private def rack(message: Incoming): Int = message match {
    case Incoming(CallbackType.DomEvent.code, _, _, _) => 0
    case Incoming(CallbackType.History.code, _, _, _) => 1
    case _ => 2
  }
  private val List(rawDomEvents, rawBrowserHistoryChanges, rawClientMessages) = guardedRacks match {
    case Some(queues) => queues.map(_.stream).toList
    case None => incomingMessages.map(parseMessage).sort(3)(rack)
  }

  val outgoingMessages: Stream[F, String] = outgoingQueue.stream.mapAsync { outgoing =>
    if (!outgoing.pending()) Effect[F].pure(Option.empty[String])
    else {
      val allowed = outgoing.producer match {
        case Some(check) => authorizeInteraction().flatMap(_ => Effect[F].delayAsync(check())).flatMap(_ => requireOpen())
        case None => requireOpen()
      }
      allowed.flatMap { _ => outgoing.sensitiveId match {
        case Some(id) => sensitiveRuntime.pull(id)
        case None => Effect[F].pure(Option.when(outgoing.pending())(outgoing.message))
      }}
        .recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }
    }
  }.collect { case Some(message) => message }

  val domEventMessages: Stream[F, Frontend.DomEventMessage] =
    rawDomEvents.mapAsync { case Incoming(_, args, revision, departure) =>
      Effect[F].delay(Frontend.decodeDomEvent(args).copy(renderRevision = revision, sensitiveDeparture = departure)).recoverF { case error =>
        if (guarded) close().flatMap(_ => Effect[F].fail(error)) else Effect[F].fail(error)
      }
    }

  val browserHistoryMessages: Stream[F, BrowserHistoryMessage] =
    rawBrowserHistoryChanges.mapAsync { case Incoming(_, args, revision, _) =>
      Effect[F].delay {
        val counter = Option.when(guarded)(sensitiveNavigation.incrementAndGet())
        if (counter.exists(_ > 9007199254740991L)) throw new SessionAccessDenied
        BrowserHistoryMessage(PathAndQuery.fromString(args), revision, counter)
      }.flatMap { message =>
        // Retire before joining the user queue: an earlier disclosure may be
        // waiting for its acknowledgement and must be released first.
        sensitiveRuntime.clearAll(spoonbill.sensitive.ClearReason.Navigation).as(message)
      }.recoverF { case _ =>
        val error = new IllegalArgumentException("Invalid browser history message")
        if (guarded) close().flatMap(_ => Effect[F].fail(error)) else Effect[F].fail(error)
      }
    }

  private def enqueueOutput(str: String, producer: Option[() => F[Unit]], pending: () => Boolean,
    rejectIfFull: Boolean, resetView: Boolean, onPublished: () => Unit): F[Unit] =
    if (recoversView && (str.startsWith("[4,") || str.startsWith("[4 ") || str == "[4]")) Effect[F].delay {
      // Reserve the revision and publish in one critical section *after* the
      // asynchronous authorization checks. Concurrent producers cannot reorder
      // sequence numbers or publish a direct property mutation without a stamp.
      renderPublication.synchronized {
        import spoonbill.security.Versions.ViewOwnershipEpoch.*
        val frame = if (resetView) {
          if (recoveryStarted.get()) throw new SessionAccessDenied
          val epoch = viewRecoveryEpoch.getOrElse(throw new SessionAccessDenied)
          s"""[19,"${epoch.toLong}","$actionConnectionId","0",$str]"""
        } else {
          val previous = renderRevision.get()
          if (!recoveryStarted.get() || previous >= 9007199254740991L) throw new SessionAccessDenied
          s"""[20,"$actionConnectionId","$previous","${previous + 1L}",$str]"""
        }
        if (!outgoingQueue.offerUnsafe(Outgoing(frame, producer, pending))) throw new SessionAccessDenied
        if (resetView) recoveryStarted.set(true) else renderRevision.incrementAndGet()
        onPublished()
        ()
      }
    } else if (rejectIfFull) Effect[F].delay {
      if (!outgoingQueue.offerUnsafe(Outgoing(str, producer, pending)))
        throw ClientSideException("Browser output queue is full")
    } else outgoingQueue.enqueue(Outgoing(str, producer, pending))

  private def sendRaw(str: String, protectedOutput: Boolean = true, producer: Option[() => F[Unit]] = None,
    pending: () => Boolean = () => true, rejectIfFull: Boolean = false, resetView: Boolean = false,
    onPublished: () => Unit = () => ()): F[Unit] =
    if (!pending()) Effect[F].unit
    else if (!protectedOutput) requireOpen().flatMap(_ => enqueueOutput(str, None, pending, rejectIfFull, resetView, onPublished))
    else {
      val captured = producer match {
        case Some(check) => Effect[F].pure(check)
        case None => captureOutputAuthorization.fold(Effect[F].pure(() => authorizeInteraction()))(capture => Effect[F].delayAsync(capture()))
      }
      captured.flatMap { check =>
        authorizeInteraction().flatMap(_ => Effect[F].delayAsync(check()))
          .flatMap { _ =>
            if (!pending()) Effect[F].unit
            else enqueueOutput(str, Some(check), pending, rejectIfFull, resetView, onPublished)
          }
      }.recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }
    }

  private def send(args: Any*): F[Unit] = sendWithProducer(None, () => true, false, args: _*)

  private def sendRequest(pending: () => Boolean, args: Any*): F[Unit] = sendWithProducer(None, pending, true, args: _*)

  private def sendWithProducer(producer: Option[() => F[Unit]], pending: () => Boolean, rpc: Boolean, args: Any*): F[Unit] = {

    val sb = new mutable.StringBuilder()
    sb.append('[')
    args.foreach {
      case s: String =>
        sb.append('"')
        jsonEscape(sb, s, unicode = true)
        sb.append('"')
        sb.append(',')
      case x =>
        sb.append(x.toString)
        sb.append(',')
    }
    sb.update(sb.length - 1, ' ') // replace last comma to space
    sb.append(']')
    val control = args.headOption.exists {
      case code: Int => Set(0, 1, 9, 16, 17).contains(code)
      case _ => false
    }
    sendRaw(sb.result(), protectedOutput = !control, producer = producer, pending = pending, rejectIfFull = rpc)
  }

  def listenEvent(name: String, preventDefault: Boolean): F[Unit] =
    send(Procedure.ListenEvent.code, name, preventDefault)

  def uploadForm(id: Id): F[FormData] =
    for {
      descriptor <- nextDescriptor()
      result <- formRequests.request(descriptor, rpcSettings.fileTimeout, () => ClientSideException("UploadForm timed out"))(
        pending => sendRequest(pending, Procedure.UploadForm.code, id.mkString, descriptor))
    } yield result

  def listFiles(id: Id): F[List[(String, Long)]] =
    for {
      descriptor <- nextDescriptor()
      files <- fileNameRequests.request(descriptor, rpcSettings.requestTimeout, () => ClientSideException("ListFiles timed out"))(
        pending => sendRequest(pending, Procedure.ListFiles.code, id.mkString, descriptor))
    } yield files

  def uploadFile(id: Id, handler: FileHandler): F[Stream[F, Bytes]] =
    for {
      descriptor <- nextDescriptor()
      file <- fileRequests.request(descriptor, rpcSettings.fileTimeout, () => ClientSideException("UploadFile timed out"))(
        pending => sendRequest(pending, Procedure.UploadFile.code, id.mkString, descriptor, handler.fileName))
    } yield file

  def downloadFile(name: String, stream: Stream[F, Bytes], size: Option[Long], mimeType: String): F[Unit] = {
    nextDescriptor().flatMap { id =>
      val canceled = new AtomicBoolean(false)
      val owned = new Stream[F, Bytes] {
        def pull(): F[Option[Bytes]] =
          if (canceled.get()) Effect[F].pure(None)
          else stream.pull().flatMap {
            case None => Effect[F].delay { downloadFiles.remove(id); Option.empty[Bytes] }
            case some => Effect[F].pure(some)
          }.recoverF { case error =>
            Effect[F].delay(downloadFiles.remove(id)).flatMap(_ => Effect[F].fail(error))
          }
        def cancel(): F[Unit] = Effect[F].delay(canceled.compareAndSet(false, true)).flatMap {
          case true => Effect[F].delay(downloadFiles.remove(id)).flatMap(_ => stream.cancel())
          case false => Effect[F].unit
        }
      }
      Effect[F].delay {
        downloadFiles.put(id, DownloadFileMeta(owned, size, mimeType))
        ()
      }.flatMap(_ => requireOpen()).flatMap(_ => send(Procedure.DownloadFile.code, id, name))
        .recoverF { case error =>
          owned.cancel().recover { case _ => () }
            .flatMap(_ => Effect[F].fail(error))
        }
    }
  }

  def resolveFileDownload(descriptor: String): F[Option[DownloadFileMeta[F]]] =
    requireOpen().flatMap(_ => Effect[F].delay(downloadFiles.get(descriptor)))

  def focus(id: Id): F[Unit] =
    send(Procedure.Focus.code, id.mkString)

  private def nextDescriptor(): F[String] = requireOpen().flatMap { _ => Effect[F].delay {
    @tailrec def next(): String = {
      val descriptor = lastDescriptor.get()
      if (descriptor == Long.MaxValue) throw new IllegalStateException("Browser request descriptors exhausted")
      if (lastDescriptor.compareAndSet(descriptor, descriptor + 1L)) descriptor.toString else next()
    }
    next()
  }}

  def extractProperty(id: Id, name: String): F[String] =
    for {
      descriptor <- nextDescriptor()
      _ <- Effect[F].delay {
             reporter.debug(s"ExtractProperty request: id=${id.mkString} name=$name descriptor=$descriptor")
           }
      result <- stringRequests.request("property:" + descriptor, rpcSettings.propertyTimeout,
        () => ClientSideException("ExtractProperty timed out"))(
        pending => sendRequest(pending, Procedure.ExtractProperty.code, descriptor, id.mkString, name))
      _ <- Effect[F].delay {
             reporter.debug(s"ExtractProperty response: descriptor=$descriptor length=${result.length}")
           }
    } yield result

  def setProperty(id: Id, name: String, value: Any): F[Unit] =
    send(Procedure.ModifyDom.code, ModifyDomProcedure.SetAttr.code, id.mkString, 0, name, value, true)

  def evalJs(code: String): F[String] =
    for {
      descriptor <- nextDescriptor()
      result <- stringRequests.request("eval:" + descriptor, rpcSettings.requestTimeout,
        () => ClientSideException("EvalJs timed out"))(
        pending => sendRequest(pending, Procedure.EvalJs.code, descriptor, code))
    } yield result

  def resetForm(id: Id): F[Unit] =
    send(Procedure.RestForm.code, id.mkString)

  def changePageUrl(pq: PathAndQuery): F[Unit] =
    send(Procedure.ChangePageUrl.code, pq.mkString)

  private[spoonbill] def changePageUrlAuthorized(pq: PathAndQuery, producer: () => F[Unit]): F[Unit] =
    sendWithProducer(Some(producer), () => true, false, Procedure.ChangePageUrl.code, pq.mkString)

  def setEventCounter(id: Id, eventType: String, n: Int): F[Unit] =
    send(Procedure.SetEventCounter.code, id.mkString, eventType, n)

  def resetEventCounters(): F[Unit] =
    send(Procedure.ResetEventCounters.code)

  private[spoonbill] def readyForUserEvents(): F[Unit] =
    send(Procedure.ReadyForUserEvents.code, recoversView)

  def reload(): F[Unit] =
    send(Procedure.Reload.code)

  def reloadCss(): F[Unit] =
    send(Procedure.ReloadCss.code)

  def extractEventData(dem: DomEventMessage): F[String] =
    for {
      descriptor <- nextDescriptor()
      _ <- Effect[F].delay {
             reporter.debug(
               s"ExtractEventData request: id=${dem.target.mkString} type=${dem.eventType} descriptor=$descriptor"
             )
           }
      result <- stringRequests.request("event:" + descriptor, rpcSettings.eventDataTimeout,
        () => ClientSideException("ExtractEventData timed out"))(
        pending => sendRequest(pending, Procedure.ExtractEventData.code, descriptor, dem.target.mkString, dem.eventType))
      _ <- Effect[F].delay {
             reporter.debug(s"ExtractEventData response: descriptor=$descriptor length=${result.length}")
           }
    } yield result

  def performDomChanges(f: ChangesPerformer => Unit): F[Unit] = performDomChangesWithProducer(f, None)

  private[spoonbill] def performDomChangesAuthorized(f: ChangesPerformer => Unit, producer: () => F[Unit],
    onPublished: () => Unit = () => ()): F[Unit] =
    performDomChangesWithProducer(f, Some(producer), onPublished = onPublished)

  private[spoonbill] def resetDomChangesAuthorized(f: ChangesPerformer => Unit, producer: () => F[Unit],
    onPublished: () => Unit = () => ()): F[Unit] =
    performDomChangesWithProducer(f, Some(producer), reset = true, onPublished = onPublished)

  private def performDomChangesWithProducer(f: ChangesPerformer => Unit, producer: Option[() => F[Unit]],
    reset: Boolean = false, onPublished: () => Unit = () => ()): F[Unit] = {
    def diff = {
      val timeStart = System.nanoTime()
      val sb        = remoteDomChangesPerformer.buffer
      sb.append('[')
      sb.append(Procedure.ModifyDom.codeString)
      sb.append(',')
      f(remoteDomChangesPerformer)
      sb.update(sb.length - 1, ' ') // replace last comma to space
      sb.append(']')
      val result = remoteDomChangesPerformer.buffer.result()
      val timeEnd   = System.nanoTime()
      val timeTotal = timeEnd - timeStart
      Metrics.MaxDiffNanos.update(prev => Math.max(prev, timeTotal))
      Metrics.MinDiffNanos.update(prev => if (prev == 0) timeTotal else Math.min(prev, timeTotal))
      avgDiffTime.set((avgDiffTime.get + timeTotal) / 2)
      result
    }
    for {
      _ <- sensitiveRuntime.revalidateActive()
      // Switch to blocking context if rendering is slow
      result <- if (avgDiffTime.get() > HeavyRenderThresholdNanos) Effect[F].blocking(diff) else Effect[F].delay(diff)
      _      <- sendRaw(result, producer = producer, resetView = reset, onPublished = onPublished)
      _      <- Effect[F].delay(remoteDomChangesPerformer.buffer.clear())
    } yield ()
  }

  def resolveFile(descriptor: String, file: Stream[F, Bytes]): F[Unit] =
    fileRequests.complete(descriptor, Right(file)).flatMap {
      case true => Effect[F].unit
      case false => file.cancel()
    }

  def resolveFileNames(descriptor: String, handler: List[(String, Long)]): F[Unit] =
    fileNameRequests.complete(descriptor, Right(handler)).unit

  def resolveFormData(descriptor: String, formData: Either[Throwable, FormData]): F[Unit] =
    formRequests.complete(descriptor, formData.left.map(_ => ClientSideException("Browser form transfer failed"))).unit

  def registerCustomCallback(name: String)(f: String => F[Unit]): F[Unit] =
    Effect[F].delay {
      customCallbacks.put(name, f)
      ()
    }

  private def unescapeJsonString(s: String): String = {
    val sb  = new mutable.StringBuilder()
    var i   = 1
    val len = s.length - 1
    while (i < len) {
      val c             = s.charAt(i)
      var charsConsumed = 0
      if (c != '\\') {
        charsConsumed = 1
        sb.append(c)
      } else {
        charsConsumed = 2
        (s.charAt(i + 1): @switch) match {
          case '\\' => sb.append('\\')
          case '"'  => sb.append('"')
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case 'u' =>
            val code = s.substring(i + 2, i + 6)
            charsConsumed = 6
            sb.append(Integer.parseInt(code, 16).toChar)
        }
      }
      i += charsConsumed
    }
    sb.result()
  }

  private def parseMessage(json: String) = {
    try {
    val tokens = json
      .substring(1, json.length - 1) // remove brackets
      .split(",", 2)                 // split to tokens
    val callbackType = tokens(0).toInt
    val args =
      if (tokens.length > 1) unescapeJsonString(tokens(1))
      else ""
    if (callbackType == CallbackType.DomEvent.code) {
      // Action submissions can contain credentials. Log metadata only.
      reporter.debug("DOM event received")
    }
    if (callbackType == CallbackType.ViewEvent.code) {
      val fields = args.split(":", 4)
      if (!recoversView || fields.length != 4 || fields(0) != actionConnectionId.toString)
        throw new IllegalArgumentException("Invalid view callback")
      val revision = fields(1).toLongOption.filter(value => value >= 0L && value <= 9007199254740991L)
        .getOrElse(throw new IllegalArgumentException("Invalid view revision"))
      val innerCode = fields(2).toIntOption.filter(Set(0, 1, 3).contains)
        .getOrElse(throw new IllegalArgumentException("Invalid view callback"))
      Incoming(innerCode, fields(3), Some(revision))
    } else {
      if (recoversView && Set(0, 1, 3).contains(callbackType))
        throw new IllegalArgumentException("View callback requires a revision")
      Incoming(callbackType, args)
    }
    } catch { case NonFatal(_) => throw new IllegalArgumentException("Invalid protocol frame") }
  }

  // Keep pulling independently of user handlers. Stream.sort's demand-based
  // routing cannot guarantee progress when a serial handler awaits a reply.
  guardedRacks.foreach { queues =>
    incomingMessages.foreach { message =>
      Effect[F].delay {
        if (message.length > 16384 || message.getBytes(StandardCharsets.UTF_8).length > 16384)
          throw new IllegalArgumentException("Incoming session frame exceeds its limit")
        val parsed = parseMessage(message)
        // Stamp at transport arrival, before independent racks can delay user
        // admission. A recovery callback may overtake an unread DOM rack item.
        if (parsed.code == CallbackType.SensitiveDeparture.code) {
          val counter = departureCounter(parsed.args)
          sensitiveDeparture.updateAndGet(previous => math.max(previous, counter))
        }
        if (!queues(rack(parsed)).offerUnsafe(parsed.copy(departure = sensitiveDeparture.get()))) throw new SessionAccessDenied
      }
    }.flatMap(_ => close()).recoverF { case error =>
      queues.foreach(_.failUnsafe(error))
      close().flatMap(_ => Effect[F].fail(error))
    }.runAsyncForget
  }

  rawClientMessages.foreach {
    case Incoming(CallbackType.SensitiveDeparture.code, args, _, _) =>
      recoverSensitiveDeparture(args)
    case Incoming(CallbackType.SensitiveAcknowledgment.code, args, _, _) =>
      sensitiveCallback(args, acknowledge = true)
    case Incoming(CallbackType.SensitiveCleared.code, args, _, _) =>
      sensitiveCallback(args, acknowledge = false)
    case Incoming(CallbackType.Heartbeat.code, _, _, _) =>
      heartbeatLimit match {
        case Some(_) =>
          sendRaw("[16]", protectedOutput = false)
        case None =>
          Effect[F].unit
      }
    case Incoming(CallbackType.ExtractPropertyResponse.code, args, _, _) =>
      val Array(descriptor, propertyType, value) = args.split(":", 3)
      reporter.debug("Browser property reply received")
      propertyType.toIntOption match {
        case Some(PropertyType.Error.code) =>
          stringRequests.complete("property:" + descriptor, Left(ClientSideException("Browser property extraction failed"))).unit
        case Some(code) if code >= PropertyType.String.code && code <= PropertyType.Object.code =>
          stringRequests.complete("property:" + descriptor, Right(value)).unit
        case _ => Effect[F].fail(ClientSideException("Invalid browser property reply"))
      }
    case Incoming(CallbackType.ExtractEventDataResponse.code, args, _, _) =>
      val Array(descriptor, value) = args.split(":", 2)
      stringRequests.complete("event:" + descriptor, Right(value)).unit
    case Incoming(CallbackType.EvalJsResponse.code, args, _, _) =>
      val Array(descriptor, status, json) = args.split(":", 3)
      status.toIntOption match {
        case Some(EvalJsStatus.Success.code) => stringRequests.complete("eval:" + descriptor, Right(json)).unit
        case Some(EvalJsStatus.Failure.code) =>
          stringRequests.complete("eval:" + descriptor, Left(ClientSideException("Browser evaluation failed"))).unit
        case _ => Effect[F].fail(ClientSideException("Invalid browser evaluation reply"))
      }
    case Incoming(CallbackType.CustomCallback.code, args, revision, departure) =>
      val Array(name, arg) = args.split(":", 2)
      customCallbacks.get(name) match {
        case Some(f) =>
          // Enqueue without awaiting here: a subsequent transport reply may be
          // needed by the user action currently owning the execution queue.
          Effect[F].delay { runBrowserAction(f(arg), revision, departure).runAsyncForget }
        case None    => Effect[F].unit
      }
    case Incoming(callbackType, args, _, _) =>
      Effect[F].fail(UnknownCallbackException(callbackType, "<redacted>"))
  }.recoverF { case error =>
    if (guarded) close().flatMap(_ => Effect[F].fail(error)) else Effect[F].fail(error)
  }.runAsyncForget

  private def departureCounter(args: String): Long = {
    if (!guarded || !args.matches("[1-9][0-9]{0,15}")) throw new SessionAccessDenied
    val counter = args.toLong
    if (counter > 9007199254740991L) throw new SessionAccessDenied
    counter
  }

  private def recoverSensitiveDeparture(args: String): F[Unit] =
    Effect[F].delay {
      val counter = departureCounter(args)
      if (counter <= handledSensitiveDeparture.get()) None
      else { handledSensitiveDeparture.set(counter); Some(counter) }
    }.flatMap {
      case None => Effect[F].unit
      case Some(counter) =>
        // Release an outstanding disclosure before joining its execution queue.
        sensitiveRuntime.clearAll(spoonbill.sensitive.ClearReason.Navigation).flatMap { _ =>
          // Do not await the queued job on the control rack: the preceding user
          // action may still require an ordinary browser RPC reply from this rack.
          Effect[F].delay {
            runUserAction {
              sensitiveRuntime.clearAll(spoonbill.sensitive.ClearReason.Navigation)
                .flatMap(_ => sendRaw(s"[25,$counter]", rejectIfFull = true))
            }.recoverF { case error => close().flatMap(_ => Effect[F].fail(error)) }.runAsyncForget
          }
        }
    }

  private def sensitiveCallback(args: String, acknowledge: Boolean): F[Unit] = {
    import spoonbill.sensitive.*
    val fields = args.split(":", -1)
    if (fields.length != (if (acknowledge) 4 else 3) || fields(0) != actionConnectionId.toString)
      Effect[F].fail(new SessionAccessDenied)
    else (scala.util.Try(UUID.fromString(fields(1))).toOption, RegionId.parse(fields(2))) match {
      case (Some(id), Right(region)) if !acknowledge => sensitiveRuntime.browserCleared(PresentationId.fromUuid(id), region)
      case (Some(id), Right(region)) if Set("ok", "failed").contains(fields(3)) =>
        sensitiveRuntime.acknowledge(PresentationId.fromUuid(id), region, fields(3) == "ok")
      case _ => Effect[F].fail(new SessionAccessDenied)
    }
  }
}

object Frontend {

  /** Positive bounded waits; zero/negative values never disable RPC deadlines.
    * The file timeout covers waiting for an incoming transfer to be supplied,
    * not the application-controlled duration of consuming its stream.
    */
  final case class RpcSettings(
    requestTimeout: FiniteDuration = 5.seconds,
    propertyTimeout: FiniteDuration = 5.seconds,
    eventDataTimeout: FiniteDuration = 5.seconds,
    fileTimeout: FiniteDuration = 30.seconds,
    maxPendingPerKind: Int = 128
  ) {
    require(List(requestTimeout, propertyTimeout, eventDataTimeout, fileTimeout).forall(value =>
      value.toMillis > 0 && value <= 5.minutes), "Browser RPC deadlines must be positive and at most five minutes")
    require(maxPendingPerKind > 0 && maxPendingPerKind <= 4096, "Invalid pending browser RPC limit")
  }

  /** Trusted scheduling hook; cancel must be safe after expiry. The default
    * uses the framework scheduler. Tests can advance deadlines without sleeps.
    */
  trait RpcDeadlineScheduler {
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit
  }

  private def positiveTimeout(property: String, fallback: FiniteDuration): FiniteDuration =
    Option(System.getProperty(property)).flatMap(_.toLongOption)
      .filter(value => value > 0 && value <= 5.minutes.toMillis).map(_.millis).getOrElse(fallback)

  private def defaultRpcSettings: RpcSettings = RpcSettings(
    requestTimeout = positiveTimeout("spoonbill.browserRpcTimeoutMillis", 5.seconds),
    propertyTimeout = positiveTimeout("spoonbill.extractPropertyTimeoutMillis", 5.seconds),
    eventDataTimeout = positiveTimeout("spoonbill.extractEventDataTimeoutMillis", 5.seconds),
    fileTimeout = positiveTimeout("spoonbill.fileRpcTimeoutMillis", 30.seconds)
  )

  /** Header/parser failures must never include the credential-bearing frame. */
  private[spoonbill] def decodeDomEvent(args: String): DomEventMessage = {
    val parts = args.split(":", 4)
    def invalid = new IllegalArgumentException("Invalid DOM event")
    if (parts.length < 3 || parts(1).length > 128 || parts(2).length > 96 ||
        !parts(1).matches("[0-9]+(_[0-9]+)*") || !parts(2).matches("[A-Za-z][A-Za-z0-9_.-]*"))
      throw invalid
    val counter = parts(0).toIntOption.filter(_ >= 0).getOrElse(throw invalid)
    val target = scala.util.Try(Id(parts(1))).toOption.getOrElse(throw invalid)
    val submission =
      if (parts.length == 4 && parts(2) == "submit") Some(FormSubmission.decode(parts(3)))
      else None
    DomEventMessage(counter, target, parts(2), submission)
  }

  final case class DomEventMessage(
    eventCounter: Int,
    target: Id,
    eventType: String,
    private[spoonbill] val submission: Option[Either[FormSubmission.InvalidSubmission, FormSubmission]] = None,
    private[spoonbill] val renderRevision: Option[Long] = None,
    private[spoonbill] val sensitiveDeparture: Long = 0L
  ) {
    override def toString: String = s"DomEventMessage($eventCounter,$target,$eventType,<redacted>)"
  }

  final case class BrowserHistoryMessage(path: PathAndQuery, renderRevision: Option[Long], sensitiveNavigation: Option[Long] = None)

  sealed abstract class Procedure(final val code: Int) {
    final val codeString = code.toString
  }

  object Procedure {
    case object SetEventCounter    extends Procedure(0)  // (id, eventType, n)
    case object Reload             extends Procedure(1)  // ()
    case object ListenEvent        extends Procedure(2)  // (type, preventDefault)
    case object ExtractProperty    extends Procedure(3)  // (id, propertyName, descriptor)
    case object ModifyDom          extends Procedure(4)  // (commands)
    case object Focus              extends Procedure(5)  // (id) {
    case object ChangePageUrl      extends Procedure(6)  // (path)
    case object UploadForm         extends Procedure(7)  // (id, descriptor)
    case object ReloadCss          extends Procedure(8)  // ()
    case object KeepAlive          extends Procedure(9)  // ()
    case object EvalJs             extends Procedure(10) // (code)
    case object ExtractEventData   extends Procedure(11) // (descriptor, id, eventType)
    case object ListFiles          extends Procedure(12) // (id, descriptor)
    case object UploadFile         extends Procedure(13) // (id, descriptor, fileName)
    case object RestForm           extends Procedure(14) // (id)
    case object DownloadFile       extends Procedure(15) // (descriptor, fileName)
    case object Heartbeat          extends Procedure(16) // ()
    case object ResetEventCounters extends Procedure(17) // ()
    case object CompleteAuthentication extends Procedure(18) // (restricted completion id; no credential)
    case object ResetView extends Procedure(19) // (owner epoch, connection, render revision, full DOM)
    case object PatchView extends Procedure(20) // (connection, previous revision, next revision, DOM diff)
    case object ReadyForUserEvents extends Procedure(21) // (requires durable baseline)
    case object PresentSensitive extends Procedure(22)
    case object ClearSensitive extends Procedure(23)
    case object SensitiveNavigationComplete extends Procedure(24)
    case object SensitiveDepartureComplete extends Procedure(25)

    val All = Set(
      SetEventCounter,
      Reload,
      ListenEvent,
      ExtractProperty,
      ModifyDom,
      Focus,
      ChangePageUrl,
      UploadForm,
      ReloadCss,
      KeepAlive,
      EvalJs,
      ExtractEventData,
      ListFiles,
      UploadFile,
      RestForm,
      DownloadFile,
      Heartbeat,
      ResetEventCounters,
      CompleteAuthentication,
      ResetView,
      PatchView,
      ReadyForUserEvents,
      PresentSensitive,
      ClearSensitive,
      SensitiveNavigationComplete,
      SensitiveDepartureComplete
    )

    def apply(n: Int): Option[Procedure] =
      All.find(_.code == n)
  }

  sealed abstract class ModifyDomProcedure(final val code: Int) {
    final val codeString = code.toString
  }

  object ModifyDomProcedure {
    case object Create      extends ModifyDomProcedure(0) // (id, childId, xmlNs, tag)
    case object CreateText  extends ModifyDomProcedure(1) // (id, childId, text)
    case object Remove      extends ModifyDomProcedure(2) // (id, childId)
    case object SetAttr     extends ModifyDomProcedure(3) // (id, xmlNs, name, value, isProperty)
    case object RemoveAttr  extends ModifyDomProcedure(4) // (id, xmlNs, name, isProperty)
    case object SetStyle    extends ModifyDomProcedure(5) // (id, name, value)
    case object RemoveStyle extends ModifyDomProcedure(6) // (id, name)
  }

  sealed abstract class PropertyType(final val code: Int)

  object PropertyType {
    case object String  extends PropertyType(0)
    case object Number  extends PropertyType(1)
    case object Boolean extends PropertyType(2)
    case object Object  extends PropertyType(3)
    case object Error   extends PropertyType(4)
  }

  sealed abstract class EvalJsStatus(final val code: Int)

  object EvalJsStatus {
    case object Success extends EvalJsStatus(0)
    case object Failure extends EvalJsStatus(1)
  }

  sealed abstract class CallbackType(final val code: Int)

  object CallbackType {
    case object DomEvent                 extends CallbackType(0) // `$eventCounter:$elementId:$eventType`
    case object CustomCallback           extends CallbackType(1) // `$name:arg`
    case object ExtractPropertyResponse  extends CallbackType(2) // `$descriptor:$value`
    case object History                  extends CallbackType(3) // URL
    case object EvalJsResponse           extends CallbackType(4) // `$descriptor:$status:$value`
    case object ExtractEventDataResponse extends CallbackType(5) // `$descriptor:$dataJson`
    case object Heartbeat                extends CallbackType(6) // `$descriptor:$anyvalue`
    case object ViewEvent                extends CallbackType(7) // connection:revision:callback:args
    case object SensitiveAcknowledgment  extends CallbackType(8)
    case object SensitiveCleared         extends CallbackType(9)
    case object SensitiveDeparture       extends CallbackType(10)

    final val All = Set(
      DomEvent,
      CustomCallback,
      ExtractPropertyResponse,
      History,
      EvalJsResponse,
      ExtractEventDataResponse,
      Heartbeat,
      ViewEvent,
      SensitiveAcknowledgment,
      SensitiveCleared,
      SensitiveDeparture
    )

    def apply(n: Int): Option[CallbackType] =
      All.find(_.code == n)
  }

  case class ClientSideException(message: String) extends Exception(message)
  case class UnknownCallbackException(callbackType: Int, args: String)
      extends Exception(s"Unknown callback $callbackType with args '$args' received")

  final case class DownloadFileMeta[F[_]: Effect](stream: Stream[F, Bytes], size: Option[Long], mimeType: String)

  final val ReloadMessage: String     = "[1]"
  final val HeavyRenderThresholdNanos = 50000000L
}
