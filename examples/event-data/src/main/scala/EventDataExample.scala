import spoonbill._
import spoonbill.akka._
import spoonbill.server._
import spoonbill.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object EventDataExample extends SimpleAkkaHttpSpoonbillApp {

  val globalContext = Context[Future, String, Any]

  import globalContext._
  import avocet.dsl._
  import avocet.dsl.html._

  val service = akkaHttpService {
    SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("nothing"),
      document = json =>
        optimize {
          Html(
            body(
              input(
                `type` := "text",
                event("keydown") { access =>
                  access.eventData.flatMap { eventData =>
                    access.transition(_ => eventData)
                  }
                }
              ),
              pre(json)
            )
          )
        }
    )
  }
}
