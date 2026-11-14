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
  recovery: PartialFunction[Throwable, F[Unit]],
  executionContext: ExecutionContext = ExecutionContext.global,
  ownership: Option[ComponentOwnership[F]] = None,
  timers: Option[TimerRuntime[F]] = None
) { self =>

  import ComponentInstance._
  import reporter.Implicit

  private val miscLock = new Object()
  private val owners   = ownership.getOrElse(new ComponentOwnership(stateManager, reporter, executionContext))
  private val owner    = owners.bind(nodeId, deleteOnRetire = nodeId != Id.TopLevel)
  // A traversal snapshot can predate a completed predecessor deletion, even
  // when bind no longer finds an outstanding handoff at this address.
  @volatile private var baselineReady = !owner.reused
  @volatile private var loading       = false

  private[spoonbill] def stateSnapshot: F[StateManager.Snapshot] = owners.snapshot

  private[spoonbill] def closeAdmission(): Unit = owners.closeAdmission()

  @volatile private var lastParameters: P = _
  private val markedDelays             = mutable.Set.empty[Id] // Set of the delays which are should survive
  private val markedComponentInstances = mutable.Set.empty[Id]
  private val delays                   = mutable.Map.empty[Id, DelayInstance[F, CS, E]]
  private val elements                 = mutable.Map.empty[ElementId, Id]
  private val events                   = mutable.Map.empty[EventId, Vector[Event[F, CS, E]]]
  private val nestedComponents         = mutable.Map.empty[Id, ComponentInstance[F, CS, E, _, _, _]]

  // Why we use '() => F[Unit]'? Because should
  // support scala.concurrent.Future which is has
  // strict semantic (runs immediately).
  private val pendingEffects = Queue[F, ComponentLaneEntry[F]]()

  private val timerTarget = new TimerRuntime.Target[F] {
    val owner: TimerMachine.Owner                    = TimerMachine.Owner(self.owner.incarnation)
    def active: Boolean                              = self.owner.isActive
    def offer(entry: ComponentLaneEntry[F]): Boolean = pendingEffects.offerUnsafe(entry)
    def deliver(select: () => Option[Any => Option[Any]]): F[Unit] =
      self.owner.awaitOpen.flatMap {
        case false => Effect[F].unit
        case true =>
          frontend.authorizeInteraction().flatMap(_ => readState).flatMap {
            case None        => Effect[F].unit // No generated state yet: settle this tick as a skip.
            case Some(state) =>
              // Authorization/read may resume inline on a scheduler thread. The lane
              // awaits this second dispatch, including its write and notification.
              Effect[F].fork(Effect[F].delayAsync {
                val admitted = frontend.selectLiveInteraction {
                  self.owner.admit().map(lease => lease -> select())
                }.flatten
                admitted match {
                  case None => Effect[F].unit
                  case Some((lease, selected)) =>
                    lease.run {
                      selected.flatMap(_(state)) match {
                        case None => Effect[F].unit
                        case Some(value) =>
                          val next = value.asInstanceOf[CS]
                          stateManager.write(nodeId, next).flatMap(_ => publishState(next, None))
                      }
                    }
                }
              })(executionContext)
          }
      }
  }

  @volatile private var eventSubscription = Option.empty[E => _]
  @volatile private var stateSubscription = Option.empty[CS => Unit]

  /**
   * Announces a written state: first to the state subscriber, then to the
   * render queue. Without a subscriber this is only the enqueue, so nested
   * components pay nothing for the subscription.
   */
  private def publishState(value: CS, rendered: Option[Effect.Promise[Unit]]): F[Unit] =
    stateSubscription match {
      case None => stateQueue.enqueue(nodeId, value, rendered)
      case Some(callback) =>
        Effect[F].delay(callback(value)).flatMap(_ => stateQueue.enqueue(nodeId, value, rendered))
    }

  private[spoonbill] case class BrowserAccess(dem: DomEventMessage, departure: Option[Long])
      extends BaseAccessDefault[F, CS, E] {

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
      frontend.authorizeInteraction().flatMap(_ => Effect[F].delay(eventSubscription.foreach(f => f(message))))

    def state: F[CS] =
      readState.map { maybeState =>
        maybeState
          // Fallback to initial state in case when
          // access.state invoked in component with
          // unmodified default state.
          // Required because state manager doesn't
          // hold the state until modification.
          .getOrElse(throw new RuntimeException(s"State for ${nodeId.mkString} is empty"))
      }

    def sessionId: F[Qsid] = Effect[F].delay(self.sessionId)

    def transition(f: Transition[CS]): F[Unit]                   = applyTransition(x => Effect[F].pure(f(x)))
    def transitionForce(f: Transition[CS]): F[Unit]              = applyTransitionForce(x => Effect[F].pure(f(x)))
    def transitionAsync(f: TransitionAsync[F, CS]): F[Unit]      = applyTransition(f)
    def transitionForceAsync(f: TransitionAsync[F, CS]): F[Unit] = applyTransitionForce(f)
    def afterRender(f: BaseAccess[F, CS, E] => F[Unit]): F[Unit] =
      transitionForce((state: CS) => state).flatMap(_ => f(this))

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

    override private[spoonbill] def submittedFields: F[Vector[(String, String)]] =
      dem.submission match {
        case Some(Right(submission)) => Effect[F].pure(submission.fields)
        case Some(Left(error))       => Effect[F].fail(error)
        case None                    => super.submittedFields
      }

    override private[spoonbill] def actionBinding: F[spoonbill.action.InvocationBinding] =
      frontend.newActionBinding(departure)

    override private[spoonbill] def authenticatedActionBinding: F[spoonbill.action.InvocationBinding] =
      frontend.newAuthenticatedActionBinding(departure)

    override private[spoonbill] def completeAuthentication(completionId: java.util.UUID): F[Unit] =
      frontend.completeAuthentication(completionId)
    override private[spoonbill] def completeReplacement(
      handoff: spoonbill.auth.CommittedReplacement,
      binding: spoonbill.action.InvocationBinding
    ): F[Unit] =
      frontend.completeReplacement(handoff, binding)
    override private[spoonbill] def retireReplacement(binding: spoonbill.action.InvocationBinding): F[Unit] =
      frontend.retireReplacement(binding)
    override private[spoonbill] def recoverReplacement(id: spoonbill.auth.CeremonyId): F[Unit] =
      frontend.recoverReplacement(id)

    override def presentSensitive(
      region: spoonbill.sensitive.RegionId,
      purpose: spoonbill.sensitive.Purpose,
      payload: spoonbill.sensitive.SensitivePayload,
      lifetime: scala.concurrent.duration.FiniteDuration
    ): F[spoonbill.sensitive.DisclosureOutcome] =
      frontend.presentSensitive(region, purpose, payload, lifetime)
    override def clearSensitive(region: spoonbill.sensitive.RegionId): F[Unit] = frontend.clearSensitive(region)

    def registerCallback(name: String)(f: String => F[Unit]): F[Unit] =
      frontend.registerCustomCallback(name)(f)
  }

  private[spoonbill] val browserAccess = BrowserAccess(DomEventMessage(0, Id.TopLevel, "init"), None)

  /**
   * Subscribes to component instance events. Callback will be invoked on call
   * of `access.publish()` in the component instance context.
   */
  def setEventsSubscription(callback: E => _): Unit =
    eventSubscription = Some(callback)

  /**
   * Subscribes to the state writes of this component instance. The callback
   * runs for every write, in the order the writes are announced, before the
   * write is queued for rendering. It must be quick and must not throw.
   */
  def setStateSubscription(callback: CS => Unit): Unit =
    stateSubscription = Some(callback)

  def applyRenderContext(
    parameters: P,
    rc: StatefulRenderContext[Binding[F, AS, M]],
    snapshot: StateManager.Snapshot
  ): Unit = miscLock.synchronized {
    // Reset all event handlers delays and elements
    prepare()
    val hasBaseline = baselineReady && owner.snapshotIsCurrent(snapshot)
    val node = if (!hasBaseline) {
      loadWhenReady(parameters)
      component.renderNoState(parameters)
    } else
      snapshot[CS](nodeId).map(Right(_)).getOrElse(component.initialState) match {
        case Right(value) =>
          if (lastParameters != parameters) {
            component.maybeUpdateState(parameters, value) match {
              case None => ()
              case Some(effect) =>
                effect.flatMap(newState => writeLoaded(newState, establishBaseline = false)).runAsyncForget
            }
          }
          component.render(parameters, value)
        case Left(generateState) =>
          Effect[F].delayAsync(generateState(parameters)).flatMap(value => writeLoaded(value)).runAsyncForget
          component.renderNoState(parameters)
      }

    if (hasBaseline) lastParameters = parameters
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
              val delayInstance =
                new DelayInstance(delay, scheduler, reporter, executionContext, timers.map(_.deadlines))
              delays.put(id, delayInstance)
              delayInstance.start(browserAccess, () => frontend.authorizeInteraction())
            }
          case timer: TimerBinding[F, CS, E] @unchecked =>
            timers.foreach(_.collect(timerTarget, timer.id, timer.nanos, timer.periodic, timer.restartOn, timer.update))
          case entry: ComponentEntry[F, CS, E, Any, Any, Any] @unchecked =>
            val id = rc.subsequentId
            nestedComponents.get(id) match {
              case Some(n: ComponentInstance[F, CS, E, Any, Any, Any]) if n.component.id == entry.component.id =>
                // Use nested component instance
                markedComponentInstances += id
                n.setEventsSubscription((e: Any) => entry.eventHandler(browserAccess, e).runAsyncForget)
                n.applyRenderContext(entry.parameters, proxy, snapshot)
              case _ =>
                nestedComponents.get(id).foreach(_.retire())
                val n = entry.createInstance(
                  id,
                  sessionId,
                  frontend,
                  eventRegistry,
                  stateManager,
                  stateQueue,
                  scheduler,
                  reporter,
                  recovery,
                  executionContext,
                  Some(owners),
                  timers
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

  private def writeLoaded(newState: CS, establishBaseline: Boolean = true): F[Unit] = owner.admit() match {
    case None => Effect[F].unit
    case Some(lease) =>
      lease.run {
        stateManager.write(nodeId, newState).flatMap { _ =>
          notifyLoaded(newState, establishBaseline)
        }
      }
  }

  private def notifyLoaded(state: CS, establishBaseline: Boolean = true): F[Unit] = Effect[F].delay {
    if (establishBaseline) owner.establishBaseline()
    baselineReady = true
    loading = false
  }.flatMap(_ => publishState(state, None))

  private def loadWhenReady(parameters: P): Unit = if (!loading) {
    loading = true
    Effect[F]
      .fork(Effect[F].delayAsync {
        owner.awaitOpen.flatMap {
          case false => Effect[F].unit
          case true =>
            owner.read(stateManager.read[CS](nodeId)).flatMap {
              case None              => Effect[F].unit
              case Some(Some(saved)) =>
                // Refresh the traversal without writing a previously read
                // value back over a concurrent update from this same owner.
                owner.admit().fold(Effect[F].unit)(_.run(notifyLoaded(saved)))
              case Some(None) =>
                component.initialState match {
                  case Right(value) => writeLoaded(value)
                  case Left(generate) =>
                    Effect[F].delayAsync(generate(parameters)).flatMap { value =>
                      lastParameters = parameters
                      writeLoaded(value)
                    }
                }
            }
        }
      })(executionContext)
      .recover { case error =>
        // A failed ownership gate cannot recover on another render. Keep this
        // loader settled until remount instead of reporting the same failure.
        if (owner.isOpen) loading = false
        reporter.error("Component initialization failed", error)
      }
      .runAsyncForget
  }

  // Missing generated state is distinct from retired/denied state access.
  private def readState: F[Option[CS]] = owner.read(stateManager.read[CS](nodeId)).flatMap {
    case Some(value) => Effect[F].pure(value.orElse(component.initialState.toOption))
    case None        => Effect[F].fail(new spoonbill.server.SessionAccessDenied)
  }

  private def prepareTransition(transition: TransitionAsync[F, CS]): F[Option[CS]] =
    readState.flatMap {
      case Some(state) => Effect[F].delayAsync(transition(state)).map(Some(_))
      case None        => Effect[F].fail(new Exception("Uninitialized component state"))
    }

  private def applyTransition(transition: TransitionAsync[F, CS]): F[Unit] = {
    val effect = () => ownedTurn(prepareTransition(transition))
    pendingEffects.enqueue(new ComponentLaneEntry(effect))
  }

  private def applyTransitionForce(transition: TransitionAsync[F, CS]): F[Unit] = Effect[F].promiseF[Unit] { cb =>
    val effect = () =>
      ownedTurn(
        prepareTransition(transition),
        Some(cb)
      ).recover { case error => cb(Left(error)) }
    Effect[F].delay {
      val entry = new ComponentLaneEntry(effect, () => cb(Left(new spoonbill.server.SessionAccessDenied)))
      if (!pendingEffects.offerUnsafe(entry)) entry.discard()
    }
  }

  /** A no-update turn completes without writing state or requesting a render. */
  private[internal] def ownedTurn(
    prepare: => F[Option[CS]],
    rendered: Option[Effect.Promise[Unit]] = None
  ): F[Unit] = {
    def discard(): F[Unit] = Effect[F].delay(rendered.foreach(_(Left(new spoonbill.server.SessionAccessDenied))))
    owner.awaitOpen.flatMap {
      case false                 => discard()
      case true if !owner.isOpen => discard()
      case true =>
        Effect[F].delayAsync(prepare).flatMap {
          case None => Effect[F].delay(rendered.foreach(_(Right(()))))
          case Some(value) =>
            owner.admit() match {
              // User computation stays in the lane but owns no drain reservation.
              // Only a commit admitted while this captured owner is live can write.
              case None => discard()
              case Some(lease) =>
                lease.run {
                  stateManager.write(nodeId, value).flatMap(_ => publishState(value, rendered))
                }
            }
        }
    }
  }

  private[spoonbill] def updateFromHistory(update: CS => F[Option[CS]]): F[Unit] =
    ownedTurn {
      browserAccess.state.flatMap(update)
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

  // ApplicationInstance.onEvent observes current authority before each handler
  // (the first one through the event's admission check).
  private def mapHandlers(handlers: Vector[Event[F, CS, E]]): EventHandlers =
    handlers.map { handler => (dem: DomEventMessage) =>
      frontend.browserAdmission(dem.sensitiveDeparture).invoke[F, Boolean](true) {
        // Superseded events stop propagation without invoking any application
        // handler. Synthetic lifecycle access has no browser generation.
        handler.effect(BrowserAccess(dem, Some(dem.sensitiveDeparture))).as(handler.stopPropagation)
      }
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
        nested.retire()
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
  private[spoonbill] def retire(): Unit = {
    if (nodeId == Id.TopLevel) owners.closeAdmission()
    timers.foreach(_.close(timerTarget.owner))
    owner.retire()
    miscLock.synchronized {
      delays.values.foreach { delay =>
        try delay.cancel()
        catch { case NonFatal(error) => reporter.error("Delay cancellation failed", error) }
      }
      delays.clear()
      pendingEffects.unsafeClose()
      pendingEffects.dequeueAll().map(_.foreach(_.discard())).runAsyncForget
      nestedComponents.values.foreach(_.retire())
    }
  }

  def destroy(): F[Unit] = Effect[F].delay(retire()).flatMap { _ =>
    val ownDrain = if (nodeId == Id.TopLevel) owners.closeAndDrain() else owner.awaitRetired
    val cleanup  = ownDrain :: miscLock.synchronized(nestedComponents.values.toList).map(_.destroy())
    cleanup.map(_.map[Option[Throwable]](_ => None).recover { case error => Some(error) }).sequence.flatMap { errors =>
      errors.flatten.headOption.fold(Effect[F].unit)(Effect[F].fail[Unit])
    }
  }

  private def applyPendingEffect(entry: ComponentLaneEntry[F]): F[Unit] =
    entry.run().recover { case e => reporter.error("Transition failed", e) }

  protected def unsafeInitialize(): Unit =
    pendingEffects.stream
      .foreach(applyPendingEffect)
      .runAsyncForget

  // Execute effects sequentially
  def initialize()(implicit ec: ExecutionContext): F[Effect.Fiber[F, Unit]] =
    Effect[F].start(pendingEffects.stream.foreach(applyPendingEffect))
}

private[spoonbill] object ComponentInstance {

  import Context.Access
  import Context.Delay

  final class DelayInstance[F[_]: Effect, S, M](
    delay: Delay[F, S, M],
    scheduler: Scheduler[F],
    reporter: Reporter,
    executionContext: ExecutionContext = ExecutionContext.global,
    deadlines: Option[Frontend.RpcDeadlineScheduler] = None
  ) {

    @volatile private var handler  = Option.empty[() => Unit]
    @volatile private var finished = false

    def isFinished: Boolean = finished

    def cancel(): Unit =
      handler.foreach(_())

    private def report(error: Throwable): Unit = error match {
      case _: spoonbill.server.SessionAccessDenied => ()
      case Frontend.ObsoleteBrowserEvent           => ()
      case _                                       => reporter.error("Delay failed", error)
    }

    def start(access: Access[F, S, M], authorize: () => F[Unit]): Unit = {
      val driver = deadlines.getOrElse(new Frontend.RpcDeadlineScheduler {
        def schedule(duration: scala.concurrent.duration.FiniteDuration)(wake: () => Unit): () => Unit = {
          val job = scheduler.unsafeScheduleOnce(duration)(Effect[F].delay(wake()))
          () => job.unsafeCancel()
        }
      })
      try
        handler = Some(driver.schedule(delay.duration) { () =>
          finished = true
          try
            Effect[F]
              .fork(Effect[F].delayAsync {
                authorize().flatMap { _ =>
                  Effect[F].fork(Effect[F].delayAsync(delay.effect(access)))(executionContext)
                }
              })(executionContext)
              .runAsync {
                case Left(error) => report(error)
                case Right(_)    => ()
              }
          catch { case NonFatal(error) => report(error) }
        })
      catch { case NonFatal(error) => finished = true; report(error) }
    }
  }
}
