package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Queue}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Extension cases that need a real session service and an effect that can be
 * cancelled. `Future` cannot be cancelled, so only the lazy-effect modules run
 * them.
 */
object ExtensionSessionChecks {

  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 10.seconds)

  /**
   * A guarded attach is cancelled while a `setup` runs. No caller is left to
   * clean up after it, so the session does: it closes the view, and it destroys
   * the handlers that the `setup` returns afterwards.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledGuardedAttach[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F            = Effect[F]
    val setupStarted = new CountDownLatch(1)
    val destroyed    = new CountDownLatch(1)
    val finishSetup  = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    // Whether the access still worked inside onDestroy.
    val accessWorked = new AtomicReference(Option.empty[Boolean])
    val extension = Extension[F, String, String]("slow") { access =>
      F.flatMap(F.delay(setupStarted.countDown())) { _ =>
        F.map(finishSetup.get(())) { _ =>
          Extension.Handlers[F, String, String](
            onDestroy = _ =>
              F.map(F.recover(F.map(access.state)(_ => true)) { case _ => false }) { worked =>
                accessWorked.set(Some(worked))
                destroyed.countDown()
              }
          )
        }
      }
    }
    val control = new SessionAccessControl[F, String] {
      def authorizeHttp(request: Request.Head, state: String): F[Unit] = F.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): F[SessionGuard[F, String]] =
        F.pure(new SessionGuard[F, String] {
          val applicationStateInitialization: ApplicationStateInitialization[F] =
            ApplicationStateInitialization.Retained[F]()
          def authorize(state: String): F[Unit]   = F.unit
          def connected(state: String): F[String] = F.pure(state)
          def close(): F[Unit]                    = F.unit
        })
    }
    val config = SpoonbillServiceConfig[F, String, String](
      stateLoader = StateLoader.default("initial"),
      document = state => Html(body(div(state))),
      extensions = List(extension),
      sessionAccessControl = Some(control)
    )
    val service  = new SessionsService(config, new PageService(config))
    val qsid     = Qsid("cancelled-attach", UUID.randomUUID().toString)
    val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[F, String]()
    run(service.initAppState(qsid, request))

    val cancel = start(F.map(service.createAppIfNeeded(qsid, request, incoming.stream))(_ => ()))
    assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup never started")
    cancel()
    run(incoming.close())
    // The setup returns its handlers after the attach has gone away.
    run(finishSetup.put((), ()))
    assert(destroyed.await(10, TimeUnit.SECONDS), "The cancelled attach kept the handlers of its setup")
    assert(accessWorked.get().contains(false), "The view was still open inside onDestroy")
    assert(run(service.getApp(qsid)).isEmpty, "The cancelled attach left its view registered")
    // The attachment of the view is free again: the next attach is not refused.
    run(service.createAppIfNeeded(qsid, request, Queue[F, String]().stream)) match {
      case SessionStart.Attached(_, release) => run(release())
      case SessionStart.Reload()             => ()
    }
  }
}
