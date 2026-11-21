package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Try}
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Extension cases that need a real session service and an effect that can be
 * cancelled. `Future` cannot be cancelled, so only the lazy-effect modules run
 * them.
 *
 * A session may put a cancellation off until its cleanup is done. So where a
 * check holds the cleanup back, it asks for the cancellation from another
 * thread and lets the cleanup go on afterwards.
 */
object ExtensionSessionChecks {

  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 10.seconds)

  /**
   * Some checks let a `setup` fail on purpose. The default reporter would print
   * that failure with its stack trace into the test output.
   */
  private val quiet: Reporter = new Reporter {
    def error(message: String, error: Throwable): Unit                = ()
    def error(message: String): Unit                                  = ()
    def warning(message: String, error: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }

  /** One view of a guarded session whose HTTP render has happened. */
  private final class Guarded[F[_]: Effect](
    extensions: List[Extension[F, String, String]],
    closeGuard: () => F[Unit]
  )(implicit ec: ExecutionContext) {
    private val F = Effect[F]
    private val control = new SessionAccessControl[F, String] {
      def authorizeHttp(request: Request.Head, state: String): F[Unit] = F.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): F[SessionGuard[F, String]] =
        F.pure(new SessionGuard[F, String] {
          val applicationStateInitialization: ApplicationStateInitialization[F] =
            ApplicationStateInitialization.Retained[F]()
          def authorize(state: String): F[Unit]   = F.unit
          def connected(state: String): F[String] = F.pure(state)
          def close(): F[Unit]                    = closeGuard()
        })
    }
    private val config = SpoonbillServiceConfig[F, String, String](
      stateLoader = StateLoader.default("initial"),
      document = state => Html(body(div(state))),
      reporter = quiet,
      extensions = extensions,
      sessionAccessControl = Some(control)
    )
    private val service = new SessionsService(config, new PageService(config))
    private val qsid    = Qsid("cancelled-attach", UUID.randomUUID().toString)
    private val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming        = Queue[F, String]()
    run(service.initAppState(qsid, request))

    def attach(input: Queue[F, String] = incoming): F[SessionStart[F]] =
      service.createAppIfNeeded(qsid, request, input.stream)

    /**
     * Whether the service holds a running view. Only an attach that completed
     * registers one.
     */
    def registered: Boolean = run(service.getApp(qsid)).nonEmpty

    /**
     * Attaches once more and releases that view. It proves that the cleanup
     * released the attachment: an attach of a view that is still taken is
     * refused.
     */
    def attachAgain(): Unit = run(attach(Queue[F, String]())) match {
      case SessionStart.Attached(_, release) => run(release())
      case SessionStart.Reload()             => ()
      // Only an attach that names an owner can be superseded. This one names none.
      case SessionStart.Superseded() => throw new AssertionError("An attach without an owner was superseded")
    }
  }

  /**
   * A gate that a guard waits for in `close`, and the signal that it got there.
   */
  private final class HeldClose[F[_]: Effect] {
    private val F        = Effect[F]
    private val gate     = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val started          = new CountDownLatch(1)
    def close(): F[Unit] = F.flatMap(F.delay(started.countDown()))(_ => gate.get(()))

    /** Lets the guard close. Safe to call more than once. */
    def open(): Unit = { Try(run(gate.putEither((), Right(()), silent = true))); () }
  }

  /**
   * Asks for the cancellation from another thread. A session that gives in to
   * it at once gets a second to do so; one that puts it off is still waiting
   * then.
   */
  private def request(cancel: () => Unit)(implicit ec: ExecutionContext): Future[Unit] = {
    val cancelling = Future(cancel())
    Try(Await.ready(cancelling, 1.second))
    cancelling
  }

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
    val view = new Guarded[F](List(extension), () => F.unit)

    val cancel = start(F.map(view.attach())(_ => ()))
    try {
      assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup never started")
      cancel()
      run(view.incoming.close())
      // The setup returns its handlers after the attach has gone away.
      run(finishSetup.put((), ()))
      assert(destroyed.await(10, TimeUnit.SECONDS), "The cancelled attach kept the handlers of its setup")
      assert(accessWorked.get().contains(false), "The view was still open inside onDestroy")
      view.attachAgain()
    } finally { Try(run(finishSetup.putEither((), Right(()), silent = true))); () }
  }

  /**
   * A guarded attach is cancelled while its rollback is under way: a `setup`
   * has failed, the cleanup has started, and it waits for the guard to close.
   * The cleanup still runs to its end, so the extension that was set up is
   * destroyed and the view is released.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledGuardedRollback[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F         = Effect[F]
    val failure   = new IllegalStateException("synthetic")
    val destroyed = new CountDownLatch(1)
    val guard     = new HeldClose[F]
    val first = Extension.pure[F, String, String]("first") { _ =>
      Extension.Handlers[F, String, String](onDestroy = _ => F.delay(destroyed.countDown()))
    }
    val failing = Extension[F, String, String]("failing")(_ => F.fail(failure))
    val view    = new Guarded[F](List(first, failing), () => guard.close())

    // This attach fails on purpose. The check looks at what it leaves behind.
    val cancel = start(F.recover(F.map(view.attach())(_ => ())) { case _ => () })
    try {
      assert(guard.started.await(10, TimeUnit.SECONDS), "The rollback never reached the guard")
      val cancelling = request(cancel)
      guard.open()
      Await.result(cancelling, 10.seconds)
      assert(
        destroyed.await(10, TimeUnit.SECONDS),
        "The cancelled rollback did not destroy the extension that was set up"
      )
      // The attachment of the view is free again. The next attach fails for its
      // own reason, the failing setup, and not because the view is still taken.
      assert(Try(run(view.attach(Queue[F, String]()))) == Failure(failure))
    } finally guard.open()
  }

  /**
   * The same cancellation, where the rollback starts because a `setup` ran out
   * of time, and that `setup` returns its handlers while the rollback still
   * waits for the guard. This takes the ten real seconds of the bound.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledTimeoutRollback[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F            = Effect[F]
    val setupStarted = new CountDownLatch(1)
    val destroyed    = new CountDownLatch(1)
    val finishSetup  = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val guard        = new HeldClose[F]
    val slow = Extension[F, String, String]("slow") { _ =>
      F.flatMap(F.delay(setupStarted.countDown())) { _ =>
        F.map(finishSetup.get(())) { _ =>
          Extension.Handlers[F, String, String](onDestroy = _ => F.delay(destroyed.countDown()))
        }
      }
    }
    val view = new Guarded[F](List(slow), () => guard.close())

    // This attach fails on purpose, when its setup runs out of time.
    val cancel = start(F.recover(F.map(view.attach())(_ => ())) { case _ => () })
    try {
      assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup never started")
      // The bound of the setup expires, and the rollback reaches the guard.
      assert(guard.started.await(20, TimeUnit.SECONDS), "The setup did not run out of time")
      val cancelling = request(cancel)
      // The setup returns late, while the rollback still waits for the guard.
      run(finishSetup.put((), ()))
      guard.open()
      Await.result(cancelling, 10.seconds)
      assert(destroyed.await(10, TimeUnit.SECONDS), "The cancelled rollback kept the late setup result")
      // The setup returns at once now, so this attach succeeds if the view is free.
      view.attachAgain()
    } finally {
      // Leave nothing pending when an assertion fails.
      Try(run(finishSetup.putEither((), Right(()), silent = true)))
      guard.open()
    }
  }

  /**
   * An unguarded attach is cancelled while a `setup` of it is about to fail.
   * The attach still rolls its view back, and it takes its failure out of the
   * session's registry in the same step. So the failure is not the answer for
   * the next attach, which builds a view of its own.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledLegacyRollback[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F            = Effect[F]
    val failure      = new IllegalStateException("synthetic")
    val setups       = new AtomicInteger()
    val setupStarted = new CountDownLatch(1)
    val failSetup    = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    // The first setup waits, and then fails. Every later one returns at once.
    val once = Extension[F, String, String]("once") { _ =>
      if (setups.incrementAndGet() > 1) F.pure(Extension.Handlers[F, String, String]())
      else
        F.flatMap(F.delay(setupStarted.countDown())) { _ =>
          F.flatMap(failSetup.get(()))(_ => F.fail[Extension.Handlers[F, String, String]](failure))
        }
    }
    val config = SpoonbillServiceConfig[F, String, String](
      stateLoader = StateLoader.default("initial"),
      document = state => Html(body(div(state))),
      reporter = quiet,
      extensions = List(once)
    )
    val service = new SessionsService(config, new PageService(config))
    val qsid    = Qsid("cancelled-legacy-attach", UUID.randomUUID().toString)
    val head    = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    def attach(): F[SessionStart[F]] = service.createAppIfNeeded(qsid, head, Queue[F, String]().stream)

    // This attach fails on purpose. The check looks at what it leaves behind.
    val cancel = start(F.recover(F.map(attach())(_ => ())) { case _ => () })
    try {
      assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup never started")
      val cancelling = request(cancel)
      run(failSetup.put((), ()))
      Await.result(cancelling, 10.seconds)
      run(attach()) match {
        case SessionStart.Attached(_, release) => run(release())
        case other                             => throw new AssertionError(s"The attach after the rollback got $other")
      }
    } finally {
      // Leave nothing pending when an assertion fails.
      Try(run(failSetup.putEither((), Right(()), silent = true)))
      ()
    }
  }

  /**
   * The release of an attached view is cancelled while its cleanup waits for
   * the guard to close. The cleanup still runs to its end, and the cancellation
   * returns only then.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledRelease[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F         = Effect[F]
    val destroyed = new CountDownLatch(1)
    val guard     = new HeldClose[F]
    val extension = Extension.pure[F, String, String]("attached") { _ =>
      Extension.Handlers[F, String, String](onDestroy = _ => F.delay(destroyed.countDown()))
    }
    val view = new Guarded[F](List(extension), () => guard.close())
    val release = run(view.attach()) match {
      case SessionStart.Attached(_, release) => release
      case other                             => throw new AssertionError(s"Expected an attached view but got $other")
    }
    assert(view.registered, "The attached view is not registered")

    val cancel = start(release())
    try {
      assert(guard.started.await(10, TimeUnit.SECONDS), "The cleanup never reached the guard")
      val cancelling = request(cancel)
      assert(!cancelling.isCompleted, "The cancellation did not wait for the cleanup")
      guard.open()
      Await.result(cancelling, 10.seconds)
      // The cleanup was done when the cancellation returned.
      assert(destroyed.getCount == 0, "The cancellation returned before the extension was destroyed")
      assert(!view.registered, "The cancelled release left its view registered")
      view.attachAgain()
    } finally guard.open()
  }
}
