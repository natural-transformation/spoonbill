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
import spoonbill.Context.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler, Stream}
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend.DomEventMessage
import spoonbill.state.StateDeserializer
import spoonbill.state.StateManager
import spoonbill.state.StateSerializer
import spoonbill.util.JsCode
import spoonbill.util.Lens
import spoonbill.web.FormData
import avocet.Id
import avocet.StatefulRenderContext
import avocet.events.EventId
import avocet.events.EventPhase
import scala.collection.AbstractMapView
import scala.collection.MapView
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
 * Component state holder and effects performer
 *
 * Performing cycle:
 *
 *   1. prepare() 2. Optionally setState() 3. applyRenderContext() 4.
 *      dropObsoleteMisc()
 *
 * @tparam AS
 *   Type of top level state (application state)
 * @tparam CS
 *   Type of component state
 */
final class ComponentInstance[
  F[_]: Effect,
  AS: StateSerializer: StateDeserializer,
  M,
  CS: StateSerializer: StateDeserializer,
  P,
  E
](
  nodeId: Id,
  sessionId: Qsid,
  frontend: Frontend[F],
  eventRegistry: EventRegistry[F],
  stateManager: StateManager[F],
  val component: Component[F, CS, P, E],
  stateQueue: Queue[F, (Id, Any, Option[Effect.Promise[Unit]])],
  createMiscProxy: (
    StatefulRenderContext[Binding[F, AS, M]],
    (StatefulRenderContext[Binding[F, CS, E]], Binding[F, CS, E]) => Unit
  ) => StatefulRenderContext[Binding[F, CS, E]],
  scheduler: Scheduler[F],
  reporter: Reporter,
  recovery: PartialFunction[Throwable, F[Unit]]
) { self =>

  import ComponentInstance._
  import reporter.Implicit

  private val miscLock = new Object()

  private var lastParameters: P        = _
  private val markedDelays             = mutable.Set.empty[Id] // Set of the delays which are should survive
  private val markedComponentInstances = mutable.Set.empty[Id]
  private val delays                   = mutable.Map.empty[Id, DelayInstance[F, CS, E]]
  private val elements                 = mutable.Map.empty[ElementId, Id]
  private val events                   = mutable.Map.empty[EventId, Vector[Event[F, CS, E]]]
  private val nestedComponents         = mutable.Map.empty[Id, ComponentInstance[F, CS, E, _, _, _]]

  // Why we use '() => F[Unit]'? Because should
  // support scala.concurrent.Future which is has
  // strict semantic (runs immediately).
  private val pendingEffects = Queue[F, () => F[Unit]]()

  @volatile private var eventSubscription = Option.empty[E => _]

  private[spoonbill] case class BrowserAccess(dem: DomEventMessage) extends BaseAccessDefault[F, CS, E] {

    private def getId(elementId: ElementId): F[Id] = Effect[F].delay {
      unsafeGetId(elementId)
    }

    private def unsafeGetId(elementId: ElementId): Id =
      // miscLock synchronization required
      // because prop handler methods can be
      // invoked during render.
      miscLock.synchronized {
        elements.get(elementId) match {
          case None =>
            elementId.name match {
              case Some(name) => throw new Exception(s"No element matched for accessor $name")
              case None       => throw new Exception(s"No element matched for accessor")
            }
          case Some(id) => id
        }
      }

    def property(elementId: ElementId): PropertyHandler[F] = {
      val idF = getId(elementId)
      new PropertyHandler[F] {
        def get(propName: String): F[String] = idF.flatMap { id =>
          frontend.extractProperty(id, propName)
        }

        def set(propName: String, value: Any): F[Unit] = idF.flatMap { id =>
          // XmlNs argument is empty cause it will be ignored
          frontend.setProperty(id, propName, value)
        }
      }
    }

    def focus(element: ElementId): F[Unit] =
      getId(element).flatMap { id =>
        frontend.focus(id)
      }

    def publish(message: E): F[Unit] =
      Effect[F].delay(eventSubscription.foreach(f => f(message)))

    def state: F[CS] = {
      val state = stateManager.read[CS](nodeId)

      state.map { maybeState =>
        maybeState
          // Fallback to initial state in case when
          // access.state invoked in component with
          // unmodified default state.
          // Required because state manager doesn't
          // hold the state until modification.
          .orElse(component.initialState.toOption)
          .getOrElse(throw new RuntimeException(s"State for ${nodeId.mkString} is empty"))
      }
    }

    def sessionId: F[Qsid] = Effect[F].delay(self.sessionId)

    def transition(f: Transition[CS]): F[Unit]                   = applyTransition(x => Effect[F].pure(f(x)))
    def transitionForce(f: Transition[CS]): F[Unit]              = applyTransitionForce(x => Effect[F].pure(f(x)))
    def transitionAsync(f: TransitionAsync[F, CS]): F[Unit]      = applyTransition(f)
    def transitionForceAsync(f: TransitionAsync[F, CS]): F[Unit] = applyTransitionForce(f)

    def downloadFormData(element: ElementId): F[FormData] =
      for {
        id       <- getId(element)
        formData <- frontend.uploadForm(id)
      } yield formData

    def downloadFiles(id: ElementId): F[List[(FileHandler, Bytes)]] =
      downloadFilesAsStream(id).flatMap { streams =>
        Effect[F].sequence {
          streams.map { case (handler, data) =>
            data
              .fold(Bytes.empty)(_ ++ _)
              .map(b => (handler, b))
          }
        }
      }

    def downloadFilesAsStream(id: ElementId): F[List[(FileHandler, Stream[F, Bytes])]] =
      listFiles(id).flatMap { handlers =>
        Effect[F].sequence {
          handlers.map { handler =>
            downloadFileAsStream(handler).map(f => (handler, f))
          }
        }
      }

    /**
     * Get selected file as a stream from input
     */
    def downloadFileAsStream(handler: FileHandler): F[Stream[F, Bytes]] =
      for {
        id      <- getId(handler.elementId)
        streams <- frontend.uploadFile(id, handler)
      } yield streams

    def listFiles(elementId: ElementId): F[List[FileHandler]] =
      for {
        id    <- getId(elementId)
        files <- frontend.listFiles(id)
      } yield {
        files.map { case (fileName, size) =>
          FileHandler(fileName, size)(elementId)
        }
      }

    def uploadFile(name: String, stream: Stream[F, Bytes], size: Option[Long], mimeType: String): F[Unit] =
      frontend.downloadFile(name, stream, size, mimeType)

    def resetForm(elementId: ElementId): F[Unit] =
      getId(elementId).flatMap { id =>
        frontend.resetForm(id)
      }

    def evalJs(code: JsCode): F[String] =
      frontend.evalJs(code.mkString(unsafeGetId))

    def eventData: F[String] = frontend.extractEventData(dem)

    def registerCallback(name: String)(f: String => F[Unit]): F[Unit] =
      frontend.registerCustomCallback(name)(f)
  }

  private[spoonbill] val browserAccess = BrowserAccess(DomEventMessage(0, Id.TopLevel, "init"))

  /**
   * Subscribes to component instance events. Callback will be invoked on call
   * of `access.publish()` in the component instance context.
   */
  def setEventsSubscription(callback: E => _): Unit =
    eventSubscription = Some(callback)

  def applyRenderContext(
    parameters: P,
    rc: StatefulRenderContext[Binding[F, AS, M]],
    snapshot: StateManager.Snapshot
  ): Unit = miscLock.synchronized {
    // Reset all event handlers delays and elements
    prepare()
    val state = snapshot[CS](nodeId).map(Right(_)).getOrElse(component.initialState)
    val node = state match {
      case Right(value) =>
        if (lastParameters != parameters) {
          component.maybeUpdateState(parameters, value) match {
            case None => ()
            case Some(effect) =>
              effect.flatMap { newState =>
                stateManager.write(nodeId, newState) *>
                  stateQueue.enqueue(nodeId, newState, None)
              }.runAsyncForget // TODO Should be cancelable
          }
        }
        component.render(parameters, value)
      case Left(generateState) =>
        generateState(parameters).flatMap { newState =>
          stateManager.write(nodeId, newState) *>
            stateQueue.enqueue(nodeId, newState, None)
        }.runAsyncForget // TODO Should be cancelable
        component.renderNoState(parameters)
    }

    lastParameters = parameters
    val proxy = createMiscProxy(
      rc,
      (proxy, misc) =>
        misc match {
          case event: Event[F, CS, E] @unchecked =>
            val id  = rc.currentContainerId
            val eid = EventId(id, event.`type`, event.phase)
            val es  = events.getOrElseUpdate(eid, Vector.empty)
            events.put(eid, es :+ event)
            eventRegistry.registerEventType(event.`type`)
          case element: ElementId =>
            val id = rc.currentContainerId
            elements.put(element, id)
            ()
          case delay: Delay[F, CS, E] @unchecked =>
            val id = rc.currentContainerId
            markedDelays += id
            if (!delays.contains(id)) {
              val delayInstance = new DelayInstance(delay, scheduler, reporter)
              delays.put(id, delayInstance)
              delayInstance.start(browserAccess)
            }
          case entry: ComponentEntry[F, CS, E, Any, Any, Any] @unchecked =>
            val id = rc.subsequentId
            nestedComponents.get(id) match {
              case Some(n: ComponentInstance[F, CS, E, Any, Any, Any]) if n.component.id == entry.component.id =>
                // Use nested component instance
                markedComponentInstances += id
                n.setEventsSubscription((e: Any) => entry.eventHandler(browserAccess, e).runAsyncForget)
                n.applyRenderContext(entry.parameters, proxy, snapshot)
              case _ =>
                val n = entry.createInstance(
                  id,
                  sessionId,
                  frontend,
                  eventRegistry,
                  stateManager,
                  stateQueue,
                  scheduler,
                  reporter,
                  recovery
                )
                markedComponentInstances += id
                nestedComponents.put(id, n)
                n.unsafeInitialize()
                n.setEventsSubscription((e: Any) => entry.eventHandler(browserAccess, e).runAsyncForget)
                n.applyRenderContext(entry.parameters, proxy, snapshot)
            }
        }
    )
    node(proxy)

    // Publish a complete snapshot of handlers after rendering is finished.
    // (Events arriving during rendering will still use the previous snapshot.)
    refreshHandlerSnapshot()
  }

  private def applyTransitionEffect(transition: TransitionAsync[F, CS]): F[CS] =
    for {
      maybeState <- stateManager.read[CS](nodeId)
      state <- maybeState
                 .orElse(component.initialState.toOption)
                 .fold(Effect[F].fail(new Exception("Uninitialized component state")): F[CS])(Effect[F].pure(_))
      newState <- transition(state)
      _        <- stateManager.write(nodeId, newState)
    } yield newState

  private def applyTransition(transition: TransitionAsync[F, CS]): F[Unit] = {
    val effect = () =>
      for {
        newState <- applyTransitionEffect(transition)
        _        <- stateQueue.enqueue(nodeId, newState, None)
      } yield ()
    pendingEffects.enqueue(effect)
  }

  private def applyTransitionForce(transition: TransitionAsync[F, CS]): F[Unit] = Effect[F].promiseF[Unit] { cb =>
    val effect = () =>
      for {
        newState <- applyTransitionEffect(transition).recoverF { case e =>
                      cb(Left(e))
                      Effect[F].fail[CS](e)
                    }
        _ <- stateQueue.enqueue(nodeId, newState, Some(cb))
      } yield ()
    pendingEffects.enqueue(effect)
  }

  type EventHandlers    = Vector[DomEventMessage => F[Boolean]]
  type AllEventHandlers = MapView[EventId, EventHandlers]

  // NOTE:
  // `applyRenderContext()` clears and rebuilds `events` under `miscLock`.
  // Dom events can arrive concurrently while rendering is in progress.
  //
  // If we read `events` without synchronization, we can observe an empty / partially
  // rebuilt registry and incorrectly drop events (this started surfacing after the
  // Avocet upgrade, because rendering + diff got a bit stricter about buffer lifecycles).
  //
  // We fix this by snapshotting the handler registry at the end of rendering (under `miscLock`)
  // and serving events from the last complete snapshot.
  //
  // This avoids observing a partially rebuilt registry *and* avoids allocating a new snapshot per event.
  private type HandlerSnapshot =
    (Map[EventId, Vector[Event[F, CS, E]]], List[ComponentInstance[F, CS, E, _, _, _]])

  @volatile private var handlerSnapshot: HandlerSnapshot = (Map.empty, Nil)

  private def refreshHandlerSnapshot(): Unit =
    // Must be invoked under `miscLock` so `events` and `nestedComponents` are consistent.
    handlerSnapshot = (events.toMap, nestedComponents.values.toList)

  private def eventHandlersSnapshot: HandlerSnapshot =
    handlerSnapshot

  private def mapHandlers(handlers: Vector[Event[F, CS, E]]): EventHandlers =
    handlers.map { handler => (dem: DomEventMessage) =>
      handler
        .effect(BrowserAccess(dem))
        .as(handler.stopPropagation)
    }

  def allEventHandlers: AllEventHandlers = {
    val (eventsSnap, nestedSnap) = eventHandlersSnapshot
    val local: AllEventHandlers  = eventsSnap.view.mapValues(mapHandlers)
    local +++ nestedSnap
      .map(_.allEventHandlers)
      .foldLeft(MapView.empty: AllEventHandlers)(_ +++ _)
  }

  def eventHandlersFor(eventId: EventId): EventHandlers = {
    val (eventsSnap, nestedSnap) = eventHandlersSnapshot
    eventsSnap
      .get(eventId)
      .fold(Vector.empty[DomEventMessage => F[Boolean]])(mapHandlers) ++ nestedSnap
      .flatMap(_.eventHandlersFor(eventId))
  }

  /**
   * Remove all delays and nested component instances which were not marked
   * during applying render context.
   */
  def dropObsoleteMisc(): Unit = miscLock.synchronized {
    delays foreach { case (id, delay) =>
      if (!markedDelays.contains(id)) {
        delays.remove(id)
        delay.cancel()
      }
    }
    nestedComponents foreach { case (id, nested) =>
      if (!markedComponentInstances.contains(id)) {
        nestedComponents.remove(id)
        nested
          .destroy()
          .after(stateManager.delete(id))
          .runAsyncForget
      } else nested.dropObsoleteMisc()
    }

    // Keep the published handler snapshot consistent with the current live component tree.
    refreshHandlerSnapshot()
  }

  /**
   * Prepares component instance to applying render context. Removes all
   * temporary and obsolete misc. All nested components also will be prepared.
   */
  private def prepare(): Unit = {
    markedComponentInstances.clear()
    markedDelays.clear()
    elements.clear()
    events.clear()
    // Remove only finished delays
    delays foreach { case (id, delay) =>
      if (delay.isFinished)
        delays.remove(id)
    }
  }

  /**
   * Close 'pendingEffects' in this component and all nested components.
   *
   * MUST be invoked after closing connection.
   */
  def destroy(): F[Unit] =
    for {
      _ <- pendingEffects.close()
      _ <- nestedComponents.values.toList
             .map(_.destroy())
             .sequence
             .unit
    } yield ()

  private def applyPendingEffect(f: () => F[Unit]): F[Unit] =
    f().recover { case e => reporter.error("Transition failed", e) }

  protected def unsafeInitialize(): Unit =
    pendingEffects.stream
      .foreach(applyPendingEffect)
      .runAsyncForget

  // Execute effects sequentially
  def initialize()(implicit ec: ExecutionContext): F[Effect.Fiber[F, Unit]] =
    Effect[F].start(pendingEffects.stream.foreach(applyPendingEffect))
}

private object ComponentInstance {

  import Context.Access
  import Context.Delay

  final class DelayInstance[F[_]: Effect, S, M](delay: Delay[F, S, M], scheduler: Scheduler[F], reporter: Reporter) {

    @volatile private var handler  = Option.empty[Scheduler.JobHandler[F, _]]
    @volatile private var finished = false

    def isFinished: Boolean = finished

    def cancel(): Unit =
      handler.foreach(_.unsafeCancel())

    def start(access: Access[F, S, M]): Unit =
      handler = Some {
        scheduler.unsafeScheduleOnce(delay.duration) {
          finished = true
          delay.effect(access)
        }
      }
  }
}
