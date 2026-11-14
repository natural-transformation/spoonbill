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
import spoonbill.Qsid
import spoonbill.effect.{AsyncTable, Effect, Hub, Scheduler, Stream, Var}
import spoonbill.effect.syntax.*
import spoonbill.internal.{ApplicationInstance, DevMode, ExtensionRuntime, Frontend, SensitiveAuthorization}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  ApplicationStateInitialization,
  SessionAccessControl,
  SessionAccessDenied,
  SessionGuard,
  SpoonbillServiceConfig
}
import spoonbill.server.internal.{BadRequestException, Cookies}
import spoonbill.state.{GuardedStateManager, StateDeserializer, StateManager, StateSerializer, StateStorage}
import spoonbill.web.{PathAndQuery, Request}
import spoonbill.web.Request.Head

private[spoonbill] final class SessionsService[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
  config: SpoonbillServiceConfig[F, S, M],
  pageService: PageService[F, S, M]
) {

  import config.executionContext
  import config.reporter.Implicit

  type App        = ApplicationInstance[F, S, M]
  type Extensions = ExtensionRuntime[F, S, M]

  private val apps               = AsyncTable.unsafeCreateEmpty[F, Qsid, App]
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
    if (
      raw.length > 8192 || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192 ||
      !raw.startsWith("/") || raw.startsWith("//") || raw.exists(c => c < ' ' || c == 127 || c == '\\' || c == '#')
    )
      throw new SessionAccessDenied
    val path =
      try PathAndQuery.fromString(raw)
      catch { case scala.util.control.NonFatal(_) => throw new SessionAccessDenied }
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
    val bootstrap = bootstrapPaths
      .synchronized(Option(bootstrapPaths.remove(qsid)))
      .filter { case (created, _) => System.nanoTime() - created <= 5.minutes.toNanos }
      .map(_._2)
    val path = current.param("__spoonbill_location") match {
      // Current browser location takes precedence after client-side navigation.
      // It is untrusted routing input, checked by the same current state policy.
      case Some(raw) => checkedApplicationPath(raw)
      case None =>
        bootstrap.getOrElse {
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
      deviceId <- rh.cookie(Cookies.DeviceId).filter(Cookies.validDeviceId) match {
                    case Some(d) => Effect[F].pure(d)
                    case None    => config.idGenerator.generateDeviceId()
                  }
      _ <- Effect[F].delay {
             require(
               Cookies.validDeviceId(deviceId),
               "Device ID generator must produce 1–128 ASCII letters, digits, or ._~-"
             )
           }
      sessionId <- config.idGenerator.generateSessionId()
    } yield {
      Qsid(deviceId, sessionId)
    }

  def initAppState(qsid: Qsid, rh: Head): F[S] =
    for {
      bootstrapPath <- Effect[F].delay(Option.when(config.sessionAccessControl.nonEmpty)(checkedApplicationPath(rh.pq)))
      defaultState  <- config.stateLoader(qsid.deviceId, rh)
      state <- config.router.toState
                 .lift(rh.pq)
                 .fold(Effect[F].pure(defaultState))(f => f(defaultState))
      _ <- config.sessionAccessControl.fold(Effect[F].unit)(control => required(control.authorizeHttp(rh, state)))
      _ <- config.sessionAccessControl.flatMap(_.prepareHttp(qsid, rh, state)).fold(Effect[F].unit)(required(_))
      _ <- stateStorage.create(qsid.deviceId, qsid.sessionId, state)
      _ <- Effect[F].delay {
             bootstrapPath.foreach { path =>
               bootstrapPaths.synchronized { bootstrapPaths.put(qsid, (System.nanoTime(), path)); () }
             }
           }
    } yield state

  def getApp(qsid: Qsid): F[Option[App]] =
    apps.getImmediately(qsid)

  def createAppIfNeeded(qsid: Qsid, rh: Head, incomingStream: Stream[F, String]): F[SessionStart[F]] =
    config.sessionAccessControl match {
      case Some(control) => createGuarded(qsid, rh, incomingStream, control)
      case None          => createLegacy(qsid, rh, incomingStream)
    }

  /**
   * Mandatory checks have a bounded wait. A late successful open is released;
   * timeout cannot cancel an already-running external database operation.
   */
  private def required[A](
    operation: => F[A],
    late: Option[A => F[Unit]] = None,
    expired: () => Throwable = () => new SessionAccessDenied
  ): F[A] =
    Effect[F].promiseF[A] { callback =>
      val completed = new AtomicBoolean(false)
      scheduler
        .scheduleOnce(10.seconds) {
          Effect[F].delay {
            if (completed.compareAndSet(false, true)) callback(Left(expired()))
          }
        }
        .flatMap { timer =>
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

  /**
   * The time bound of extension `setup` and `onDestroy` calls. It is the same
   * bounded wait that guarded checks use, with its own error: an expired call
   * is not a denied access, and it is always reported.
   */
  private val extensionBound: ExtensionRuntime.Bound[F] =
    new ExtensionRuntime.Bound[F] {
      def apply[A](operation: => F[A], late: A => F[Unit]): F[A] =
        required(operation, Some(late), () => new ExtensionRuntime.TimedOut)
    }

  private def createGuarded(
    qsid: Qsid,
    rh: Head,
    incoming: Stream[F, String],
    control: SessionAccessControl[F, S]
  ): F[SessionStart[F]] = {
    val connectionId      = ConnectionId.fromUuid(UUID.randomUUID())
    val live              = new AtomicBoolean(true)
    val cleaned           = new AtomicBoolean(false)
    val terminal          = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val terminalSignalled = new AtomicBoolean(false)
    val ready             = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val guardRef          = new AtomicReference(Option.empty[SessionGuard[F, S]])
    val appRef            = new AtomicReference(Option.empty[App])
    val frontendRef       = new AtomicReference(Option.empty[Frontend[F]])
    val managerRef        = new AtomicReference(Option.empty[GuardedStateManager[F, S]])
    val rawStateAcquired  = new AtomicBoolean(false)
    val extensionsRef     = new AtomicReference(Option.empty[Extensions])
    val idleTimer         = new AtomicReference(Option.empty[Scheduler.JobHandler[F, Unit]])

    def requireLive(): F[Unit] = Effect[F].delay(if (!live.get()) throw new SessionAccessDenied)
    def signal(): F[Unit] =
      Effect[F].delay(terminalSignalled.compareAndSet(false, true)).flatMap {
        case true  => terminal.put((), ())
        case false => Effect[F].unit
      }
    def touch(): F[Unit] = Effect[F].delay {
      if (live.get()) {
        val timer = scheduler.unsafeScheduleOnce(config.sessionIdleTimeout)(signal())
        idleTimer.getAndSet(Some(timer)).foreach(_.unsafeCancel())
      }
    }
    val monitored = new Stream[F, String] {
      def pull(): F[Option[String]] = incoming
        .pull()
        .flatMap {
          case None => signal().as(None)
          case some => touch().as(some)
        }
        .recoverF { case error => signal().flatMap(_ => Effect[F].fail(error)) }
      def cancel(): F[Unit] = incoming.cancel().recover { case _ => () }.flatMap(_ => signal())
    }
    def attempt(operation: => F[Unit]): F[Unit] = Effect[F].delayAsync(operation).recover { case error =>
      config.reporter.error("Guarded session cleanup failed", error)
      ()
    }
    def deny(error: Throwable): F[Unit] =
      Effect[F]
        .delay(live.set(false))
        .flatMap(_ => frontendRef.get().fold(monitored.cancel())(_.close()))
        .flatMap(_ => signal())
        .flatMap(_ => Effect[F].fail(error))
    def authorize(guard: SessionGuard[F, S], state: S): F[Unit] =
      requireLive().flatMap(_ => required(guard.authorize(state))).flatMap(_ => requireLive()).recoverF { case error =>
        deny(error)
      }
    // One fresh authority observation for several presentations, such as the
    // current and the produced or candidate presentation of one operation.
    def authorizeAll(guard: SessionGuard[F, S], states: List[S]): F[Unit] =
      requireLive().flatMap(_ => required(guard.authorizeEach(states))).flatMap(_ => requireLive()).recoverF {
        case error => deny(error)
      }

    final class OutputAuthorization(raw: StateManager[F], guard: SessionGuard[F, S]) {
      private def current: F[S] = raw.read[S](avocet.Id.TopLevel).flatMap {
        case Some(value) => Effect[F].pure(value)
        case None        => Effect[F].fail(new SessionAccessDenied)
      }
      private def admit[A](policy: spoonbill.server.SessionOutputAdmission[F, S], produced: S, output: A): F[A] =
        requireLive()
          .flatMap(_ => current)
          .flatMap { state =>
            required(policy(output, produced, state))
          }
          .flatMap(value => requireLive().as(value))
          .recoverF { case error => deny(error).flatMap(_ => Effect[F].fail(error)) }

      def producer(state: S): () => F[Unit] = guard.outputAdmission match {
        case None => () => authorize(guard, state)
        case Some(policy) =>
          new Frontend.FusedOutputAuthorization[F] {
            def apply(): F[Unit]          = authorize(guard, state)
            def apply[A](output: A): F[A] = admit(policy, state, output)
          }
      }
      def capture(): F[() => F[Unit]] = current.map(producer)
      val admission: Option[Frontend.OutputAdmission[F]] = guard.outputAdmission.map { policy =>
        new Frontend.OutputAdmission[F] {
          def apply[A](output: A): F[A] = current.flatMap(state => admit(policy, state, output))
        }
      }
    }

    def cleanup(): F[Unit] = Effect[F].delay(cleaned.compareAndSet(false, true)).flatMap {
      case false => Effect[F].unit
      case true =>
        for {
          // Stop extension delivery first: no callback starts once teardown began.
          _ <- attempt(Effect[F].delay(extensionsRef.get().foreach(_.stop())))
          // Retire component admission before any asynchronous close/drain can
          // settle a lease and schedule a now-unnecessary per-node deletion.
          _ <- attempt(Effect[F].delay(appRef.get().foreach(_.closeAdmission())))
          _ <- Effect[F].delay { live.set(false); idleTimer.getAndSet(None).foreach(_.unsafeCancel()) }
          _ <- attempt(ready.putEither((), Left(new SessionAccessDenied), silent = true))
          _ <- attempt(frontendRef.get().fold(monitored.cancel())(_.close()))
          // Drain already-admitted local storage writes before freeing this Qsid.
          // Do not time out this barrier and then allow an unsafe local takeover.
          _ <- managerRef.get().fold(Effect[F].unit)(_.closeAndDrain())
          _ <- attempt(appRef.get().fold(Effect[F].unit)(_.destroy()))
          _ <- attempt(guardRef.get().fold(Effect[F].unit)(guard => required(guard.close())))
          // The view is closed by now, so the access fails inside onDestroy.
          _ <- attempt(extensionsRef.get().fold(Effect[F].unit)(_.destroy()))
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

    def create(): F[SessionStart[F]] = for {
      localExists <- stateStorage.exists(qsid.deviceId, qsid.sessionId)
      guard <- required(
                 if (localExists) control.open(qsid, rh, connectionId)
                 else control.resume(qsid, rh, connectionId).getOrElse(Effect[F].fail(MissingLocalView)),
                 Some((value: SessionGuard[F, S]) => value.close())
               )
      _             <- Effect[F].delay(guardRef.set(Some(guard)))
      snapshots      = guard.viewSnapshots
      initialization = guard.applicationStateInitialization
      initializeApplication = initialization match {
                                case ApplicationStateInitialization.Retained()       => snapshots.nonEmpty
                                case ApplicationStateInitialization.Reconstructed(_) => true
                              }
      _ <- if (snapshots.nonEmpty && localExists) Effect[F].delay(rawStateAcquired.set(true)) else Effect[F].unit
      restoredManager <- if (initializeApplication) {
                           // Restore only presentation onto fresh authority. A prior process's
                           // arbitrary state/DOM cache is never the durable bootstrap baseline.
                           for {
                             application <- Effect[F].delay(applicationRequest(qsid, rh))
                             loaded      <- config.stateLoader(qsid.deviceId, application)
                             routed <-
                               config.router.toState.lift(application.pq).fold(Effect[F].pure(loaded))(f => f(loaded))
                             fresh <- required(guard.connected(routed))
                             _     <- authorize(guard, fresh)
                             restored <-
                               snapshots.fold(Effect[F].pure(fresh))(session => required(session.initialize(fresh)))
                             _ <- authorize(guard, restored)
                             manager <- snapshots match {
                                          case Some(_) =>
                                            Effect[F].pure(StateManager.cached[F](Map(avocet.Id.TopLevel -> restored)))
                                          case None =>
                                            for {
                                              local <- stateStorage.get(qsid.deviceId, qsid.sessionId)
                                              _     <- Effect[F].delay(rawStateAcquired.set(true))
                                              _     <- local.write(avocet.Id.TopLevel, restored)
                                            } yield local
                                        }
                             _ <- initialization match {
                                    case ApplicationStateInitialization.Retained() => Effect[F].unit
                                    case ApplicationStateInitialization.Reconstructed(acknowledge) =>
                                      required(acknowledge())
                                  }
                           } yield manager
                         } else {
                           stateStorage.exists(qsid.deviceId, qsid.sessionId).flatMap {
                             case true  => stateStorage.get(qsid.deviceId, qsid.sessionId)
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
                   case None        => Effect[F].fail[S](new SessionAccessDenied)
                 }
      _ <- authorize(guard, initial)
      manager = new GuardedStateManager[F, S](
                  rawManager,
                  state => authorize(guard, state),
                  snapshots.map(session => (state: S) => session.commit(state)),
                  snapshots.map(_ => (error: Throwable) => deny(error)),
                  Some(states => authorizeAll(guard, states))
                )
      _ <- Effect[F].delay(managerRef.set(Some(manager)))
      authorizeCurrent = () =>
                           rawManager.read[S](avocet.Id.TopLevel).flatMap {
                             case Some(state) => authorize(guard, state)
                             case None        => deny(new SessionAccessDenied)
                           }
      outputAuthorization = guard.outputAdmission.map(_ => new OutputAuthorization(rawManager, guard))
      // Single-owner output: one fresh observation at release checks the
      // current presentation together with the one that produced the frame.
      completeOutput = (produced: S) =>
                         new Frontend.CompleteOutputAuthorization[F] {
                           def apply(): F[Unit] = rawManager.read[S](avocet.Id.TopLevel).flatMap {
                             case Some(current) => authorizeAll(guard, List(current, produced))
                             case None          => deny(new SessionAccessDenied)
                           }
                         }
      captureProducer = () =>
                          outputAuthorization match {
                            case Some(output) => output.capture()
                            case None =>
                              rawManager.read[S](avocet.Id.TopLevel).flatMap {
                                case Some(state) => Effect[F].pure[() => F[Unit]](completeOutput(state))
                                case None        => Effect[F].fail[() => F[Unit]](new SessionAccessDenied)
                              }
                          }
      captureSensitive = guard.sensitive.map { policy => (purpose: spoonbill.sensitive.Purpose) =>
                           for {
                             produced <- rawManager.read[S](avocet.Id.TopLevel).flatMap {
                                           case Some(state) => Effect[F].pure(state)
                                           case None        => Effect[F].fail[S](new SessionAccessDenied)
                                         }
                             _        <- authorize(guard, produced)
                             audience <- required(policy.authorize(purpose, produced))
                           } yield SensitiveAuthorization[F](
                             audience,
                             () =>
                               for {
                                 _ <- authorize(guard, produced)
                                 current <- rawManager.read[S](avocet.Id.TopLevel).flatMap {
                                              case Some(state) => Effect[F].pure(state)
                                              case None        => Effect[F].fail[S](new SessionAccessDenied)
                                            }
                                 _                <- authorize(guard, current)
                                 producerAudience <- required(policy.authorize(purpose, produced))
                                 currentAudience  <- required(policy.authorize(purpose, current))
                                 _ <- Effect[F].delay {
                                        if (producerAudience != audience || currentAudience != audience)
                                          throw new SessionAccessDenied
                                      }
                                 _ <- requireLive()
                               } yield ()
                           )
                         }
      frontend = new Frontend[F](
                   monitored,
                   config.heartbeatLimit,
                   Some(connectionId),
                   Some(authorizeCurrent),
                   Some(() => ready.get(())),
                   Some(captureProducer),
                   viewRecoveryEpoch = guard.ownershipEpoch.orElse(snapshots.map(_.ownerEpoch)),
                   captureSensitiveAuthorization = captureSensitive,
                   expectedActionAuthority = guard.actionAuthority,
                   outputAdmission = outputAuthorization.flatMap(_.admission),
                   replacementCompletion = guard.replacementCompletion
                 )
      _ <- Effect[F].delay(frontendRef.set(Some(frontend)))
      // One runtime owns the extensions of this view. It observes the
      // application instance from its creation. A view without extensions has
      // none: nothing is recorded, buffered or checked for it.
      extensions = Option.when(config.extensions.nonEmpty) {
                     new ExtensionRuntime[F, S, M](
                       config.extensions,
                       config.reporter,
                       extensionBound,
                       // These checks guard extension callbacks only.
                       Some(new ExtensionRuntime.Checks[F, S, M] {
                         // A check that cannot complete denies the view, whatever
                         // the reason. So a view never goes on without delivery.
                         private def failClosed(check: F[Unit]): F[Unit] =
                           Effect[F].delayAsync(check).recoverF { case error => deny(error) }
                         // One fresh observation for the delivered and the current presentation.
                         def state(changed: S): F[Unit] = failClosed {
                           rawManager.read[S](avocet.Id.TopLevel).flatMap {
                             case Some(current) => authorizeAll(guard, List(changed, current))
                             case None          => deny(new SessionAccessDenied)
                           }
                         }
                         def message(message: M): F[Unit] = failClosed(authorizeCurrent())
                       })
                     )
                   }
      _ <- Effect[F].delay(extensionsRef.set(extensions))
      app =
        new ApplicationInstance[F, S, M](
          qsid,
          frontend,
          manager,
          initial,
          config.document,
          config.rootPath,
          config.router,
          (rc, k) => pageService.setupStatefulProxy(rc, qsid, k),
          scheduler,
          config.reporter,
          config.recovery,
          config.delayedRender,
          Some(() => ready.get(())),
          Some(state => authorize(guard, state)),
          Some(outputAuthorization.fold[S => () => F[Unit]](completeOutput)(output => state => output.producer(state))),
          timerSettings = config.timerSettings,
          observer = extensions
        )
      _         <- Effect[F].delay(appRef.set(Some(app)))
      _         <- app.initialize()
      state     <- rawManager.read[S](avocet.Id.TopLevel).map(_.getOrElse(initial))
      connected <- if (initializeApplication) Effect[F].pure(state) else required(guard.connected(state))
      _         <- authorize(guard, connected)
      _ <- if (initializeApplication) Effect[F].unit
           else required(app.topLevelComponentInstance.browserAccess.transitionForce(_ => connected))
      // The connected state is the admitted state: it passed its check above.
      // Setup stops once the connection is gone.
      _ <- extensions.fold(Effect[F].unit)(
             _.setup(
               app.topLevelComponentInstance.browserAccess,
               connected,
               () => if (terminalSignalled.get()) Effect[F].fail[Unit](new SessionAccessDenied) else requireLive()
             ).recoverF {
               // The runtime has reported the timeout. A guarded attach that
               // does not complete in time is denied, as before.
               case _: ExtensionRuntime.TimedOut => Effect[F].fail[Unit](new SessionAccessDenied)
             }
           )
      _ <- requireLive()
      _ <- apps.put(qsid, app)
      // Publish before arming cleanup, so an early disconnect cannot be followed
      // by a late put resurrecting a closed app in the local registry.
      _ <- Effect[F].start(terminal.get(()).flatMap(_ => cleanup()))
      // Delivery starts with the newest state written so far.
      _ <- extensions.fold(Effect[F].unit)(_.start())
      _ <- ready.put((), ())
      _ <- frontend.readyForUserEvents()
      _ <- touch()
      // cleanup is idempotent. The thunk stays lazy so constructing the result
      // does not release a session that is about to be attached.
    } yield SessionStart.Attached(frontend.outgoingMessages, () => cleanup())

    // Whether this attach holds the attachment of the view, and whether it has
    // completed. In between, the attach owns everything it has created so far.
    val owned    = new AtomicBoolean(false)
    val attached = new AtomicBoolean(false)

    val attach = Effect[F].delay {
      if (config.sessionIdleTimeout.toMillis <= 0 || guardedAttachments.putIfAbsent(qsid, connectionId).nonEmpty)
        throw new SessionAccessDenied
      owned.set(true)
    }.flatMap { _ =>
      retryStorageRemoval(qsid)
        .flatMap(_ => create())
        .map[SessionStart[F]] { started =>
          attached.set(true)
          started
        }
        .recoverF[SessionStart[F]] {
          // A missing baseline can be discovered after the guard is acquired.
          // Cleanup still closes that guard; the successful recovery result is reload.
          case MissingLocalView => cleanup().map(_ => SessionStart.Reload(): SessionStart[F])
          case error =>
            cleanup() *> Effect[F].delay {
              error match {
                case snapshot: spoonbill.snapshot.ViewSnapshotException =>
                  // Report only the closed error code, never a caller-supplied
                  // cause, suppressed exception, view identity or projection.
                  try
                    config.reporter.error(
                      "Guarded view snapshot setup rejected",
                      new spoonbill.snapshot.ViewSnapshotException(snapshot.error)
                    )
                  catch { case scala.util.control.NonFatal(_) => () }
                case _ => ()
              }
            } *> Effect[F].fail(error)
        }
    }

    // A cancelled attach has no caller left to clean up after it, and its close
    // watcher is not armed yet. So the attach cleans up itself: the view is
    // closed, and the extensions that are set up are destroyed. A setup that
    // returns afterwards finds the teardown done and destroys its handlers.
    // After a failure the cleanup has run already, and it runs only once.
    Effect[F].guarantee(attach) {
      if (owned.get() && !attached.get()) cleanup() else Effect[F].unit
    }
  }

  private def createLegacy(qsid: Qsid, rh: Head, incomingStream: Stream[F, String]): F[SessionStart[F]] = {
    val (incomingConsumed, managedIncomingStream) = incomingStream.handleConsumed
    val incomingHub: Hub[F, String]               = Hub(managedIncomingStream)

    def attempt(operation: => F[Unit]): F[Unit] = Effect[F].delayAsync(operation).recover { case error =>
      config.reporter.error("Legacy session cleanup failed", error)
      ()
    }

    /**
     * What this attach created for the view.
     *
     * @param restore
     *   writes back the state the page was rendered with
     */
    final case class View(frontend: Frontend[F], app: App, extensions: Option[Extensions], restore: () => F[Unit])

    // The view of this attach, once it exists. A failed attach rolls it back.
    val created = new AtomicReference(Option.empty[View])
    // Set when the connection has closed. Setup then stops before the next
    // extension, and delivery does not start.
    val connectionClosed = new AtomicBoolean(false)
    // The close watcher and a failed attach both retire the view.
    val retired = new AtomicBoolean(false)

    /**
     * Closes the view, then destroys its extensions. It runs once, and to its
     * end: a cancelled request must not leave a view half retired.
     *
     * @param restore
     *   runs when the view is closed, before its stored state is released
     * @param registry
     *   settles the registration of this view. It runs last.
     */
    def retire(view: View)(restore: () => F[Unit], registry: () => F[Unit]): F[Unit] = Effect[F].uncancelable {
      Effect[F].delay(retired.compareAndSet(false, true)).flatMap {
        case false => Effect[F].unit
        case true =>
          for {
            // Stop extension delivery first: no callback starts once teardown began.
            _ <- attempt(Effect[F].delay(view.extensions.foreach(_.stop())))
            _ <- attempt(incomingStream.cancel())
            _ <- attempt(view.frontend.close())
            _ <- attempt(view.app.destroy())
            // The view is closed by now, so the access fails inside onDestroy.
            _ <- attempt(view.extensions.fold(Effect[F].unit)(_.destroy()))
            _ <- attempt(restore())
            // A failed removal must not keep the registration: later attaches
            // of this session would wait for it without an end.
            _ <- attempt(Effect[F].delay(stateStorage.remove(qsid.deviceId, qsid.sessionId)))
            _ <- attempt(registry())
          } yield ()
      }
    }

    /**
     * A failed attach. The view is closed and its extensions are destroyed.
     * Nothing has reached the browser, so the state the page was rendered with
     * is written back: the next attach must not trust a state the browser
     * never saw. Every waiting attach gets the error.
     */
    def abandon(error: Throwable): F[Unit] = {
      // Fail the entry before it is removed, so the waiters get this error
      // and not the removal.
      val settle = () => apps.fail(qsid, error).flatMap(_ => apps.remove(qsid))
      created.get() match {
        case Some(view) => retire(view)(view.restore, settle)
        case None       => attempt(settle())
      }
    }

    def handleAppOrWsOutgoingCloseOrTimeout(view: View): F[Unit] = {

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

      consumed
        .flatMap(_ => Effect[F].delay(connectionClosed.set(true)))
        // Retire only when this attach has registered the view or rolled it
        // back. So the registry never forgets a view that is registered
        // afterwards, which would keep a retired view for good.
        .flatMap(_ => apps.settled(qsid))
        .flatMap(_ => retire(view)(() => Effect[F].unit, () => apps.remove(qsid)))
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
        // One runtime owns the extensions of this view, as in a guarded
        // session. A legacy session has no checks before a delivery.
        extensions = Option.when(config.extensions.nonEmpty) {
                       new ExtensionRuntime[F, S, M](config.extensions, config.reporter, extensionBound, None)
                     }
        app <- Effect[F].delay(new ApplicationInstance[F, S, M](
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
                config.delayedRender,
                timerSettings = config.timerSettings,
                observer = extensions
              )).recoverF { case error: DevMode.InvalidSavedRenderContext =>
                frontend.close().recover { case cleanupError => error.addSuppressed(cleanupError); () }
                  .flatMap(_ => Effect[F].fail(error))
              }
        view = View(frontend, app, extensions, () => stateManager.write(avocet.Id.TopLevel, initialState))
        // From here a failed attach rolls the view back. See attachOrReload.
        _ <- Effect[F].delay(created.set(Some(view)))
        // The watcher waits for the connection to close, and then for this
        // attach to settle. So it never retires a view that is half attached.
        _ <- Effect[F].start(handleAppOrWsOutgoingCloseOrTimeout(view))
        _ <- app.initialize()
        // The state this instance was created with is the admitted state. A
        // failed setup fails the attach. So does a connection that closes
        // during setup: the remaining extensions are not set up.
        _ <- extensions.fold(Effect[F].unit) { runtime =>
               config.reporter.debug("Extensions init")
               runtime
                 .setup(
                   app.topLevelComponentInstance.browserAccess,
                   initialState,
                   () =>
                     if (connectionClosed.get()) Effect[F].fail[Unit](new ExtensionRuntime.SetupAborted)
                     else Effect[F].unit
                 )
                 .flatMap(_ => runtime.start())
             }
      } yield {
        app
      }
    }

    // Canceling this connection's input is the legacy close signal. Keep the
    // thunk lazy: eager effects would otherwise drop the stream at construction.
    def attached(app: App): SessionStart[F] = {
      val outgoing = app.frontend.outgoingMessages
      val release = () =>
        incomingStream.cancel().recover { case _ => () } *>
          outgoing.cancel().recover { case _ => () }
      SessionStart.Attached(outgoing, release)
    }

    def attachOrReload(): F[SessionStart[F]] =
      // The attach runs to its end, also when the request is cancelled: it
      // registers the view or it rolls the view back. The close watcher waits
      // for that.
      Effect[F].uncancelable {
        apps.getFill(qsid) {
          Effect[F].delayAsync(create()).recoverF {
            case error: DevMode.InvalidSavedRenderContext =>
              // Settle all current waiters and retain a terminal failure for this old
              // session. Removing the entry would let a reconnect trust the stale DOM.
              apps.fail(qsid, error).flatMap(_ => Effect[F].fail(error))
            case error =>
              abandon(error).flatMap(_ => Effect[F].fail(error))
          }
        }
      }.map[SessionStart[F]](attached).recoverF { case error: DevMode.InvalidSavedRenderContext =>
        config.reporter.error(error.getMessage, error)
        incomingStream.cancel().recover { case cleanupError =>
          config.reporter.error(s"Unable to close invalid development session $qsid", cleanupError)
          ()
        }.map(_ => SessionStart.Reload[F](): SessionStart[F])
      }

    stateStorage.exists(qsid.deviceId, qsid.sessionId) flatMap {
      case true =>
        // State exists because it was created on static page
        // rendering phase (see ServerSideRenderingService)
        attachOrReload()
      case false =>
        // State can be missing after a restart; rebuild it from the request.
        // A failed rebuild is the recognized reload recovery, not an access failure.
        config.reporter.debug(s"State is missing for $qsid. Rebuilding from request.")
        initAppState(qsid, rh)
          .map(_ => true)
          .recover { case error =>
            config.reporter.error(s"Unable to rebuild state for $qsid", error)
            false
          }
          .flatMap {
            // A failed attach is not a failed rebuild. It fails here as it
            // does when the state exists: a reload would only repeat it.
            case true  => attachOrReload()
            case false => Effect[F].pure[SessionStart[F]](SessionStart.Reload())
          }
    }
  }

  private val stateStorage =
    if (config.stateStorage == null && config.sessionAccessControl.nonEmpty) StateStorage.ephemeral[F, S]()
    else if (config.stateStorage == null) StateStorage[F, S]()
    else config.stateStorage

  private val scheduler = new Scheduler[F]()
}
