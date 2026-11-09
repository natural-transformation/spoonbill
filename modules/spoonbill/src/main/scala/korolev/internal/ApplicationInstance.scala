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

import spoonbill.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import spoonbill.*
import spoonbill.Context.*
import spoonbill.effect.{Effect, Hub, Queue, Reporter, Scheduler, Stream}
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend.DomEventMessage
import spoonbill.state.StateDeserializer
import spoonbill.state.StateManager
import spoonbill.state.StateSerializer
import spoonbill.web.Path
import spoonbill.web.PathAndQuery
import avocet.Document
import avocet.Id
import avocet.StatefulRenderContext
import avocet.XmlNs
import avocet.events.{calculateEventPropagation, EventId}
import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.ChangesPerformer
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

final class ApplicationInstance[
  F[_]: Effect,
  S: StateSerializer: StateDeserializer,
  M
](
  sessionId: Qsid,
  val frontend: Frontend[F],
  stateManager: StateManager[F],
  initialState: S,
  render: S => Document.Node[Binding[F, S, M]],
  rootPath: Path,
  router: Router[F, S],
  createMiscProxy: (
    StatefulRenderContext[Binding[F, S, M]],
    (StatefulRenderContext[Binding[F, S, M]], Binding[F, S, M]) => Unit
  ) => StatefulRenderContext[Binding[F, S, M]],
  scheduler: Scheduler[F],
  reporter: Reporter,
  recovery: PartialFunction[Throwable, S => S],
  delayedRender: FiniteDuration,
  awaitReady: Option[() => F[Unit]] = None,
  authorizeProduced: Option[S => F[Unit]] = None,
  captureProducedAuthorization: Option[S => (() => F[Unit])] = None,
  timerSettings: spoonbill.timer.TimerSettings = spoonbill.timer.TimerSettings(),
  timerDeadlines: Option[Frontend.RpcDeadlineScheduler] = None,
  timerClock: () => Long = () => System.nanoTime()
)(implicit ec: ExecutionContext) { application =>

  import reporter.Implicit

  private def outputAuthorization(state: S): () => F[Unit] =
    captureProducedAuthorization.fold(() => authorizeProduced.fold(frontend.authorizeInteraction())(check => check(state)))(_(state))

  private val devMode       = new DevMode.ForRenderContext(sessionId.toString)
  private val eventCounters = new TrieMap[(Id, String), Int]()
  private val stateQueue    = Queue[F, (Id, Any, Option[Effect.Promise[Unit]])]()
  private val stateHub      = Hub(stateQueue.stream)
  private val messagesQueue = Queue[F, M]()
  private val publishedEventHandlers = new AtomicReference(Map.empty[EventId, Vector[DomEventMessage => F[Boolean]]])
  private val timers = new TimerRuntime[F](
    timerDeadlines.getOrElse(TimerRuntime.deadlineDriver(scheduler)),
    timerClock,
    ec,
    reporter,
    timerSettings
  )

  private val renderContext = {
    DiffRenderContext[Binding[F, S, M]](savedBuffer = if (frontend.guarded) None else devMode.loadRenderContext())
  }
  // The current candidate becomes the next baseline only after the frontend
  // accepts it. Failed rendering or publication must keep the previous DOM.
  private var renderAccepted = false

  private def acceptRender(): Unit = {
    renderAccepted = true
    timers.acceptTimerRender()
  }

  private def abortRender(): Unit = {
    if (!renderAccepted) renderContext.reset()
    timers.abortRender()
  }

  val topLevelComponentInstance: ComponentInstance[F, S, M, S, Any, M] = {
    val eventRegistry = new EventRegistry[F](frontend)
    val component = new Component[F, S, Any, M](initialState, Component.TopLevelComponentId) {
      def render(parameters: Any, state: S): Document.Node[Binding[F, S, M]] =
        try {
          application.render(state)
        } catch {
          case e: MatchError =>
            Document.Node[Binding[F, S, M]] { rc =>
              reporter.error(s"Render is not defined for $state")
              rc.openNode(XmlNs.html, "html")
              rc.openNode(XmlNs.html, "body")
              rc.addTextNode("Render is not defined for the state. ")
              rc.addTextNode(e.getMessage())
              rc.closeNode("body")
              rc.closeNode("html")
            }
        }
    }
    val componentInstance = new ComponentInstance[F, S, M, S, Any, M](
      Id.TopLevel,
      sessionId,
      frontend,
      eventRegistry,
      stateManager,
      component,
      stateQueue,
      createMiscProxy,
      scheduler,
      reporter,
      { case ex: Throwable =>
        topLevelComponentInstance.browserAccess.transition(recovery(ex))
      },
      ec,
      timers = Some(timers)
    )
    componentInstance.setEventsSubscription(messagesQueue.offerUnsafe)
    componentInstance
  }

  /**
   * If dev mode is enabled save render context
   */
  private def saveRenderContextIfNecessary(): F[Unit] =
    if (devMode.isActive && !frontend.guarded) Effect[F].delay(devMode.saveRenderContext(renderContext))
    else Effect[F].unit

  private def onState(maybeRenderCallback: Seq[Effect.Promise[Unit]]): F[Unit] =
    (for {
      snapshot <- topLevelComponentInstance.stateSnapshot
      producedState = snapshot[S](Id.TopLevel).getOrElse(initialState)
      // Set page url if router exists
      _ <- router.fromState
             .lift(producedState)
             .fold(Effect[F].unit) { uri => authorizeProduced match {
               case Some(_) => frontend.changePageUrlAuthorized(rootPath ++ uri, outputAuthorization(producedState))
               case None => frontend.changePageUrl(rootPath ++ uri)
             }}
      _ <- Effect[F].delay {
             timers.beginRender()
             // Prepare render context
             if (renderAccepted) {
               renderContext.swap()
               renderAccepted = false
             }
             // Discard any rejected candidate while retaining the accepted baseline.
             renderContext.reset()
             // Perform rendering
             topLevelComponentInstance.applyRenderContext(
               parameters = (), // Boxed unit as parameter. Top level component doesn't need parameters
               snapshot = snapshot,
               rc = renderContext
             )
             // Avocet's DiffRenderContext expects the freshly-rendered document to be finalized
             // (flip the buffer + reset the id builder) before running `diff`.
             renderContext.finalizeDocument()
             timers.prepareRender()
           }
      // Infer and perform changes
      handlers = if (frontend.recoversView) topLevelComponentInstance.allEventHandlers.toMap
        else Map.empty[EventId, Vector[DomEventMessage => F[Boolean]]]
      _ <- authorizeProduced match {
             case Some(_) =>
               frontend.performDomChangesAuthorized(
                 renderContext.diff,
                 outputAuthorization(producedState),
                 () => { publishedEventHandlers.set(handlers); acceptRender() }
               )
             case None => frontend.performDomChangesAccepted(renderContext.diff, () => acceptRender())
           }
      _ <- Effect[F].delay { timers.flush(); topLevelComponentInstance.dropObsoleteMisc() }
      _ <- saveRenderContextIfNecessary()
      // Make spoonbill ready to next render
      _ = maybeRenderCallback.foreach(_(Right(())))
    } yield ()).recoverF { case error =>
      abortRender()
      maybeRenderCallback.foreach(_(Left(error)))
      Effect[F].fail(error)
    }

  private def awaitReadiness: F[Unit] =
    awaitReady.fold(Effect[F].unit)(ready => Effect[F].delayAsync(ready()))

  private def beforeInteraction: F[Unit] =
    awaitReadiness.flatMap(_ => frontend.authorizeInteraction())

  /**
   * A guarded frontend's action queue authorizes each action immediately
   * before running it, so the action itself only awaits readiness. Unguarded
   * actions run directly and keep their own interaction check.
   */
  private def beforeAdmittedInteraction: F[Unit] =
    if (frontend.guarded) awaitReadiness else beforeInteraction

  private def onHistory(message: Frontend.BrowserHistoryMessage): F[Unit] =
    frontend.runUserAction(
      beforeAdmittedInteraction
        .flatMap(_ =>
          topLevelComponentInstance.updateFromHistory { current =>
            router.toState
              .lift(message.path)
              .fold(Effect[F].delay(Option.empty[S]))(_(current).map(Some(_)))
          }
        )
        .flatMap(_ => message.sensitiveNavigation.fold(Effect[F].unit)(frontend.completeSensitiveNavigation)),
      message.renderRevision
    )

  private def onEvent(dem: DomEventMessage): F[Unit] = {
    // The event's admission check authorized the first handler: only
    // synchronous revision and handler selection run in between. A later
    // handler starts after earlier user effects, which may have suspended, so
    // it observes current authority again.
    def aux(effects: List[DomEventMessage => F[Boolean]], admitted: Boolean): F[Unit] =
      effects match {
        case Nil => Effect[F].unit
        case effect :: xs =>
          val authorized = if (admitted) Effect[F].unit else frontend.authorizeInteraction()
          authorized.flatMap(_ => effect(dem)).flatMap { stopPropagation =>
            if (stopPropagation) Effect[F].unit
            else aux(xs, admitted = false)
          }
      }
    val k = (dem.target, dem.eventType)
    def process: F[Unit] = beforeAdmittedInteraction.flatMap(_ => Effect[F]
      .delay(eventCounters.getOrElse(k, 0))
      .flatMap { eventCounter =>
        if (eventCounter == dem.eventCounter) {
          // Capture bindings for the admitted revision atomically with render
          // publication. Release the lock before any user effect/browser RPC.
          frontend.selectFromPublishedBrowserView(dem.renderRevision) {
            val propagation = calculateEventPropagation(dem.target, dem.eventType)
            if (frontend.recoversView) {
              val allHandlers = publishedEventHandlers.get()
              propagation.toList.flatMap(eventId => allHandlers.getOrElse(eventId, Vector.empty))
            } else {
              val allHandlers = topLevelComponentInstance.allEventHandlers
              propagation.toList.flatMap(eventId => allHandlers.getOrElse(eventId, Vector.empty))
            }
          }.flatMap { allEffects =>
            if (allEffects.nonEmpty) {
              for {
                _             <- aux(allEffects, admitted = true)
                newEventConter = dem.eventCounter + 1
                _             <- Effect[F].delay(eventCounters.put(k, newEventConter))
                _             <- frontend.setEventCounter(dem.target, dem.eventType, newEventConter)
              } yield ()
            } else Effect[F].unit
          }
        } else {
          Effect[F].unit
        }
      }
      .recover {
        // A newer render won during authorization. The event was discarded and
        // counted by the frontend; it is not an application error.
        case Frontend.ObsoleteBrowserEvent => ()
        case error                         => reporter.error(s"Unable to process event $dem", error)
      })
    if (frontend.guarded) frontend.runBrowserAction(process, dem.renderRevision, dem.sensitiveDeparture) else process.start.unit
  }

  private final val internalStateStream =
    stateHub.newStreamUnsafe()

  val stateStream: F[Stream[F, (Id, Any)]] =
    stateHub.newStream().map { stream =>
      stream.map { case (id, state, _) =>
        (id, state)
      }
    }

  val messagesStream: Stream[F, M] =
    messagesQueue.stream

  private[spoonbill] def closeAdmission(): Unit = topLevelComponentInstance.closeAdmission()

  def destroy(): F[Unit] = for {
    _ <- stateQueue.close()
    _ <- messagesQueue.close()
    _ <- topLevelComponentInstance.destroy()
  } yield ()

  private def startTimersWhenReady(): F[Unit] = awaitReady match {
    case None => Effect[F].delay(timers.start())
    case Some(_) =>
      Effect[F].start {
        awaitReadiness.flatMap(_ => Effect[F].delay(timers.start())).recover {
          case _: spoonbill.server.SessionAccessDenied => ()
          case error                                   => reporter.error("Unable to start render timers", error)
        }
      }.unit
  }

  def initialize()(implicit ec: ExecutionContext): F[Unit] = {

    // If dev mode is enabled and active
    // CSS should be reloaded
    def reloadCssIfNecessary() =
      if (devMode.isActive) frontend.reloadCss()
      else Effect[F].unit

    // Render current state using 'performDiff'.
    def render() =
      for {
        snapshot <- topLevelComponentInstance.stateSnapshot
        _ <- Effect[F].delay {
               timers.beginRender()
               topLevelComponentInstance.applyRenderContext((), renderContext, snapshot)
               // Avocet's DiffRenderContext expects the freshly-rendered document to be finalized
               // (flip the buffer + reset the id builder) before running `diff`.
               renderContext.finalizeDocument()
               timers.prepareRender()
             }
        _ <- frontend.performDomChangesAccepted(renderContext.diff, () => acceptRender())
        _ <- Effect[F].delay { timers.flush(); topLevelComponentInstance.dropObsoleteMisc() }
        _ <- saveRenderContextIfNecessary()
      } yield ()

    // Initial render for the "pre-rendered page" startup path.
    //
    // We intentionally DO NOT run `diff` here:
    // - There is nothing to apply to the browser (the page is assumed to match the server render).
    // - Diffing against an empty rhs buffer can put Avocet's internal IdBuilder into an invalid state,
    //   which breaks subsequent renders (see Issue14Spec after the Avocet upgrade).
    def renderInitialState(): F[Unit] =
      for {
        snapshot <- topLevelComponentInstance.stateSnapshot
        _ <- Effect[F].delay {
               timers.beginRender()
               if (frontend.recoversView) {
                 // The client resets to this empty root, so no prior DOM,
                 // patch history or process-local buffer is trusted.
                 renderContext.openNode(XmlNs.html, "html")
                 renderContext.closeNode("html")
                 renderContext.finalizeDocument()
                 renderContext.swap()
                 renderContext.reset()
               }
               topLevelComponentInstance.applyRenderContext((), renderContext, snapshot)
               renderContext.finalizeDocument()
               timers.prepareRender()
             }
        _ <- if (frontend.recoversView) {
               val produced = snapshot[S](Id.TopLevel).getOrElse(initialState)
               val handlers = topLevelComponentInstance.allEventHandlers.toMap
               frontend.resetDomChangesAuthorized(
                 renderContext.diff,
                 outputAuthorization(produced),
                 () => { publishedEventHandlers.set(handlers); acceptRender() }
               )
             } else frontend.acceptInitialRender(() => acceptRender())
        _ <- Effect[F].delay { timers.flush(); topLevelComponentInstance.dropObsoleteMisc() }
        _ <- saveRenderContextIfNecessary()
      } yield ()

    def start(): F[Unit] = if (devMode.saved && !frontend.guarded) {

      // Initialize with
      // 1. Old page in users browser
      // 2. Has saved render context
      for {
        _ <- frontend.resetEventCounters()
        _ <- reloadCssIfNecessary()
        _ <- Effect[F].delay {
               renderContext.swap()
               renderContext.reset()
             }
        // Serialized render context exists.
        // It means that user is looking at page
        // generated by old code. The code may
        // consist changes in render, so we
        // should deliver them to the user.
        _ <- render()
        // Start handlers (same as the "pre-rendered page" init path).
        _ <- frontend.browserHistoryMessages
               .foreach(onHistory)
               .start
        _ <- frontend.domEventMessages
               .foreach(onEvent)
               .start
        _ <- if (delayedRender.toMillis > 0) {
               internalStateStream
                 .buffer(delayedRender)
                 .foreach { xs =>
                   onState(xs.flatMap(_._3)).recover { case error =>
                     reporter.error("Unable to process buffered state update", error)
                     ()
                   }
                 }
                 .start
             } else {
               internalStateStream.foreach { x =>
                 onState(x._3.toSeq).recover { case error =>
                   reporter.error("Unable to process state update", error)
                   ()
                 }
               }.start
             }
        _ <- topLevelComponentInstance.initialize()
        _ <- startTimersWhenReady()
      } yield ()

    } else {

      // Initialize with pre-rendered page
      for {
        _ <- frontend.resetEventCounters()
        _ <- reloadCssIfNecessary()
        _ <- renderInitialState()
        // Start handlers
        _ <- frontend.browserHistoryMessages
               .foreach(onHistory)
               .start
        _ <- frontend.domEventMessages
               .foreach(onEvent)
               .start
        _ <- if (delayedRender.toMillis > 0) {
               internalStateStream
                 .buffer(delayedRender)
                 .foreach { xs =>
                   onState(xs.flatMap(_._3)).recover { case error =>
                     reporter.error("Unable to process buffered state update", error)
                     ()
                   }
                 }
                 .start
             } else {
               internalStateStream.foreach { x =>
                 onState(x._3.toSeq).recover { case error =>
                   reporter.error("Unable to process state update", error)
                   ()
                 }
               }.start
             }
        // Init component
        _ <- topLevelComponentInstance.initialize()
        _ <- startTimersWhenReady()
      } yield ()
    }
    frontend.authorizeInteraction().flatMap(_ => start()).recoverF { case error =>
      abortRender()
      Effect[F].fail(error)
    }
  }
}
