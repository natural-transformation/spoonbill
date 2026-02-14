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
import java.util.concurrent.atomic.AtomicInteger
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
import avocet.events.calculateEventPropagation
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
  delayedRender: FiniteDuration
)(implicit ec: ExecutionContext) { application =>

  import reporter.Implicit

  private val devMode       = new DevMode.ForRenderContext(sessionId.toString)
  private val eventCounters = new TrieMap[(Id, String), Int]()
  private val stateQueue    = Queue[F, (Id, Any, Option[Effect.Promise[Unit]])]()
  private val stateHub      = Hub(stateQueue.stream)
  private val messagesQueue = Queue[F, M]()

  private val renderContext = {
    DiffRenderContext[Binding[F, S, M]](savedBuffer = devMode.loadRenderContext())
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
      }
    )
    componentInstance.setEventsSubscription(messagesQueue.offerUnsafe)
    componentInstance
  }

  /**
   * If dev mode is enabled save render context
   */
  private def saveRenderContextIfNecessary(): F[Unit] =
    if (devMode.isActive) Effect[F].delay(devMode.saveRenderContext(renderContext))
    else Effect[F].unit

  private def onState(maybeRenderCallback: Seq[Effect.Promise[Unit]]): F[Unit] =
    for {
      snapshot <- stateManager.snapshot
      // Set page url if router exists
      _ <- router.fromState
             .lift(snapshot(Id.TopLevel).getOrElse(initialState))
             .fold(Effect[F].unit)(uri => frontend.changePageUrl(rootPath ++ uri))
      _ <- Effect[F].delay {
             // Prepare render context
             renderContext.swap()
             // Avocet's diff algorithm mutates its internal IdBuilder. Reset it before each render
             // so DOM ids remain deterministic across renders.
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
           }
      // Infer and perform changes
      _ <- frontend.performDomChanges(renderContext.diff)
      _ <- saveRenderContextIfNecessary()
      // Make spoonbill ready to next render
      _ <- Effect[F].delay(topLevelComponentInstance.dropObsoleteMisc())
      _  = maybeRenderCallback.foreach(_(Right(())))
    } yield ()

  private def onHistory(pq: PathAndQuery): F[Unit] =
    stateManager
      .read[S](Id.TopLevel)
      .flatMap { maybeTopLevelState =>
        router.toState
          .lift(pq)
          .fold(Effect[F].delay(Option.empty[S]))(_(maybeTopLevelState.getOrElse(initialState)).map(Some(_)))
      }
      .flatMap {
        case Some(newState) =>
          stateManager
            .write(Id.TopLevel, newState)
            .after(stateQueue.enqueue(Id.TopLevel, newState, None))
        case None =>
          Effect[F].unit
      }

  private def onEvent(dem: DomEventMessage): F[Unit] = {
    def aux(effects: List[DomEventMessage => F[Boolean]]): F[Unit] =
      effects match {
        case Nil => Effect[F].unit
        case effect :: xs =>
          effect(dem).flatMap { stopPropagation =>
            if (stopPropagation) Effect[F].unit
            else aux(xs)
          }
      }
    val k = (dem.target, dem.eventType)
    Effect[F]
      .delay(eventCounters.getOrElse(k, 0))
      .flatMap { eventCounter =>
        if (eventCounter == dem.eventCounter) {
          val propagation = calculateEventPropagation(dem.target, dem.eventType)
          val allHandlers = topLevelComponentInstance.allEventHandlers
          val allEffects  = propagation.toList.flatMap(eventId => allHandlers.getOrElse(eventId, Vector.empty))
          if (allEffects.nonEmpty) {
            for {
              _             <- aux(allEffects)
              newEventConter = dem.eventCounter + 1
              _             <- Effect[F].delay(eventCounters.put(k, newEventConter))
              _             <- frontend.setEventCounter(dem.target, dem.eventType, newEventConter)
            } yield ()
          } else {
            Effect[F].unit
          }
        } else {
          Effect[F].unit
        }
      }
      .recover { case error => reporter.error(s"Unable to process event $dem", error) }
      .start
      .unit
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

  def destroy(): F[Unit] = for {
    _ <- stateQueue.close()
    _ <- messagesQueue.close()
    _ <- topLevelComponentInstance.destroy()
  } yield ()

  def initialize()(implicit ec: ExecutionContext): F[Unit] = {

    // If dev mode is enabled and active
    // CSS should be reloaded
    def reloadCssIfNecessary() =
      if (devMode.isActive) frontend.reloadCss()
      else Effect[F].unit

    // Render current state using 'performDiff'.
    def render(performDiff: (ChangesPerformer => Unit) => F[Unit]) =
      for {
        snapshot <- stateManager.snapshot
        _        <- Effect[F].delay {
                    topLevelComponentInstance.applyRenderContext((), renderContext, snapshot)
                    // Avocet's DiffRenderContext expects the freshly-rendered document to be finalized
                    // (flip the buffer + reset the id builder) before running `diff`.
                    renderContext.finalizeDocument()
                  }
        _        <- performDiff(renderContext.diff)
        _        <- saveRenderContextIfNecessary()
      } yield ()

    // Initial render for the "pre-rendered page" startup path.
    //
    // We intentionally DO NOT run `diff` here:
    // - There is nothing to apply to the browser (the page is assumed to match the server render).
    // - Diffing against an empty rhs buffer can put Avocet's internal IdBuilder into an invalid state,
    //   which breaks subsequent renders (see Issue14Spec after the Avocet upgrade).
    def renderInitialState(): F[Unit] =
      for {
        snapshot <- stateManager.snapshot
        _        <- Effect[F].delay {
                    topLevelComponentInstance.applyRenderContext((), renderContext, snapshot)
                    renderContext.finalizeDocument()
                  }
        _        <- saveRenderContextIfNecessary()
      } yield ()

    if (devMode.saved) {

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
        _ <- render(frontend.performDomChanges)
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
      } yield ()
    }
  }
}
