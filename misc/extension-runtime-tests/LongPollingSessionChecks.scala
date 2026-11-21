package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Success, Try}
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{AsyncTable, Effect, Reporter}
import spoonbill.server.SpoonbillServiceConfig
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request, Response}

/**
 * Long-polling cases that need a real session service and a lazy effect: one
 * that can be cancelled, and that runs its steps on threads of its own. Only
 * the lazy-effect modules run them, as they run the extension checks of this
 * folder.
 */
object LongPollingSessionChecks {

  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 20.seconds)

  /**
   * A long-polling request can be cancelled at any step: the browser gave up,
   * or the server ended the request. The request of a new opening does two
   * things to the opening before: it takes the place of its topic, and it
   * tells its view to leave. A cancellation must not come between the two. If
   * it did, the view would stay, and every later poll would wait for it until
   * its idle timeout, a minute.
   *
   * @param start
   *   starts an effect and returns the function that cancels it
   */
  def cancelledReopening[F[_]: Effect](start: F[Unit] => () => Unit)(implicit ec: ExecutionContext): Unit = {
    val F       = Effect[F]
    val topics  = new AtomicInteger()
    val swapped = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)
    val reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit   = ()
      def error(message: String): Unit                     = ()
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit                   = ()
      def info(message: String): Unit                      = ()
      // The service says this while it installs a topic. The second topic is
      // the one of opening 2: its request stops here, inside that step.
      def debug(message: String): Unit =
        if (message.startsWith("Create long-polling topic") && topics.incrementAndGet() == 2) {
          swapped.countDown()
          resume.await(30, TimeUnit.SECONDS)
          ()
        }
      def debug(message: String, arg1: Any): Unit                       = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }
    val config = SpoonbillServiceConfig[F, Int, Any](
      stateLoader = (_, _) => F.pure(0),
      document = state => Html(body(state.toString)),
      reporter = reporter
    )
    val sessions = new SessionsService(config, new PageService(config))
    val messaging = new MessagingService[F](
      reporter = reporter,
      // Not used by the long-polling requests.
      commonService = null.asInstanceOf[CommonService[F]],
      sessionsService = sessions,
      compressionSupport = None,
      orphanTopicTimeout = 1.minute
    )
    val qsid = Qsid("long-polling-cancellation", UUID.randomUUID().toString)
    def poll(open: Long): F[Response.Status] = {
      val path = PathAndQuery.fromString(s"/bridge/long-polling/session/subscribe?open=$open")
      F.map(messaging.longPollingSubscribe(qsid, Request(Request.Method.Get, path, Nil, None, ())))(_.status)
    }

    assert(run(poll(1)) == Response.Status.Ok, "The poll of opening 1 was not answered with a frame")
    val first = run(sessions.getApp(qsid))
    assert(first.nonEmpty, "The poll of opening 1 registered no view")

    val cancel = start(F.map(poll(2))(_ => ()))
    try {
      assert(swapped.await(20, TimeUnit.SECONDS), "The request of opening 2 did not install its topic")
      // The cancellation is asked for from another thread, while the request
      // is stopped inside the step. It cannot return before the request goes
      // on, so this waits the full second. On a machine so busy that the
      // cancellation arrives later still, the case passes without having
      // tried the gap. It cannot fail for it.
      val cancelling = Future(cancel())
      Try(Await.ready(cancelling, 1.second))
      resume.countDown()
      Await.result(cancelling, 30.seconds)
    } finally resume.countDown()

    // The view of opening 1 has to leave, so that the next poll gets a new one.
    val next = Try(run(poll(2)))
    assert(
      next == Success(Response.Status.Ok),
      s"The poll after the cancelled request got $next: the view of opening 1 has not left"
    )
    val current = run(sessions.getApp(qsid))
    assert(current.nonEmpty && current != first, "The poll after the cancelled request got the view of opening 1")
  }

  /**
   * The poll of a new opening arrives while the view of the opening before is
   * still in the setup of its extension. That setup is stopped, because the
   * transport of its view is over. The poll of the new opening only waited
   * for that view: it starts again and gets a view of its own.
   */
  def reopenedDuringSetup[F[_]: Effect]()(implicit ec: ExecutionContext): Unit = {
    val F            = Effect[F]
    val topics       = new AtomicInteger()
    val setups       = new AtomicInteger()
    val setupStarted = new CountDownLatch(1)
    val reopened     = new CountDownLatch(1)
    val finishSetup  = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit   = ()
      def error(message: String): Unit                     = ()
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit                   = ()
      def info(message: String): Unit                      = ()
      // The second topic is the one of opening 2. The old topic has ended by then.
      def debug(message: String): Unit =
        if (message.startsWith("Create long-polling topic") && topics.incrementAndGet() == 2) reopened.countDown()
      def debug(message: String, arg1: Any): Unit                       = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }
    // The first setup waits. Every later one returns at once.
    val held = Extension[F, Int, Any]("held") { _ =>
      if (setups.incrementAndGet() > 1) F.pure(Extension.Handlers[F, Int, Any]())
      else
        F.flatMap(F.delay(setupStarted.countDown())) { _ =>
          F.map(finishSetup.get(()))(_ => Extension.Handlers[F, Int, Any]())
        }
    }
    val config = SpoonbillServiceConfig[F, Int, Any](
      stateLoader = (_, _) => F.pure(0),
      document = state => Html(body(state.toString)),
      reporter = reporter,
      extensions = List(held)
    )
    val sessions = new SessionsService(config, new PageService(config))
    val messaging = new MessagingService[F](
      reporter = reporter,
      // Not used by the long-polling requests.
      commonService = null.asInstanceOf[CommonService[F]],
      sessionsService = sessions,
      compressionSupport = None,
      orphanTopicTimeout = 1.minute
    )
    val qsid = Qsid("long-polling-reopened", UUID.randomUUID().toString)
    def poll(open: Long): Future[Response.Status] = {
      val path = PathAndQuery.fromString(s"/bridge/long-polling/session/subscribe?open=$open")
      F.toFuture(F.map(messaging.longPollingSubscribe(qsid, Request(Request.Method.Get, path, Nil, None, ())))(_.status))
    }

    val first = poll(1)
    try {
      assert(setupStarted.await(10, TimeUnit.SECONDS), "The setup of the first view never started")
      val second = poll(2)
      assert(reopened.await(10, TimeUnit.SECONDS), "The poll of opening 2 did not install its topic")
      // The poll of opening 2 now reaches the registry and waits there, and
      // the first view learns that its transport is over. Nothing tells the
      // test when both have happened, so it gives them half a second. If the
      // setup goes on too early, the first view is attached and then retired,
      // and the poll of opening 2 waits for that. It gets its view either
      // way, so the case cannot fail for the pause.
      Thread.sleep(500)
      run(finishSetup.put((), ()))

      val answered = Try(Await.result(second, 20.seconds))
      assert(answered == Success(Response.Status.Ok), s"The poll of opening 2 got $answered")
      // The poll of opening 1 is answered too: with the stopped setup, or with a frame.
      Await.ready(first, 20.seconds)
      assert(run(sessions.getApp(qsid)).nonEmpty, "The poll of opening 2 left no view registered")
      assert(setups.get() == 2, s"The view of opening 2 ran no setup of its own: ${setups.get()} setups")
    } finally {
      // Leave nothing pending when an assertion fails.
      Try(run(finishSetup.putEither((), Right(()), silent = true)))
      ()
    }
  }
}
