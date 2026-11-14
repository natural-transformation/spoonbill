package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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

  private def control(reconstructed: Boolean, snapshots: Boolean = false): SessionAccessControl[Future, String] =
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
          def authorize(state: String): Future[Unit]   = Future.unit
          def connected(state: String): Future[String] = Future.successful(state)
          def close(): Future[Unit]                    = Future.unit
        })
    }

  /** The HTTP render has happened; `attach` plays the connection. */
  final class View(mode: Mode, extensions: List[Extension[Future, String, String]], initial: String = "initial") {
    val reports = new Reports
    private val config = SpoonbillServiceConfig[Future, String, String](
      stateLoader = StateLoader.default(initial),
      document = state => Html(body(div(state))),
      reporter = reports,
      extensions = extensions,
      sessionAccessControl = mode match {
        case Mode.Legacy        => None
        case Mode.Retained      => Some(control(reconstructed = false))
        case Mode.Reconstructed => Some(control(reconstructed = true))
        case Mode.Snapshots     => Some(control(reconstructed = false, snapshots = true))
      }
    )
    private val service  = new SessionsService(config, new PageService(config))
    private val qsid     = Qsid("contract-device", UUID.randomUUID().toString)
    private val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    private val incoming = Queue[Future, String]()
    await(service.initAppState(qsid, request))

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
