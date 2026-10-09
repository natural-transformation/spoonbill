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

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Hub, Scheduler, Stream, Var}
import spoonbill.effect.syntax.*
import spoonbill.internal.{ApplicationInstance, Frontend, SensitiveAuthorization}
import spoonbill.server.{SessionAccessControl, SessionAccessDenied, SessionGuard, SpoonbillServiceConfig}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.internal.{BadRequestException, Cookies}
import spoonbill.state.{GuardedStateManager, StateDeserializer, StateSerializer, StateStorage, StateManager}
import spoonbill.web.{PathAndQuery, Request}
import spoonbill.web.Request.Head

private[spoonbill] final class SessionsService[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
  config: SpoonbillServiceConfig[F, S, M],
  pageService: PageService[F, S, M]
) {

  import config.executionContext
  import config.reporter.Implicit

  type App                = ApplicationInstance[F, S, M]
  type ExtensionsHandlers = List[Extension.Handlers[F, S, M]]

  private val apps = AsyncTable.unsafeCreateEmpty[F, Qsid, App]
  private val guardedAttachments = TrieMap.empty[Qsid, ConnectionId]
  // Retry a failed storage retirement only while holding the next attachment's
  // local fence, before opening/restoring a replacement. Never retire an old
  // view asynchronously after its replacement has started writing.
  private val pendingStorageRemovals = TrieMap.empty[Qsid, Unit]
  private case object MissingLocalView extends RuntimeException("Local view is unavailable")
  // Only URI metadata is retained, never bootstrap authority or application
  // state. Bound unused HTTP bootstraps and consume them on durable attachment.
  private val bootstrapPaths = new java.util.LinkedHashMap[Qsid, (Long, PathAndQuery)] {
    override def removeEldestEntry(entry: java.util.Map.Entry[Qsid, (Long, PathAndQuery)]): Boolean = size() > 5000
  }

  private def checkedApplicationPath(raw: String): PathAndQuery = {
    if (raw.length > 8192 || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192 ||
        !raw.startsWith("/") || raw.startsWith("//") || raw.exists(c => c < ' ' || c == 127 || c == '\\' || c == '#'))
      throw new SessionAccessDenied
    val path = try PathAndQuery.fromString(raw) catch { case scala.util.control.NonFatal(_) => throw new SessionAccessDenied }
    if (path.startsWith("bridge")) throw new SessionAccessDenied
    path
  }

  private def checkedApplicationPath(path: PathAndQuery): PathAndQuery = {
    val rendered = path.mkString
    // The legacy renderer emits root queries as "?key=value". The structured
    // path is still absolute Root; normalize only that representation here.
    // Raw browser-supplied relative references must continue to be rejected.
    val absolute = if (path.asPath == PathAndQuery.Root && rendered.startsWith("?")) "/" + rendered else rendered
    checkedApplicationPath(absolute)
  }

  private def applicationRequest(qsid: Qsid, current: Head): Head = {
    val bootstrap = bootstrapPaths.synchronized(Option(bootstrapPaths.remove(qsid)))
      .filter { case (created, _) => System.nanoTime() - created <= 5.minutes.toNanos }.map(_._2)
    val path = current.param("__spoonbill_location") match {
      // Current browser location takes precedence after client-side navigation.
      // It is untrusted routing input, checked by the same current state policy.
      case Some(raw) => checkedApplicationPath(raw)
      case None => bootstrap.getOrElse {
        if (current.pq.startsWith("bridge")) throw MissingLocalView
        else checkedApplicationPath(current.pq)
      }
    }
    Request.withPathAndQuery(current, path)
  }

  private def retryStorageRemoval(qsid: Qsid): F[Unit] = Effect[F].delay {
    if (pendingStorageRemovals.contains(qsid)) {
      stateStorage.remove(qsid.deviceId, qsid.sessionId)
      pendingStorageRemovals.remove(qsid)
    }
    ()
  }

  def initSession(rh: Head): F[Qsid] =
    for {
      deviceId <- rh.cookie(Cookies.DeviceId) match {
                    case Some(d) => Effect[F].pure(d)
                    case None    => config.idGenerator.generateDeviceId()
                  }
      sessionId <- config.idGenerator.generateSessionId()
    } yield {
      Qsid(deviceId, sessionId)
    }

  def initAppState(qsid: Qsid, rh: Head): F[S] =
    for {
      bootstrapPath <- Effect[F].delay(Option.when(config.sessionAccessControl.nonEmpty)(checkedApplicationPath(rh.pq)))
      defaultState <- config.stateLoader(qsid.deviceId, rh)
      state <- config.router.toState
                 .lift(rh.pq)
                 .fold(Effect[F].pure(defaultState))(f => f(defaultState))
      _ <- config.sessionAccessControl.fold(Effect[F].unit)(control => required(control.authorizeHttp(rh, state)))
      _ <- stateStorage.create(qsid.deviceId, qsid.sessionId, state)
      _ <- Effect[F].delay {
        bootstrapPath.foreach { path =>
          bootstrapPaths.synchronized { bootstrapPaths.put(qsid, (System.nanoTime(), path)); () }
        }
      }
    } yield state

  def getApp(qsid: Qsid): F[Option[App]] =
    apps.getImmediately(qsid)

  def createAppIfNeeded(qsid: Qsid, rh: Head, incomingStream: Stream[F, String]): F[Unit] =
    config.sessionAccessControl match {
      case Some(control) => createGuarded(qsid, rh, incomingStream, control)
      case None => createLegacy(qsid, rh, incomingStream)
    }

  /** Mandatory checks have a bounded wait. A late successful open is released;
    * timeout cannot cancel an already-running external database operation.
    */
  private def required[A](operation: => F[A], late: Option[A => F[Unit]] = None): F[A] =
    Effect[F].promiseF[A] { callback =>
      val completed = new AtomicBoolean(false)
      scheduler.scheduleOnce(10.seconds) {
        Effect[F].delay {
          if (completed.compareAndSet(false, true)) callback(Left(new SessionAccessDenied))
        }
      }.flatMap { timer =>
        Effect[F].delay {
          Effect[F].runAsync(Effect[F].delayAsync(operation)) { result =>
            if (completed.compareAndSet(false, true)) {
              timer.unsafeCancel()
              callback(result)
            } else result.toOption.foreach(value => late.foreach(release => release(value).runAsyncForget))
          }
        }
      }
    }

  private def createGuarded(qsid: Qsid, rh: Head, incoming: Stream[F, String], control: SessionAccessControl[F, S]): F[Unit] = {
    val connectionId = ConnectionId.fromUuid(UUID.randomUUID())
    val live = new AtomicBoolean(true)
    val cleaned = new AtomicBoolean(false)
    val terminal = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val terminalSignalled = new AtomicBoolean(false)
    val ready = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val guardRef = new AtomicReference(Option.empty[SessionGuard[F, S]])
    val appRef = new AtomicReference(Option.empty[App])
    val frontendRef = new AtomicReference(Option.empty[Frontend[F]])
    val managerRef = new AtomicReference(Option.empty[GuardedStateManager[F, S]])
    val rawStateAcquired = new AtomicBoolean(false)
    val extensionRef = new AtomicReference(List.empty[Extension.Handlers[F, S, M]])
    val idleTimer = new AtomicReference(Option.empty[Scheduler.JobHandler[F, Unit]])

    def requireLive(): F[Unit] = Effect[F].delay { if (!live.get()) throw new SessionAccessDenied }
    def signal(): F[Unit] =
      Effect[F].delay(terminalSignalled.compareAndSet(false, true)).flatMap {
        case true => terminal.put((), ())
        case false => Effect[F].unit
      }
    def touch(): F[Unit] = Effect[F].delay {
      if (live.get()) {
        val timer = scheduler.unsafeScheduleOnce(config.sessionIdleTimeout)(signal())
        idleTimer.getAndSet(Some(timer)).foreach(_.unsafeCancel())
      }
    }
    val monitored = new Stream[F, String] {
      def pull(): F[Option[String]] = incoming.pull().flatMap {
        case None => signal().as(None)
        case some => touch().as(some)
      }.recoverF { case error => signal().flatMap(_ => Effect[F].fail(error)) }
      def cancel(): F[Unit] = incoming.cancel().recover { case _ => () }.flatMap(_ => signal())
    }
    def attempt(operation: => F[Unit]): F[Unit] = Effect[F].delayAsync(operation).recover { case error =>
      config.reporter.error("Guarded session cleanup failed", error)
      ()
    }
    def deny(error: Throwable): F[Unit] =
      Effect[F].delay(live.set(false))
        .flatMap(_ => frontendRef.get().fold(monitored.cancel())(_.close()))
        .flatMap(_ => signal())
        .flatMap(_ => Effect[F].fail(error))
    def authorize(guard: SessionGuard[F, S], state: S): F[Unit] =
      requireLive().flatMap(_ => required(guard.authorize(state))).flatMap(_ => requireLive())
        .recoverF { case error => deny(error) }

    def cleanup(): F[Unit] = Effect[F].delay(cleaned.compareAndSet(false, true)).flatMap {
      case false => Effect[F].unit
      case true => for {
        _ <- Effect[F].delay { live.set(false); idleTimer.getAndSet(None).foreach(_.unsafeCancel()) }
        _ <- attempt(ready.putEither((), Left(new SessionAccessDenied), silent = true))
        _ <- attempt(frontendRef.get().fold(monitored.cancel())(_.close()))
        // Drain already-admitted local storage writes before freeing this Qsid.
        // Do not time out this barrier and then allow an unsafe local takeover.
        _ <- managerRef.get().fold(Effect[F].unit)(_.closeAndDrain())
        _ <- attempt(appRef.get().fold(Effect[F].unit)(_.destroy()))
        _ <- attempt(guardRef.get().fold(Effect[F].unit)(guard => required(guard.close())))
        _ <- extensionRef.get().map(handler => attempt(required(handler.onDestroy()))).sequence.unit
        _ <- if (rawStateAcquired.get()) {
          Effect[F].delay(stateStorage.remove(qsid.deviceId, qsid.sessionId)).recover { case error =>
            pendingStorageRemovals.put(qsid, ())
            config.reporter.error("Guarded storage cleanup failed; retry required before restoration", error)
            ()
          }
        } else Effect[F].unit
        _ <- attempt(apps.remove(qsid))
        _ <- Effect[F].delay { guardedAttachments.remove(qsid, connectionId); () }
      } yield ()
    }

    def create(): F[Unit] = for {
      localExists <- stateStorage.exists(qsid.deviceId, qsid.sessionId)
      guard <- required(
        if (localExists) control.open(qsid, rh, connectionId)
        else control.resume(qsid, rh, connectionId).getOrElse(Effect[F].fail(MissingLocalView)),
        Some((value: SessionGuard[F, S]) => value.close()))
      _ <- Effect[F].delay(guardRef.set(Some(guard)))
      snapshots = guard.viewSnapshots
      _ <- if (snapshots.nonEmpty && localExists) Effect[F].delay(rawStateAcquired.set(true)) else Effect[F].unit
      restoredManager <- snapshots match {
        case Some(session) =>
          // Restore only presentation onto fresh authority. A prior process's
          // arbitrary state/DOM cache is never the durable bootstrap baseline.
          for {
            application <- Effect[F].delay(applicationRequest(qsid, rh))
            loaded <- config.stateLoader(qsid.deviceId, application)
            routed <- config.router.toState.lift(application.pq).fold(Effect[F].pure(loaded))(f => f(loaded))
            fresh <- required(guard.connected(routed))
            _ <- authorize(guard, fresh)
            restored <- required(session.initialize(fresh))
            _ <- authorize(guard, restored)
            manager = StateManager.cached[F](Map(avocet.Id.TopLevel -> restored))
          } yield manager
        case None => stateStorage.exists(qsid.deviceId, qsid.sessionId).flatMap {
          case true => stateStorage.get(qsid.deviceId, qsid.sessionId)
          case false => Effect[F].fail(MissingLocalView)
        }
      }
      rawManager <- Effect[F].delay {
        // get may promote a soft-closed snapshot before restoration is allowed.
        // Track that acquisition even when authorization prevents wrapper creation.
        if (snapshots.isEmpty) rawStateAcquired.set(true)
        restoredManager
      }
      initial <- rawManager.read[S](avocet.Id.TopLevel).flatMap {
        case Some(state) => Effect[F].pure(state)
        case None => Effect[F].fail[S](new SessionAccessDenied)
      }
      _ <- authorize(guard, initial)
      manager = new GuardedStateManager[F, S](rawManager, state => authorize(guard, state),
        snapshots.map(session => (state: S) => session.commit(state)),
        snapshots.map(_ => (error: Throwable) => deny(error)))
      _ <- Effect[F].delay(managerRef.set(Some(manager)))
      authorizeCurrent = () => rawManager.read[S](avocet.Id.TopLevel).flatMap {
        case Some(state) => authorize(guard, state)
        case None => deny(new SessionAccessDenied)
      }
      captureProducer = () => rawManager.read[S](avocet.Id.TopLevel).flatMap {
        case Some(state) => Effect[F].pure(() => authorize(guard, state))
        case None => Effect[F].fail[() => F[Unit]](new SessionAccessDenied)
      }
      captureSensitive = guard.sensitive.map { policy => (purpose: spoonbill.sensitive.Purpose) =>
        for {
          produced <- rawManager.read[S](avocet.Id.TopLevel).flatMap {
            case Some(state) => Effect[F].pure(state)
            case None => Effect[F].fail[S](new SessionAccessDenied)
          }
          _ <- authorize(guard, produced)
          audience <- required(policy.authorize(purpose, produced))
        } yield SensitiveAuthorization[F](audience, () => for {
          _ <- authorize(guard, produced)
          current <- rawManager.read[S](avocet.Id.TopLevel).flatMap {
            case Some(state) => Effect[F].pure(state)
            case None => Effect[F].fail[S](new SessionAccessDenied)
          }
          _ <- authorize(guard, current)
          producerAudience <- required(policy.authorize(purpose, produced))
          currentAudience <- required(policy.authorize(purpose, current))
          _ <- Effect[F].delay {
            if (producerAudience != audience || currentAudience != audience) throw new SessionAccessDenied
          }
          _ <- requireLive()
        } yield ())
      }
      frontend = new Frontend[F](monitored, config.heartbeatLimit, Some(connectionId), Some(authorizeCurrent),
        Some(() => ready.get(())), Some(captureProducer), viewRecoveryEpoch = snapshots.map(_.ownerEpoch),
        captureSensitiveAuthorization = captureSensitive)
      _ <- Effect[F].delay(frontendRef.set(Some(frontend)))
      app = new ApplicationInstance[F, S, M](qsid, frontend, manager, initial, config.document, config.rootPath,
        config.router, (rc, k) => pageService.setupStatefulProxy(rc, qsid, k), scheduler, config.reporter,
        config.recovery, config.delayedRender, Some(() => ready.get(())), Some(state => authorize(guard, state)))
      _ <- Effect[F].delay(appRef.set(Some(app)))
      _ <- app.initialize()
      state <- rawManager.read[S](avocet.Id.TopLevel).map(_.getOrElse(initial))
      connected <- if (snapshots.nonEmpty) Effect[F].pure(state) else required(guard.connected(state))
      _ <- authorize(guard, connected)
      _ <- if (snapshots.nonEmpty) Effect[F].unit
           else required(app.topLevelComponentInstance.browserAccess.transitionForce(_ => connected))
      handlers <- config.extensions.map { extension =>
        required(extension.setup(app.topLevelComponentInstance.browserAccess),
          Some((handler: Extension.Handlers[F, S, M]) => handler.onDestroy()))
          .map { handler => extensionRef.updateAndGet(existing => handler :: existing); handler }
      }.sequence
      _ <- requireLive()
      _ <- apps.put(qsid, app)
      // Publish before arming cleanup, so an early disconnect cannot be followed
      // by a late put resurrecting a closed app in the local registry.
      _ <- Effect[F].start(terminal.get(()).flatMap(_ => cleanup()))
      _ <- Effect[F].start(app.stateStream.flatMap(_.foreach { case (id, changed) =>
        if (id != avocet.Id.TopLevel) Effect[F].unit
        else authorize(guard, changed.asInstanceOf[S]).flatMap(_ => authorizeCurrent())
          .flatMap(_ => handlers.map(_.onState(changed.asInstanceOf[S])).sequence.unit)
      }))
      _ <- Effect[F].start(app.messagesStream.foreach(message =>
        authorizeCurrent().flatMap(_ => handlers.map(_.onMessage(message)).sequence.unit)))
      _ <- ready.put((), ())
      _ <- frontend.readyForUserEvents()
      _ <- touch()
    } yield ()

    Effect[F].delay {
      if (config.sessionIdleTimeout.toMillis <= 0 || guardedAttachments.putIfAbsent(qsid, connectionId).nonEmpty)
        throw new SessionAccessDenied
    }.flatMap { _ =>
      retryStorageRemoval(qsid).flatMap(_ => create()).recoverF {
        // Legacy/ephemeral views still require their original local baseline.
        case MissingLocalView => cleanup()
        case error => cleanup().flatMap(_ => Effect[F].fail(error))
      }
    }
  }

  private def createLegacy(qsid: Qsid, rh: Head, incomingStream: Stream[F, String]): F[Unit] = {
    val (incomingConsumed, managedIncomingStream) = incomingStream.handleConsumed
    val incomingHub: Hub[F, String]               = Hub(managedIncomingStream)

    def handleAppOrWsOutgoingCloseOrTimeout(frontend: Frontend[F], app: App, ehs: ExtensionsHandlers): F[Unit] = {

      val consumed: F[Unit] = Effect[F].promise[Unit] { cb =>
        val invoked = new AtomicBoolean(false)
        def invokeOnce(reason: String): Either[Throwable, Unit] => Unit = (x: Either[Throwable, Unit]) =>
          if (invoked.compareAndSet(false, true)) {
            config.reporter.debug(s"Session $qsid closed due $reason")
            cb(x)
          }

        def handleCommunicationTimeout(): F[Unit] = {
          def createTimeout(stream: Stream[F, String]): F[Scheduler.JobHandler[F, Unit]] =
            scheduler.scheduleOnce(config.sessionIdleTimeout) {
              invokeOnce("session idle timeout")(Right(()))
              stream.cancel()
            }

          for {
            in             <- incomingHub.newStream()
            initialTimeout <- createTimeout(in)
            _               = config.reporter.debug(s"Create idle timeout for $qsid")
            schedulerVar    = Var[F, Scheduler.JobHandler[F, Unit]](initialTimeout)
            _ <- in.foreach { _ =>
                   config.reporter.debug(s"Reset idle timeout for $qsid")
                   for {
                     currentTimer <- schedulerVar.get
                     _            <- currentTimer.cancel()
                     timeout      <- createTimeout(in)
                     _            <- schedulerVar.set(timeout)
                   } yield ()
                 }
          } yield ()
        }

        handleCommunicationTimeout().runAsyncForget
        incomingConsumed.runAsync(invokeOnce("due connection close"))
      }

      for {
        _ <- consumed
        _ <- incomingStream.cancel()
        _ <- frontend.outgoingMessages.cancel()
        _ <- app.topLevelComponentInstance.destroy()
        _ <- ehs.map { handler =>
               handler.onDestroy().recover { case error =>
                 config.reporter.error(s"Unable to destroy extension for $qsid", error)
                 ()
               }
             }.sequence
        _ <- Effect[F].delay(stateStorage.remove(qsid.deviceId, qsid.sessionId))
        _ <- apps.remove(qsid)
      } yield ()
    }

    def handleStateChange(app: App, ehs: ExtensionsHandlers): F[Unit] =
      app.stateStream.flatMap { stream =>
        stream.foreach { case (id, state) =>
          if (id != avocet.Id.TopLevel) Effect[F].unit
          else
            ehs
              .map(_.onState(state.asInstanceOf[S]))
              .sequence
              .unit
        }
      }

    def handleMessages(app: App, ehs: ExtensionsHandlers): F[Unit] =
      app.messagesStream.foreach { m =>
        ehs.map(_.onMessage(m)).sequence.unit
      }

    def create(): F[ApplicationInstance[F, S, M]] = {
      config.reporter.debug(s"Create session $qsid")
      for {
        stateManager      <- stateStorage.get(qsid.deviceId, qsid.sessionId)
        maybeInitialState <- stateManager.read[S](avocet.Id.TopLevel)
        // Top level state should exists. See 'initAppState'.
        initialState <-
          maybeInitialState.fold(
            Effect[F].fail[S](BadRequestException(s"Top level state should exists. Snapshot for $qsid is corrupted"))
          )(Effect[F].pure(_))
        in      <- incomingHub.newStream()
        frontend = new Frontend[F](in, config.heartbeatLimit)
        app = new ApplicationInstance[F, S, M](
                qsid,
                frontend,
                stateManager,
                initialState,
                config.document,
                config.rootPath,
                config.router,
                createMiscProxy = (rc, k) => pageService.setupStatefulProxy(rc, qsid, k),
                scheduler,
                config.reporter,
                config.recovery,
                config.delayedRender
              )
        browserAccess = app.topLevelComponentInstance.browserAccess
        _ <- config
          .extensions
          .map { extension =>
            extension
              .setup(browserAccess)
              .recover { error =>
                config.reporter.error(s"Unable to initialize extension ${extension.name}", error)
                Extension.Handlers[F, S, M]()
              }
          }
          .sequence
          .flatMap { ehs =>
            config.reporter.debug("Extensions init")
            for {
              _ <- Effect[F].start(handleStateChange(app, ehs))
              _ <- Effect[F].start(handleMessages(app, ehs))
              _ <- Effect[F].start(handleAppOrWsOutgoingCloseOrTimeout(frontend, app, ehs))
            } yield ()
          }
          .start
        _ <- app.initialize()
      } yield {
        app
      }
    }

    stateStorage.exists(qsid.deviceId, qsid.sessionId) flatMap {
      case true =>
        // State exists because it was created on static page
        // rendering phase (see ServerSideRenderingService)
        apps
          .getFill(qsid)(create())
          .unit
      case false =>
        // State can be missing after a restart; rebuild it from the request.
        config.reporter.debug(s"State is missing for $qsid. Rebuilding from request.")
        initAppState(qsid, rh)
          .flatMap(_ => apps.getFill(qsid)(create()).unit)
          .recover { case error =>
            config.reporter.error(s"Unable to rebuild state for $qsid", error)
            ()
          }
    }
  }

  private val stateStorage =
    if (config.stateStorage == null && config.sessionAccessControl.nonEmpty) StateStorage.ephemeral[F, S]()
    else if (config.stateStorage == null) StateStorage[F, S]()
    else config.stateStorage

  private val scheduler = new Scheduler[F]()
}
