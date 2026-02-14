import spoonbill._
import spoonbill.akka._
import spoonbill.server._
import spoonbill.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FocusExample extends SimpleAkkaHttpSpoonbillApp {

  val globalContext = Context[Future, Boolean, Any]

  import globalContext._
  import avocet.dsl._
  import avocet.dsl.html._

  // Handler to input
  val inputId = elementId()

  val service: AkkaHttpService = akkaHttpService {
    SpoonbillServiceConfig[Future, Boolean, Any](
      stateLoader = StateLoader.default(false),
      document = _ =>
        optimize {
          Html(
            body(
              div("Focus example"),
              div(
                input(
                  inputId,
                  `type`      := "text",
                  placeholder := "Wanna get some focus?"
                )
              ),
              div(
                button(
                  event("click") { access =>
                    access.focus(inputId)
                  },
                  "Click to focus"
                )
              )
            )
          )
        }
    )
  }
}
