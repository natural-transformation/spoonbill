import java.nio.file.Paths
import spoonbill.Context
import spoonbill.Context.FileHandler
import spoonbill.akka._
import spoonbill.effect.io.FileIO
import spoonbill.monix._
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.state.javaSerialization._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration.DurationInt

object OneByOneFileStreamingExample extends SimpleAkkaHttpSpoonbillApp {

  case class State(aliveIndicator: Boolean, progress: Map[String, (Long, Long)], inProgress: Boolean)

  val globalContext = Context[Task, State, Any]

  import globalContext._
  import avocet.dsl._
  import avocet.dsl.html._

  val fileInput = elementId()

  // Use Delay binding explicitly to avoid deprecated delay helper.
  private val aliveIndicatorTick =
    Context.Delay[Task, State, Any](1.second, access =>
      access.transition(state => state.copy(aliveIndicator = !state.aliveIndicator))
    )

  def onUploadClick(access: Access): Task[Unit] = {

    def showProgress(fileName: String, loaded: Long, total: Long): Task[Unit] = access.transition { state =>
      val updated = state.progress + ((fileName, (loaded, total)))
      state.copy(progress = updated)
    }

    for {
      files <- access.listFiles(fileInput)
      _ <- access.transition(
             _.copy(
               progress = files.map { case FileHandler(fileName, size) => (fileName, (0L, size)) }.toMap,
               inProgress = true
             )
           )
      _ <- Task.sequence {
             files.map { (handler: FileHandler) =>
               val size = handler.size
               access.downloadFileAsStream(handler).flatMap { data =>
                 // File will be saved in 'downloads' directory
                 // in the root of the example project
                 val path = Paths.get(handler.fileName)
                 data
                   .over(0L) { case (acc, chunk) =>
                     val loaded = chunk.fold(acc)(_.length + acc)
                     showProgress(handler.fileName, loaded, size)
                       .map(_ => loaded)
                   }
                   .to(FileIO.write(path))
               }
             }
           }
      _ <- access.transition(_.copy(inProgress = false))
    } yield ()
  }

  val service = akkaHttpService {
    SpoonbillServiceConfig[Task, State, Any](
      stateLoader = StateLoader.default(State(aliveIndicator = false, Map.empty, inProgress = false)),
      document = { case State(aliveIndicator, progress, inProgress) =>
        optimize {
          Html(
            body(
              aliveIndicatorTick,
              div(when(aliveIndicator)(backgroundColor @= "red"), "Online"),
              input(`type` := "file", multiple, fileInput),
              ul(
                progress.map { case (name, (loaded, total)) =>
                  li(s"$name: $loaded / $total")
                }
              ),
              button(
                "Upload",
                when(inProgress)(disabled),
                event("click")(onUploadClick)
              )
            )
          )
        }
      }
    )
  }
}
