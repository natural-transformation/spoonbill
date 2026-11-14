package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import spoonbill.{Context, Extension, Qsid}
import spoonbill.effect.{Effect, Queue}
import spoonbill.internal.TimerTestSupport.Reports
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.*
import spoonbill.snapshot.ViewSnapshotSession
import spoonbill.state.{DeviceId, SessionId, StateDeserializer, StateManager, StateSerializer, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

/**
 * One view of a real session service, with `String` states and messages. The
 * same fixture opens a legacy session and three kinds of guarded session: one
 * that retains its state, one that reconstructs it, and one with snapshots.
 */
private[services] object ExtensionContractFixture {

  given Effect[Future] = Effect.futureEffect

  enum Mode(val label: String) {
    case Legacy extends Mode("A legacy session")
    case Retained extends Mode("A guarded session")
    case Reconstructed extends Mode("A reconstructed guarded session")
    case Snapshots extends Mode("A guarded session with snapshots")
  }

  type Access = Context.BaseAccess[Future, String, String]

  def await[A](value: Future[A], limit: FiniteDuration = 5.seconds): A = Await.result(value, limit)

  /** What the extensions of a test saw, as `name.callback(argument)`. */
  final class Log {
    private val events                   = new ConcurrentLinkedQueue[String]()
    def add(text: String): Unit          = { events.add(text); () }
    def all: Vector[String]              = events.asScala.toVector
    def of(name: String): Vector[String] = all.filter(_.startsWith(s"$name."))
  }

  /**
   * An extension that logs every call. The hooks add behaviour to one callback.
   */
  def recorder(
    log: Log,
    name: String,
    onSetup: Access => Future[Unit] = _ => Future.unit,
    onState: String => Future[Unit] = _ => Future.unit,
    onMessage: String => Future[Unit] = _ => Future.unit,
    onDestroy: (Access, String) => Future[Unit] = (_, _) => Future.unit
  ): Extension[Future, String, String] =
    Extension[Future, String, String](name) { access =>
      log.add(s"$name.setup")
      onSetup(access).map { _ =>
        Extension.Handlers[Future, String, String](
          onState = state => { log.add(s"$name.onState($state)"); onState(state) },
          onMessage = message => { log.add(s"$name.onMessage($message)"); onMessage(message) },
          onDestroy = last => { log.add(s"$name.onDestroy($last)"); onDestroy(access, last) }
        )
      }
    }

  /**
   * @param allowance
   *   the number of states the guard still accepts
   */
  private def control(
    allowance: AtomicInteger,
    reconstructed: Boolean,
    snapshots: Boolean = false
  ): SessionAccessControl[Future, String] =
    new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): Future[SessionGuard[Future, String]] =
        Future.successful(new SessionGuard[Future, String] {
          val applicationStateInitialization: ApplicationStateInitialization[Future] =
            if (reconstructed) ApplicationStateInitialization.Reconstructed[Future](() => Future.unit)
            else ApplicationStateInitialization.Retained[Future]()
          // A snapshot session that restores nothing and accepts every commit.
          override val viewSnapshots: Option[ViewSnapshotSession[Future, String]] =
            Option.when(snapshots)(new ViewSnapshotSession[Future, String] {
              def ownerEpoch: ViewOwnershipEpoch            = ViewOwnershipEpoch.initial
              def initialize(fresh: String): Future[String] = Future.successful(fresh)
              def commit(candidate: String): Future[Unit]   = Future.unit
            })
          def authorize(state: String): Future[Unit] =
            if (allowance.getAndDecrement() <= 0) Future.failed(new SessionAccessDenied) else Future.unit
          // A state of its own, so a test can tell the admitted state from the one before it.
          def connected(state: String): Future[String] = Future.successful(state + Connected)
          def close(): Future[Unit]                    = Future.unit
        })
    }

  /** What a guarded session adds to a state when it accepts the attachment. */
  private val Connected = "+connected"

  /** The error of a state read that a test lets fail. */
  val unreadable = new IllegalStateException("The state cannot be read")

  /**
   * A storage whose managers can run out of state reads.
   *
   * @param readsLeft
   *   the number of reads that still succeed
   */
  private final class LimitedReads(underlying: StateStorage[Future, String], readsLeft: AtomicInteger)
      extends StateStorage[Future, String] {
    private def limited(manager: StateManager[Future]): StateManager[Future] = new StateManager[Future] {
      def snapshot: Future[StateManager.Snapshot] = manager.snapshot
      def read[T: StateDeserializer](nodeId: avocet.Id): Future[Option[T]] =
        if (readsLeft.getAndDecrement() <= 0) Future.failed(unreadable) else manager.read[T](nodeId)
      def delete(nodeId: avocet.Id): Future[Unit]                              = manager.delete(nodeId)
      def write[T: StateSerializer](nodeId: avocet.Id, value: T): Future[Unit] = manager.write(nodeId, value)
    }
    def exists(deviceId: DeviceId, sessionId: SessionId): Future[Boolean] = underlying.exists(deviceId, sessionId)
    def create(deviceId: DeviceId, sessionId: SessionId, state: String): Future[StateManager[Future]] =
      underlying.create(deviceId, sessionId, state).map(limited)
    def get(deviceId: DeviceId, sessionId: SessionId): Future[StateManager[Future]] =
      underlying.get(deviceId, sessionId).map(limited)
    def remove(deviceId: DeviceId, sessionId: SessionId): Unit = underlying.remove(deviceId, sessionId)
  }

  /**
   * The HTTP render has happened; `attach` plays the connection.
   *
   * @param rendered
   *   false leaves the state out, as after a restart of the server. The attach
   *   then rebuilds it from the request.
   * @param limitedReads
   *   the guarded session stores its state where `failReadsAfter` can make a
   *   read fail
   */
  final class View(
    mode: Mode,
    extensions: List[Extension[Future, String, String]],
    initial: String = "initial",
    rendered: Boolean = true,
    limitedReads: Boolean = false
  ) {
    val reports           = new Reports
    private val allowance = new AtomicInteger(Int.MaxValue)
    private val readsLeft = new AtomicInteger(Int.MaxValue)
    // A legacy session gets a storage the test can read. A guarded session
    // keeps its own, unless the test wants to break its reads.
    private val storage =
      if (mode == Mode.Legacy) StateStorage[Future, String]()
      else if (limitedReads) new LimitedReads(StateStorage.ephemeral[Future, String](), readsLeft)
      else null
    private val config = SpoonbillServiceConfig[Future, String, String](
      stateLoader = StateLoader.default(initial),
      stateStorage = storage,
      document = state => Html(body(div(state))),
      reporter = reports,
      extensions = extensions,
      sessionAccessControl = mode match {
        case Mode.Legacy        => None
        case Mode.Retained      => Some(control(allowance, reconstructed = false))
        case Mode.Reconstructed => Some(control(allowance, reconstructed = true))
        case Mode.Snapshots     => Some(control(allowance, reconstructed = false, snapshots = true))
      }
    )
    private val service = new SessionsService(config, new PageService(config))
    private val qsid    = Qsid("contract-device", UUID.randomUUID().toString)

    /**
     * The state the view is accepted with. A guarded session writes its
     * connected state.
     */
    val admitted: String = if (mode == Mode.Legacy) initial else initial + Connected

    /**
     * The guard accepts `checks` more states, and rejects every state after
     * them.
     */
    def revokeAfter(checks: Int): Unit = allowance.set(checks)

    /** From now on the guard rejects every state. */
    def revoke(): Unit = revokeAfter(0)

    /**
     * The storage answers `reads` more state reads, and fails every read after
     * them.
     */
    def failReadsAfter(reads: Int): Unit = readsLeft.set(reads)

    /**
     * The stored top-level state of a legacy session, also after its view was
     * retired.
     */
    def storedState: Option[String] =
      await(storage.get(qsid.deviceId, qsid.sessionId).flatMap(_.read[String](avocet.Id.TopLevel)))
    private val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    private val incoming = Queue[Future, String]()
    if (rendered) await(service.initAppState(qsid, request))

    /** The outcome of the attach. A failed setup fails it. */
    def tryAttach(limit: FiniteDuration = 5.seconds): Try[SessionStart[Future]] =
      Try(await(service.createAppIfNeeded(qsid, request, incoming.stream), limit))

    lazy val attached: SessionStart.Attached[Future] = tryAttach().get match {
      case value: SessionStart.Attached[Future] => value
      case other                                => throw new AssertionError(s"Expected an attached view but got $other")
    }

    def attach(): View = { attached; this }

    def registered: Boolean = await(service.getApp(qsid)).nonEmpty

    def access: Access = await(service.getApp(qsid)).get.topLevelComponentInstance.browserAccess

    def transition(state: String): Unit = await(access.transition(_ => state))
    def publish(message: String): Unit  = await(access.publish(message))
    def release(): Unit                 = await(attached.release())

    /** The browser goes away: the incoming stream ends. */
    def disconnect(): Future[Unit] = incoming.close()

    def errors: Vector[(String, Throwable)] = reports.errors
  }
}
