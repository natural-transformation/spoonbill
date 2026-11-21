package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Success, Try}
import spoonbill.Qsid
import spoonbill.effect.{Effect, Reporter}
import spoonbill.server.SpoonbillServiceConfig
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request, Response}

/**
 * Long-polling cases that need a real session service and an effect that can
 * be cancelled. `Future` cannot be cancelled, so only the lazy-effect modules
 * run them, as they run the extension checks of this folder.
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
}
