import spoonbill.Context
import spoonbill.akka._
import spoonbill.monix._
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

object MonixExample extends SimpleAkkaHttpSpoonbillApp {

  val ctx = Context[Task, Option[String], Any]

  import ctx._
  import avocet.dsl._
  import avocet.dsl.html._

  private val aInput = elementId()
  private val bInput = elementId()

  def service: AkkaHttpService = akkaHttpService {
    SpoonbillServiceConfig[Task, Option[String], Any](
      stateLoader = StateLoader.default(None),
      document = maybeResult =>
        optimize {
          Html(
            body(
              form(
                input(aInput, `type` := "number", event("input")(onChange)),
                span("+"),
                input(bInput, `type` := "number", event("input")(onChange)),
                span("="),
                maybeResult.map(result => span(result))
              )
            )
          )
        }
    )
  }

  private def onChange(access: Access) =
    for {
      a <- access.valueOf(aInput)
      b <- access.valueOf(bInput)
      _ <-
        if (a.trim.isEmpty || b.trim.isEmpty) Task.unit
        else access.transition(_ => Some((a.toInt + b.toInt).toString))
    } yield ()
}
