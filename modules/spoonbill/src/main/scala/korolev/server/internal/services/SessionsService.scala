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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import spoonbill.{Metrics, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Hub, LateHandleMachine, Scheduler, Stream, Var}
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
  pageService: PageService[F, S, M],
  requiredTiming: Option[SessionsService.RequiredTiming[F]] = None,
  idleTiming: Option[SessionsService.IdleTiming[F]] = None,
  // The clock of the fresh-page records. It chooses between a view and a
  // reload; it never decides that a removal is safe.
  nanoTime: () => Long = () => System.nanoTime()
) {

  import config.executionContext
  import config.reporter.Implicit

  type App        = ApplicationInstance[F, S, M]
  type Extensions = ExtensionRuntime[F, S, M]

  private val apps = AsyncTable.unsafeCreateEmpty[F, Qsid, App]
  private type Metadata = SessionRegistryMachine.State[Qsid, ConnectionId, PathAndQuery]
  private val registryMonitor = new Object
  private var metadata = SessionRegistryMachine.State[Qsid, ConnectionId, PathAndQuery]()
  private def updateMetadata(change: Metadata => Metadata): Unit = registryMonitor.synchronized {
    metadata = change(metadata)
  }
  private def claimAttachment(qsid: Qsid, connection: ConnectionId): Boolean = registryMonitor.synchronized {
    val (next, accepted) = SessionRegistryMachine.claim(metadata, qsid, connection)
    metadata = next
    accepted
  }
  private case object MissingLocalView extends RuntimeException("Local view is unavailable")

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
    // Consume once before validating time or the current browser location.
    val bootstrap = registryMonitor.synchronized {
      val (next, value) = SessionRegistryMachine.take(metadata, qsid)
      metadata = next
      value
    }.filter { case (created, _) => SessionRegistryMachine.fresh(created, System.nanoTime(), 5.minutes.toNanos) }
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
    // Called only after claiming this attachment: a retry cannot race a newer
    // local owner. Keep external storage effects outside the metadata monitor.
    if (registryMonitor.synchronized(SessionRegistryMachine.needsRemoval(metadata, qsid))) {
      stateStorage.remove(qsid.deviceId, qsid.sessionId)
      updateMetadata(state => SessionRegistryMachine.removalSucceeded(state, qsid))
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

  /**
   * Builds and stores the state of a session for a page render, the only
   * caller. An attach never builds a state: see [[createLegacy]].
   */
  def initAppState(qsid: Qsid, rh: Head): F[S] =
    for {
      bootstrapPath <- Effect[F].delay(Option.when(config.sessionAccessControl.nonEmpty)(checkedApplicationPath(rh.pq)))
      defaultState  <- config.stateLoader(qsid.deviceId, rh)
      state <- config.router.toState
                 .lift(rh.pq)
                 .fold(Effect[F].pure(defaultState))(f => f(defaultState))
      _ <- config.sessionAccessControl.fold(Effect[F].unit)(control => required(control.authorizeHttp(rh, state)))
      _ <- config.sessionAccessControl.flatMap(_.prepareHttp(qsid, rh, state)).fold(Effect[F].unit)(required(_))
      // An unguarded page gets its fresh-page record before its state is
      // stored, so that no stored state is without a record.
      _ <- Effect[F].delay {
             if (config.sessionAccessControl.isEmpty)
               updateMetadata(metadata => SessionRegistryMachine.rendered(metadata, qsid, nanoTime()))
           }
      _ <- stateStorage.create(qsid.deviceId, qsid.sessionId, state)
      // The cleanup runs to its end: a record that it gave out for removal
      // must not stay releasing because the render was cancelled.
      _ <- Effect[F].uncancelable(Effect[F].delay(if (config.sessionAccessControl.isEmpty) expireFreshPages()))
      _ <- Effect[F].delay {
             bootstrapPath.foreach { path =>
               registryMonitor.synchronized {
                 metadata = SessionRegistryMachine.remember(metadata, qsid, System.nanoTime(), path, 5000)
               }
             }
           }
    } yield state

  def getApp(qsid: Qsid): F[Option[App]] =
    apps.getImmediately(qsid)

  /** The fresh-page records that this service holds. Only for tests. */
  private[services] def freshPageRecords: Int = registryMonitor.synchronized(metadata.freshOrder.size)

  /** How long an unguarded page has for its first poll: the idle timeout of a session. */
  private def freshLifetime: Long = config.sessionIdleTimeout.toNanos

  /**
   * A render ends at most 100 records that nothing has claimed in their
   * lifetime. It sets no timer. In a storage that is local to this process the
   * state of each such record is removed: the record is releasing meanwhile,
   * so every attach of its session is told to reload and no attach reads the
   * storage. From any other storage nothing is removed.
   */
  private def expireFreshPages(): Unit = {
    val removing = registryMonitor.synchronized {
      val (next, keys) =
        SessionRegistryMachine.expireFresh(metadata, nanoTime(), freshLifetime, 100, removes = stateStorage.localToProcess)
      metadata = next
      keys
    }
    // Outside the monitor: a claim meanwhile finds the record releasing.
    removing.foreach { qsid =>
      try {
        // Dropped for good: an unclaimed state does not go to a for-deletion cache.
        stateStorage.drop(qsid.deviceId, qsid.sessionId)
        updateMetadata(metadata => SessionRegistryMachine.freshReleased(metadata, qsid))
      } catch {
        case scala.util.control.NonFatal(error) =>
          config.reporter.error(s"Unable to remove the state of an unclaimed page $qsid", error)
          updateMetadata(metadata => SessionRegistryMachine.freshRemovalFailed(metadata, qsid))
      }
    }
  }

  /** An unguarded attach claims the fresh-page record of its session. */
  private def claimFresh(qsid: Qsid, transport: PollAdmissionMachine.Transport): SessionRegistryMachine.FreshClaim =
    registryMonitor.synchronized {
      val (next, claim) = SessionRegistryMachine.claimFresh(
        metadata,
        qsid,
        nanoTime(),
        freshLifetime,
        PollAdmissionMachine.takesExpired(transport)
      )
      metadata = next
      claim
    }

  /**
   * @param transport
   *   the transport of the attach. A poll gets the view only when it builds it.
   * @param page
   *   the page id of a poll. The view takes it as its connection id.
   * @param activity
   *   for a poll, the progress of its delivery: what resets the idle timer of
   *   the view, in place of the incoming messages
   */
  def createAppIfNeeded(
    qsid: Qsid,
    rh: Head,
    incomingStream: Stream[F, String],
    transport: PollAdmissionMachine.Transport = PollAdmissionMachine.Transport.WebSocket,
    page: Option[UUID] = None,
    activity: Option[Stream[F, Unit]] = None
  ): F[SessionStart[F]] =
    config.sessionAccessControl match {
      // A guarded page has no fresh-page record, so its claim finds none, and a
      // poll never builds its view.
      case Some(_)
          if transport == PollAdmissionMachine.Transport.Poll &&
            !PollAdmissionMachine.goesOn(transport, claimFresh(qsid, transport)) =>
        incomingStream.cancel().recover { case _ => () }.map(_ => SessionStart.Reload[F](): SessionStart[F])
      case Some(control) => createGuarded(qsid, rh, incomingStream, control)
      case None          => createLegacy(qsid, rh, incomingStream, transport, page, activity)
    }

  /**
   * Mandatory checks have a bounded wait. A late successful open is released;
   * timeout cannot cancel an already-running external database operation.
   */
  private def scheduleRequired(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]] = requiredTiming match {
    case Some(timing) => timing.scheduleOnce(10.seconds)(job)
    case None        => scheduler.scheduleOnce(10.seconds)(job)
  }

  private def unsafeScheduleIdle(job: => F[Unit]): Scheduler.JobHandler[F, Unit] = idleTiming match {
    case Some(timing) => timing.unsafeScheduleOnce(config.sessionIdleTimeout)(job)
    case None => scheduler.unsafeScheduleOnce(config.sessionIdleTimeout)(job)
  }
  private def scheduleIdle(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]] = idleTiming match {
    case Some(timing) => timing.scheduleOnce(config.sessionIdleTimeout)(job)
    case None => scheduler.scheduleOnce(config.sessionIdleTimeout)(job)
  }

  private[services] def required[A](
    operation: => F[A],
    late: Option[A => F[Unit]] = None,
    expired: () => Throwable = () => new SessionAccessDenied
  ): F[A] =
    Effect[F].promiseF[A] { callback =>
      val completed = new AtomicBoolean(false)
      scheduleRequired {
          Effect[F].delay {
            // The deadline must not depend on the execution context of the
            // application: a bound has to end a wait when every worker of the
            // application is occupied. With `Future` it completes here, on
            // the timer thread. With cats-effect and ZIO the timer thread has
            // started this effect on the runtime of the effect.
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
    val lifecycle = new AtomicReference(GuardedSessionMachine.State())
    val terminal          = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val ready             = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    final case class HeldGuard(state: LateHandleMachine.State, value: Option[SessionGuard[F, S]])
    val guardRef         = new AtomicReference(HeldGuard(LateHandleMachine.State.Waiting, None))
    val appRef           = new AtomicReference(Option.empty[App])
    val frontendRef      = new AtomicReference(Option.empty[Frontend[F]])
    val managerRef       = new AtomicReference(Option.empty[GuardedStateManager[F, S]])
    val extensionsRef    = new AtomicReference(Option.empty[Extensions])
    val idleTimer        = new AtomicReference(Option.empty[Scheduler.JobHandler[F, Unit]])

    @tailrec def transition(event: GuardedSessionMachine.Event): GuardedSessionMachine.Command = {
      val before = lifecycle.get()
      val (next, command) = GuardedSessionMachine.step(before, event)
      if (next eq before) command
      else if (lifecycle.compareAndSet(before, next)) command
      else transition(event)
    }

    // Register inside the detached acquisition, before required publishes its
    // result. A canceled waiter can finish teardown while open/resume is pending.
    // The late guard then closes here rather than being handed to that waiter.
    def registerGuard(guard: SessionGuard[F, S]): F[SessionGuard[F, S]] = Effect[F].delay {
      @tailrec def install(): Boolean = {
        val before          = guardRef.get()
        val (next, command) = LateHandleMachine.step(before.state, LateHandleMachine.Event.Install)
        if (command == LateHandleMachine.Command.CancelIncoming) false
        else if (guardRef.compareAndSet(before, HeldGuard(next, Some(guard)))) true
        else install()
      }
      if (!install()) Effect[F].delayAsync(guard.close()).runAsyncForget
      guard
    }
    @tailrec def takeGuard(): Option[SessionGuard[F, S]] = {
      val before          = guardRef.get()
      val (next, command) = LateHandleMachine.step(before.state, LateHandleMachine.Event.Finish)
      if (command == LateHandleMachine.Command.Ignore) None
      else if (guardRef.compareAndSet(before, HeldGuard(next, None))) before.value
      else takeGuard()
    }

    def requireLive(): F[Unit] = Effect[F].delay(if (!GuardedSessionMachine.live(lifecycle.get())) throw new SessionAccessDenied)
    def signal(): F[Unit] =
      Effect[F].delay(transition(GuardedSessionMachine.Event.Signal)).flatMap {
        case GuardedSessionMachine.Command.Signal => terminal.put((), ())
        case _ => Effect[F].unit
      }
    def touch(): F[Unit] = Effect[F].delay {
      if (GuardedSessionMachine.live(lifecycle.get())) {
        val timer = unsafeScheduleIdle(signal())
        val installed: Option[Scheduler.JobHandler[F, Unit]] = Some(timer)
        idleTimer.getAndSet(installed).foreach(_.unsafeCancel())
        // Teardown may finish before scheduling returns its handle. Claim only
        // this installation: cleanup or a later rearm may already own it.
        if (!GuardedSessionMachine.live(lifecycle.get()) && idleTimer.compareAndSet(installed, None))
          timer.unsafeCancel()
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
        .delay(transition(GuardedSessionMachine.Event.Deny))
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

    // The cleanup runs once, and to its end. It claims its turn before it does
    // anything. If a cancelled request could stop it halfway, nobody would
    // finish it: the next caller finds the turn taken and returns. The
    // extensions that are set up would not be destroyed, and a setup that
    // returns late would keep its handlers. So a cancellation waits until the
    // cleanup is done, as it does when a legacy view is retired.
    def cleanup(): F[Unit] = Effect[F].uncancelable {
      Effect[F].delay(transition(GuardedSessionMachine.Event.BeginCleanup)).flatMap(teardown).recoverF { case error =>
        Effect[F].delay(transition(GuardedSessionMachine.Event.CleanupFailed)).flatMap(_ => Effect[F].fail(error))
      }
    }

    /** Advance only after each stage settles; an admitted-write drain has no timeout. */
    def teardown(command: GuardedSessionMachine.Command): F[Unit] = command match {
      case GuardedSessionMachine.Command.Run(stage) =>
        import GuardedSessionMachine.Stage
        val operation: F[Unit] = stage match {
          case Stage.StopExtensions => attempt(Effect[F].delay(extensionsRef.get().foreach(_.stop())))
          case Stage.StopComponents => attempt(Effect[F].delay(appRef.get().foreach(_.closeAdmission())))
          case Stage.DenyAndStopIdle => Effect[F].delay {
            transition(GuardedSessionMachine.Event.Deny)
            idleTimer.getAndSet(None).foreach(_.unsafeCancel())
          }
          case Stage.RejectReady => attempt(ready.putEither((), Left(new SessionAccessDenied), silent = true))
          case Stage.CloseFrontend => attempt(frontendRef.get().fold(monitored.cancel())(_.close()))
          case Stage.DrainWrites => managerRef.get().fold(Effect[F].unit)(_.closeAndDrain())
          case Stage.DestroyApplication => attempt(appRef.get().fold(Effect[F].unit)(_.destroy()))
          case Stage.CloseGuard =>
            attempt(Effect[F].delay(takeGuard()).flatMap(_.fold(Effect[F].unit)(guard => required(guard.close()))))
          case Stage.DestroyExtensions => attempt(extensionsRef.get().fold(Effect[F].unit)(_.destroy()))
          case Stage.RemoveStorage =>
            if (GuardedSessionMachine.ownsStorage(lifecycle.get())) {
              Effect[F].delay(stateStorage.remove(qsid.deviceId, qsid.sessionId)).recover { case error =>
                updateMetadata(state => SessionRegistryMachine.removalFailed(state, qsid))
                config.reporter.error("Guarded storage cleanup failed; retry required before restoration", error)
                ()
              }
            } else Effect[F].unit
          case Stage.RemoveApplication => attempt(apps.remove(qsid))
          case Stage.ReleaseAttachment => Effect[F].delay { updateMetadata(state => SessionRegistryMachine.release(state, qsid, connectionId)); () }
        }
        operation.flatMap(_ => Effect[F].delay(transition(GuardedSessionMachine.Event.Finished(stage)))).flatMap(teardown)
      case _ => Effect[F].unit
    }

    def create(): F[SessionStart[F]] = for {
      localExists <- stateStorage.exists(qsid.deviceId, qsid.sessionId)
      guard <- required(
                 (if (localExists) control.open(qsid, rh, connectionId)
                  else control.resume(qsid, rh, connectionId).getOrElse(Effect[F].fail(MissingLocalView)))
                   .flatMap(registerGuard)
               )
      snapshots      = guard.viewSnapshots
      initialization = guard.applicationStateInitialization
      attachMode = GuardedSessionMachine.attachMode(snapshots.nonEmpty, initialization match {
                     case ApplicationStateInitialization.Retained() => false
                     case ApplicationStateInitialization.Reconstructed(_) => true
                   })
      _ <- if (snapshots.nonEmpty && localExists) Effect[F].delay { transition(GuardedSessionMachine.Event.AcquireStorage); () } else Effect[F].unit
      restoredManager <- if (GuardedSessionMachine.initializeBeforeRender(attachMode)) {
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
                                              _     <- Effect[F].delay(transition(GuardedSessionMachine.Event.AcquireStorage))
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
                      if (GuardedSessionMachine.usesLocalStorage(attachMode)) transition(GuardedSessionMachine.Event.AcquireStorage)
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
      connected <- if (GuardedSessionMachine.initializeBeforeRender(attachMode)) Effect[F].pure(state) else required(guard.connected(state))
      _         <- authorize(guard, connected)
      _ <- if (GuardedSessionMachine.initializeBeforeRender(attachMode)) Effect[F].unit
           else required(app.topLevelComponentInstance.browserAccess.transitionForce(_ => connected))
      // The connected state is the admitted state: it passed its check above.
      // Setup stops once the connection is gone.
      _ <- extensions.fold(Effect[F].unit)(
             _.setup(
               app.topLevelComponentInstance.browserAccess,
               connected,
               () => if (GuardedSessionMachine.signalled(lifecycle.get())) Effect[F].fail[Unit](new SessionAccessDenied) else requireLive()
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

    val attach = Effect[F].delay {
      if (config.sessionIdleTimeout.toMillis <= 0 || !claimAttachment(qsid, connectionId))
        throw new SessionAccessDenied
      transition(GuardedSessionMachine.Event.Claim)
    }.flatMap { _ =>
      retryStorageRemoval(qsid)
        .flatMap(_ => create())
        .map[SessionStart[F]] { started =>
          transition(GuardedSessionMachine.Event.Attach)
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
      if (GuardedSessionMachine.needsRollback(lifecycle.get())) cleanup() else Effect[F].unit
    }
  }

  private def createLegacy(
    qsid: Qsid,
    rh: Head,
    incomingStream: Stream[F, String],
    transport: PollAdmissionMachine.Transport,
    page: Option[UUID],
    activity: Option[Stream[F, Unit]]
  ): F[SessionStart[F]] = {
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
    val lifecycle = new AtomicReference(LegacySessionMachine.State())
    @tailrec def transition(event: LegacySessionMachine.Event): LegacySessionMachine.Command = {
      val before = lifecycle.get()
      val (next, command) = LegacySessionMachine.step(before, event)
      if (next eq before) command
      else if (lifecycle.compareAndSet(before, next)) command
      else transition(event)
    }

    /**
     * Closes the view, then destroys its extensions. It runs once, and to its
     * end: a cancelled request must not leave a view half retired.
     *
     * @param restore
     *   runs when the view is closed, before its stored state is released
     * @param registry
     *   settles the registration of this view. It runs last.
     */
    def retire(view: View, cause: LegacySessionMachine.Cause)(restore: () => F[Unit], registry: () => F[Unit]): F[Unit] = {
      def execute(command: LegacySessionMachine.Command): F[Unit] = command match {
        case LegacySessionMachine.Command.Run(stage) =>
          import LegacySessionMachine.Stage
          val operation: F[Unit] = stage match {
            case Stage.StopExtensions => attempt(Effect[F].delay(view.extensions.foreach(_.stop())))
            case Stage.CancelInput => attempt(incomingStream.cancel())
            case Stage.CloseFrontend => attempt(view.frontend.close())
            case Stage.DestroyApplication => attempt(view.app.destroy())
            case Stage.DestroyExtensions => attempt(view.extensions.fold(Effect[F].unit)(_.destroy()))
            case Stage.Restore => attempt(restore())
            case Stage.RemoveStorage => attempt(Effect[F].delay(stateStorage.remove(qsid.deviceId, qsid.sessionId)))
            case Stage.SettleRegistry => attempt(registry())
          }
          operation.flatMap(_ => Effect[F].delay(transition(LegacySessionMachine.Event.Finished(stage)))).flatMap(execute)
        case _ => Effect[F].unit
      }
      Effect[F].uncancelable {
        Effect[F].delay(transition(LegacySessionMachine.Event.Retire(cause))).flatMap(execute).recoverF { case error =>
          Effect[F].delay(transition(LegacySessionMachine.Event.Failed)).flatMap(_ => Effect[F].fail(error))
        }
      }
    }

    /**
     * A failed attach. The view is closed and its extensions are destroyed.
     * Nothing has reached the browser, so the state the page was rendered with
     * is written back: the next attach must not trust a state the browser
     * never saw. Every waiting attach gets the error.
     */
    def abandon(): F[Unit] = {
      // The table entry is left alone here. This attach fills it, and the
      // fill ends with the error: the table then gives the error to every
      // attach that waits. An entry that is failed or removed before the fill
      // has ended is written again when it ends, and that failure would stay.
      created.get() match {
        case Some(view) => retire(view, LegacySessionMachine.Cause.Abandoned)(view.restore, () => Effect[F].unit)
        // Nothing was built, so nothing is retired. The machine still learns
        // that this attach was rolled back. In a storage that is local to this
        // process the state goes: this attach holds the place of the session in
        // the table of views, so no other attach reads it meanwhile. A WebSocket
        // that comes again takes it back from the for-deletion cache of the
        // default storage; a poll that comes again is told to reload.
        case None =>
          Effect[F].delay {
            transition(LegacySessionMachine.Event.NothingBuilt(removesState = stateStorage.localToProcess)) match {
              case LegacySessionMachine.Command.RemoveState => stateStorage.remove(qsid.deviceId, qsid.sessionId)
              case _                                        => ()
            }
          }
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
          def createTimeout[A](stream: Stream[F, A]): F[Scheduler.JobHandler[F, Unit]] =
            scheduleIdle {
              invokeOnce("session idle timeout")(Right(()))
              stream.cancel()
            }

          // Every element of `in` is a sign of life and resets the timer.
          def watch[A](in: Stream[F, A]): F[Unit] =
            for {
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

          // A WebSocket view lives while its messages come. A poll's view
          // lives while its page makes progress: a page that sends heartbeats
          // and clicks but never moves its cursor does not keep it.
          activity match {
            case Some(progress) => watch(progress)
            case None           => incomingHub.newStream().flatMap(watch(_))
          }
        }

        handleCommunicationTimeout().runAsyncForget
        incomingConsumed.runAsync(invokeOnce("due connection close"))
      }

      consumed
        .recover {
          case _: CancellationException => ()
          case error =>
            config.reporter.error("Legacy connection closed with an error", error)
            ()
        }
        .flatMap(_ => Effect[F].delay(transition(LegacySessionMachine.Event.ConnectionClosed)))
        // Retire only when this attach has registered the view or rolled it
        // back. So the registry never forgets a view that is registered
        // afterwards, which would keep a retired view for good.
        .flatMap(_ => apps.settled(qsid))
        .flatMap(_ => retire(view, LegacySessionMachine.Cause.Disconnected)(() => Effect[F].unit, () => apps.remove(qsid)))
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
        frontend = new Frontend[F](
                     in,
                     config.heartbeatLimit,
                     connectionId = page.map(ConnectionId.fromUuid),
                     resumes = transport == PollAdmissionMachine.Transport.Poll
                   )
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
                     if (LegacySessionMachine.connectionClosed(lifecycle.get())) Effect[F].fail[Unit](new ExtensionRuntime.SetupAborted)
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
    //
    // Who may have the output of the view: see PollAdmissionMachine.output. A
    // view that a poll built is the one whose frontend resumes, and this attach
    // built it when its own `create` made it.
    def attached(app: App): F[SessionStart[F]] = {
      val owner = if (app.frontend.resuming) PollAdmissionMachine.Transport.Poll else PollAdmissionMachine.Transport.WebSocket
      val built = created.get().exists(_.app eq app)
      def leave(start: SessionStart[F]): F[SessionStart[F]] =
        incomingStream.cancel().recover { case _ => () }.map(_ => start)
      PollAdmissionMachine.output(owner, built, transport) match {
        case PollAdmissionMachine.Output.Owns if transport == PollAdmissionMachine.Transport.Poll =>
          val frames = app.frontend.outgoingFrames
          // The delivery runs it when the frames end. The input is cancelled
          // through its managed stream: a view whose input failed reads it no
          // more, so only that cancel tells the watcher, and the view retires.
          val release = () =>
            managedIncomingStream.cancel().recover { case _ => () } *>
              frames.cancel().recover { case _ => () }
          Effect[F].pure(SessionStart.Owned(frames, release))
        case PollAdmissionMachine.Output.Owns | PollAdmissionMachine.Output.Shared =>
          val outgoing = app.frontend.outgoingMessages
          val release = () =>
            incomingStream.cancel().recover { case _ => () } *>
              outgoing.cancel().recover { case _ => () }
          Effect[F].pure(SessionStart.Attached(outgoing, release))
        case PollAdmissionMachine.Output.Refused => leave(SessionStart.Refused())
        case PollAdmissionMachine.Output.Reload  => leave(SessionStart.Reload())
      }
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
              abandon().flatMap(_ => Effect[F].fail(error))
          }
        }.recoverF { case error =>
          // The fill has ended, and every attach that waited for it has its
          // error. A view that was rolled back leaves nothing behind, so its
          // failure must not answer the attaches that come later: the page
          // reconnects, and that attach builds a view of its own. Only the
          // attach that rolled back takes the entry out, once, and in the
          // same step that a cancelled request cannot split. The machine
          // knows which attach that is. An attach that only waited, and a
          // terminal failure such as an invalid render context, leave it.
          if (transition(LegacySessionMachine.Event.Published) == LegacySessionMachine.Command.Forget)
            apps.remove(qsid).flatMap(_ => Effect[F].fail[App](error))
          else Effect[F].fail[App](error)
        }
      }.flatMap(attached).recoverF { case error: DevMode.InvalidSavedRenderContext =>
        config.reporter.error(error.getMessage, error)
        incomingStream.cancel().recover { case cleanupError =>
          config.reporter.error(s"Unable to close invalid development session $qsid", cleanupError)
          ()
        }.map(_ => SessionStart.Reload[F](): SessionStart[F])
      }

    def withRecord(attach: => F[SessionStart[F]]): F[SessionStart[F]] =
      if (PollAdmissionMachine.goesOn(transport, claimFresh(qsid, transport))) attach
      else
        // A poll goes on only for a page that this server rendered and that
        // nothing has claimed in its lifetime. A record whose state is being
        // removed refuses every attach.
        Effect[F].delay(config.reporter.debug(s"No fresh page for $qsid; the page is told to reload")) *>
          incomingStream.cancel().recover { case _ => () }.map(_ => SessionStart.Reload[F](): SessionStart[F])

    // The claim of the record, the look into the storage and the attach are
    // steps of one effect: a throw on the way is a failed effect that the
    // caller sees, and a cancelled request cannot stop it between the claim
    // and the attach, which would leave a state without record or view.
    Effect[F].uncancelable(Effect[F].delayAsync(withRecord(stateStorage.exists(qsid.deviceId, qsid.sessionId) flatMap {
      case true =>
        // State exists because it was created on static page
        // rendering phase (see ServerSideRenderingService)
        attachOrReload()
      case false =>
        // An unguarded attach never builds a state. Its request is the bridge
        // request, which matches no route of the application, so a state built
        // from it would show another page than the one in the browser. The
        // state is missing after a restart, after a removal, or with a storage
        // over a repository after the view before retired. The page is told to
        // reload: it is rendered again from its own address, under a new
        // session. ADR 0006 records this decision, its cost and when to
        // revisit it.
        // Like every rejected session, it releases its input first.
        Effect[F].delay {
          Metrics.MissingStateReloads.update(_ + 1)
          config.reporter.debug(s"State is missing for $qsid; the page is told to reload")
        } *> incomingStream.cancel().recover { case cleanupError =>
          config.reporter.error(s"Unable to close session $qsid without a stored state", cleanupError)
          ()
        }.map(_ => SessionStart.Reload[F](): SessionStart[F])
    })))
  }

  private val stateStorage =
    if (config.stateStorage == null && config.sessionAccessControl.nonEmpty) StateStorage.ephemeral[F, S]()
    else if (config.stateStorage == null) StateStorage[F, S]()
    else config.stateStorage

  private val scheduler = new Scheduler[F]()
}

private[spoonbill] object SessionsService {
  /** Internal timing seam: expiry still executes the same effect on the scheduler's thread/runtime. */
  trait RequiredTiming[F[_]] {
    def scheduleOnce(delay: FiniteDuration)(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]]
  }
  /** Idle rearming keeps synchronous and effectful handle installation distinct. */
  trait IdleTiming[F[_]] {
    def unsafeScheduleOnce(delay: FiniteDuration)(job: => F[Unit]): Scheduler.JobHandler[F, Unit]
    def scheduleOnce(delay: FiniteDuration)(job: => F[Unit]): F[Scheduler.JobHandler[F, Unit]]
  }
}
