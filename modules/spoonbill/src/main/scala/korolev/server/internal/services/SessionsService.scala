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

import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Hub, Scheduler, Stream, Var}
import spoonbill.effect.syntax.*
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.server.SpoonbillServiceConfig
import spoonbill.server.internal.{BadRequestException, Cookies}
import spoonbill.state.{StateDeserializer, StateSerializer, StateStorage}
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
      defaultState <- config.stateLoader(qsid.deviceId, rh)
      state <- config.router.toState
                 .lift(rh.pq)
                 .fold(Effect[F].pure(defaultState))(f => f(defaultState))
      _ <- stateStorage.create(qsid.deviceId, qsid.sessionId, state)
    } yield state

  def getApp(qsid: Qsid): F[Option[App]] =
    apps.getImmediately(qsid)

  def createAppIfNeeded(qsid: Qsid, rh: Head, incomingStream: Stream[F, String]): F[Unit] = {
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
        _ <- ehs.map(_.onDestroy()).sequence
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
    if (config.stateStorage == null) StateStorage[F, S]()
    else config.stateStorage

  private val scheduler = new Scheduler[F]()
}
