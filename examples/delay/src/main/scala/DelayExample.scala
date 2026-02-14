import spoonbill._
import spoonbill.akka._
import spoonbill.server._
import spoonbill.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object DelayExample extends SimpleAkkaHttpSpoonbillApp {

  val globalContext = Context[Future, Option[Int], Any]

  import globalContext._
  import avocet.dsl._
  import avocet.dsl.html._

  private val resetAfterDelay =
    Context.Delay[Future, Option[Int], Any](3.seconds, access =>
      access.transition { case _ =>
        None
      }
    )

  val document: Render = {
    case Some(n) =>
      optimize {
        Html(
          body(
            resetAfterDelay,
            button(
              "Push the button " + n,
              event("click") { access =>
                access.transition { case s =>
                  s.map(_ + 1)
                }
              }
            ),
            "Wait 3 seconds!"
          )
        )
      }
    case None =>
      optimize {
        Html(
          body(
            button(
              event("click") { access =>
                access.transition(_ => Some(1))
              },
              "Push the button"
            )
          )
        )
      }
  }

  val service = akkaHttpService {
    SpoonbillServiceConfig[Future, Option[Int], Any](
      stateLoader = StateLoader.default(Option.empty[Int]),
      document = document
    )
  }
}
