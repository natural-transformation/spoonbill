package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.{Context, Extension, Qsid}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.internal.TimerTestSupport.{Reports, Worker}
import spoonbill.server.{HttpResponse, SpoonbillServiceConfig}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request, Response}

/**
 * One session of a counter page on long polling, for the cases of
 * [[LongPollingOpeningSpec]]. The worker runs the tasks of the service when a
 * case drains it.
 *
 * @param destroy
 *   what the page's extension does when its view is destroyed
 * @param storage
 *   the state storage, when a case needs one that misbehaves
 * @param reporter
 *   the reporter of the service, when a case needs one that misbehaves
 * @param setup
 *   what a first extension waits for in its setup, when a case needs a view
 *   that is still being set up. The service looks for a closed connection
 *   before it sets up the extension after it.
 */
private[services] final class LongPollingFixture(
  destroy: () => Future[Unit] = () => Future.unit,
  storage: Option[StateStorage[Future, Int]] = None,
  reporter: Reports => Reporter = identity,
  setup: Option[() => Future[Unit]] = None
) {
  import LongPollingFixture.await
  private given Effect[Future] = Effect.futureEffect

  val worker    = new Worker
  val reports   = new Reports
  var loads     = 0 // how often the state was built from a request
  var destroyed = 0 // how many views were destroyed
  private val context = Context[Future, Int, Any]
  import context.*
  private val config = SpoonbillServiceConfig[Future, Int, Any](
    stateLoader = (_, _) => { loads += 1; Future.successful(0) },
    document = state =>
      Html(
        head(title("Counter")),
        body(button("Add", event("click")(access => access.transition(_ + 1))), p(state.toString))
      ),
    reporter = reporter(reports),
    stateStorage = storage.orNull,
    extensions = setup.toList.map { waited =>
      Extension[Future, Int, Any]("held")(_ =>
        summon[Effect[Future]].map(waited())(_ => Extension.Handlers[Future, Int, Any]())
      )
    } ::: List(
      Extension.pure[Future, Int, Any]("retirement")(_ =>
        Extension.Handlers[Future, Int, Any](onDestroy = _ => { destroyed += 1; destroy() })
      )
    )
  )(worker)
  val sessions = new SessionsService(config, new PageService(config))
  val messaging = new MessagingService[Future](
    reporter = reports,
    // Not used by the long-polling requests.
    commonService = null.asInstanceOf[CommonService[Future]],
    sessionsService = sessions,
    compressionSupport = None,
    orphanTopicTimeout = 1.minute
  )
  val qsid = Qsid("long-polling", UUID.randomUUID().toString)

  def request(open: Option[Long]): Request[Unit] = {
    val path = "/bridge/long-polling/session/subscribe" + open.fold("")(number => s"?open=$number")
    Request(Request.Method.Get, PathAndQuery.fromString(path), Nil, None, ())
  }

  /** One poll. It is answered when the view has a frame, or when the view has ended. */
  def subscribe(open: Option[Long]): Future[HttpResponse[Future]] = {
    val poll = messaging.longPollingSubscribe(qsid, request(open))
    worker.drain()
    poll
  }

  /** One message of a bridge, as its publish request carries it. */
  def publish(open: Option[Long], message: String): Future[HttpResponse[Future]] = {
    val body = await(Stream(Bytes.wrap(message.getBytes("UTF-8"))).mat[Future]())
    val sent = messaging.longPollingPublish(qsid, body, open)
    worker.drain()
    sent
  }

  def view: Option[AnyRef] = await(sessions.getApp(qsid))

  /** The status of a poll that has been answered. None while it waits. */
  def status(poll: Future[HttpResponse[Future]]): Option[Response.Status] = poll.value.map(_.get.status)

  private var outstanding = Map.empty[Option[Long], Future[HttpResponse[Future]]]

  /** The frames that are ready for this opening, in order. One poll stays outstanding, as in a browser. */
  def frames(open: Option[Long]): Vector[String] = {
    @tailrec def collect(done: Vector[String]): Vector[String] = {
      val poll = outstanding.getOrElse(open, subscribe(open))
      worker.drain()
      poll.value match {
        case Some(Success(response)) if response.status == Response.Status.Ok =>
          outstanding -= open
          collect(done :+ await(response.body.fold(Bytes.empty)(_ ++ _)).asUtf8String)
        case Some(Success(_)) => outstanding -= open; done
        case Some(Failure(error)) => throw error
        case None => outstanding += open -> poll; done
      }
    }
    collect(Vector.empty)
  }
}

private[services] object LongPollingFixture {
  def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  /** The frame that tells a bridge to listen to clicks. A view sends it once. */
  def announcesClick(frames: Vector[String]): Boolean = frames.exists(_.startsWith("""[2,"click""""))

  /** A view that handled a click renders the next state and sends the patch. */
  def patches(frames: Vector[String]): Boolean = frames.exists(_.startsWith("[4,"))

  /** The first click on the button, as a bridge sends it. The button is the first child of the body. */
  val click = """[0,"0:1_2_1:click"]"""
}
