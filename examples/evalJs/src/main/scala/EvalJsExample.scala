import spoonbill._
import spoonbill.akka._
import spoonbill.server._
import spoonbill.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object EvalJsExample extends SimpleAkkaHttpSpoonbillApp {

  val globalContext = Context[Future, String, Any]

  import globalContext._
  import avocet.dsl._
  import avocet.dsl.html._

  private def onClick(access: Access) =
    for {
      result <- access.evalJs("window.confirm('Do you have cow superpower?')")
      _      <- access.transition(s => result.toString)
    } yield ()

  val service = akkaHttpService {
    SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("nothing"),
      document = { s =>
        optimize {
          Html(
            head(
              script(
                """var x = 0;
                  |setInterval(() => {
                  |  x++;
                  |  Spoonbill.invokeCallback('myCallback', x.toString());
                  |}, 1000);
                  |""".stripMargin
              )
            ),
            body(
              button("Click me", event("click")(onClick)),
              div(s)
            )
          )
        }
      },
      extensions = List(
        Extension { access =>
          for (_ <- access.registerCallback("myCallback")(arg => Future(println(arg))))
            yield Extension.Handlers()
        }
      )
    )
  }
}
