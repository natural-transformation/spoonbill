import java.net.InetSocketAddress
import spoonbill.Context
import spoonbill.effect.Effect
import spoonbill.server
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.server.standalone
import spoonbill.state.javaSerialization.*
import spoonbill.zio.taskEffectLayer
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*

object ZioExample extends ZIOAppDefault {

  val address = new InetSocketAddress("localhost", 8080)

  val ctx = Context[Task, Option[Int], Any]

  import ctx._

  val aInput = elementId()
  val bInput = elementId()

  import avocet.dsl._
  import avocet.dsl.html._

  def renderForm(maybeResult: Option[Int]) = optimize {
    form(
      input(
        aInput,
        name   := "a-input",
        `type` := "number",
        event("input")(onChange)
      ),
      span("+"),
      input(
        bInput,
        name   := "b-input",
        `type` := "number",
        event("input")(onChange)
      ),
      span(s"= ${maybeResult.fold("?")(_.toString)}")
    )
  }

  def onChange(access: Access) =
    for {
      a <- access.valueOf(aInput)
      _ <- ZIO.logInfo(s"a = $a")
      b <- access.valueOf(bInput)
      _ <- ZIO.logInfo(s"b = $b")
      _ <- access.transition(_ => Some(a.toInt + b.toInt)).unless(a.trim.isEmpty || b.trim.isEmpty)
    } yield ()

  final val app = ZIO.service[Effect[Task]].flatMap { implicit taskEffect =>
    val config =
      SpoonbillServiceConfig[Task, Option[Int], Any](
        stateLoader = StateLoader.default(None),
        document = maybeResult =>
          optimize {
            Html(
              body(renderForm(maybeResult))
            )
          }
      )
    for {
      _ <- ZIO.logInfo(s"Try to start server at $address")
      handler <- standalone.buildServer[Task, Array[Byte]](
                   service = server.spoonbillService(config),
                   address = address,
                   gracefulShutdown = false
                 )
      _ <- ZIO.unit.forkDaemon *> ZIO.logInfo(s"Server started")
      _ <- handler.awaitShutdown()
    } yield ()
  }

  override final val run: ZIO[Any, Throwable, Unit] =
    app.provide(taskEffectLayer)
}
