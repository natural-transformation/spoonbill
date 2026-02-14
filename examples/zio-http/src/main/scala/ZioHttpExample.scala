import spoonbill.Context
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery
import spoonbill.zio.Zio2Effect
import spoonbill.zio.http.ZioHttpSpoonbill
import scala.concurrent.ExecutionContext
import zio.{ExitCode as ZExitCode, RIO, Runtime, ZIO, ZIOAppDefault}
import zio.http.Response
import zio.http.Routes
import zio.http.Server

object ZioHttpExample extends ZIOAppDefault {

  type AppTask[A] = RIO[Any, A]

  import avocet.dsl._
  import avocet.dsl.html._
  import scala.concurrent.duration._

  val ctx = Context[ZIO[Any, Throwable, *], Option[Int], Any]

  import ctx._

  private val resetAfterDelay =
    Context.Delay[ZIO[Any, Throwable, *], Option[Int], Any](3.seconds, access =>
      access.transition { case _ =>
        None
      }
    )

  val document: ctx.Render = {
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

  private class Service()(implicit runtime: Runtime[Any]) {

    implicit val ec: ExecutionContext               = Runtime.defaultExecutor.asExecutionContext
    implicit val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

    def config = SpoonbillServiceConfig[AppTask, Option[Int], Any](
      stateLoader = StateLoader.default(Option.empty[Int]),
      rootPath = PathAndQuery.Root,
      document = document
    )

    def route(): Routes[Any, Response] =
      new ZioHttpSpoonbill[Any].service(config)

  }

  private def getAppRoute(): ZIO[Any, Nothing, Routes[Any, Response]] =
    ZIO.runtime[Any].map { implicit rts =>
      new Service().route()
    }

  override def run: ZIO[Any, Nothing, ZExitCode] =
    for {
      routes <- getAppRoute()
      _ <- Server
             .serve(routes)
             .provide(Server.defaultWithPort(8088))
             .orDie
    } yield ZExitCode.success

}
