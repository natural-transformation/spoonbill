import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink, Source}
import spoonbill._
import spoonbill.akka._
import spoonbill.server._
import spoonbill.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ExtensionExample extends SimpleAkkaHttpSpoonbillApp {

  private val ctx = Context[Future, List[String], String]

  import ctx._

  private val (queue, queueSource) = Source
    .queue[String](10, OverflowStrategy.fail)
    .preMaterialize()

  private val topicListener = Extension.pure[Future, List[String], String] { access =>
    val queueSink = queueSource.runWith(Sink.queue[String]())
    def aux(): Future[Unit] = queueSink.pull() flatMap {
      case Some(message) =>
        access
          .transition(_ :+ message)
          .flatMap(_ => aux())
      case None =>
        Future.unit
    }
    aux()
    Extension.Handlers[Future, List[String], String](
      onMessage = message => queue.offer(message).map(_ => ()),
      onDestroy = () => Future.successful(queueSink.cancel())
    )
  }

  private def onSubmit(access: Access) =
    for {
      sessionId <- access.sessionId
      name      <- access.valueOf(nameElement)
      text      <- access.valueOf(textElement)
      userName =
        if (name.trim.isEmpty) s"Anonymous #${sessionId.hashCode().toHexString}"
        else name
      _ <-
        if (text.trim.isEmpty) Future.unit
        else access.publish(s"$userName: $text")
      _ <- access.property(textElement).set("value", "")
    } yield ()

  private val nameElement = elementId()
  private val textElement = elementId()

  private val config = SpoonbillServiceConfig[Future, List[String], String](
    stateLoader = StateLoader.default(Nil),
    extensions = List(topicListener),
    document = { message =>

      import avocet.dsl._
      import avocet.dsl.html._

      optimize {
        Html(
          body(
            div(
              backgroundColor @= "yellow",
              padding @= "10px",
              border @= "1px solid black",
              "This is a chat. Open this app in few browser tabs or on few different computers"
            ),
            div(
              marginTop @= "10px",
              padding @= "10px",
              height @= "250px",
              backgroundColor @= "#eeeeee",
              message map { x =>
                div(x)
              }
            ),
            form(
              marginTop @= "10px",
              input(`type` := "text", placeholder := "Name", nameElement),
              input(`type` := "text", placeholder := "Message", textElement),
              button("Sent"),
              event("submit")(onSubmit)
            )
          )
        )
      }
    }
  )

  val service: AkkaHttpService =
    akkaHttpService(config)
}
