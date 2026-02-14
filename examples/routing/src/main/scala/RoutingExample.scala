import spoonbill.*
import spoonbill.akka.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object RoutingExample extends SimpleAkkaHttpSpoonbillApp {

  import State.globalContext._
  import spoonbill.web.PathAndQuery._
  import avocet.dsl._
  import avocet.dsl.html._

  val inputId = elementId()

  val service = akkaHttpService {
    SpoonbillServiceConfig[Future, State, Any](
      stateLoader = StateLoader.default(State()),
      document = state =>
        optimize {
          Html(
            head(
              title(s"ToDo [${state.selectedTab}]"),
              link(
                href   := "/static/main.css",
                rel    := "stylesheet",
                `type` := "text/css"
              )
            ),
            body(
              div("Super TODO tracker"),
              div(
                state.todos.keys map { tabName =>
                  a(
                    event("click") { access =>
                      access.transition(_.copy(selectedTab = tabName))
                    },
                    href := "/" + tabName.toLowerCase,
                    preventDefaultClickBehavior,
                    marginLeft @= "10px",
                    if (tabName == state.selectedTab) strong(tabName)
                    else tabName
                  )
                }
              ),
              div(
                clazz := "todos",
                (state.todos(state.selectedTab) zipWithIndex) map { case (todo, i) =>
                  div(
                    div(
                      clazz := {
                        if (!todo.done) "checkbox"
                        else "checkbox checkbox__checked"
                      }
                    ),
                    if (!todo.done) span(todo.text)
                    else span(textDecoration @= "line-through", todo.text),
                    event("click") { access =>
                      // Generate transition when clicking checkboxes
                      access.transition { s =>
                        val todos   = s.todos(s.selectedTab)
                        val updated = todos.updated(i, todos(i).copy(done = !todo.done))
                        s.copy(todos = s.todos + (s.selectedTab -> updated))
                      }
                    }
                  )
                }
              ),
              form(
                // Generate AddTodo action when 'Add' button clicked
                event("submit") { access =>
                  access.valueOf(inputId) flatMap { value =>
                    val todo = State.Todo(value, done = false)
                    access.transition { s =>
                      s.copy(todos = s.todos + (s.selectedTab -> (s.todos(s.selectedTab) :+ todo)))
                    }
                  }
                },
                input(
                  inputId,
                  `type`      := "text",
                  placeholder := "What should be done?"
                ),
                button("Add todo")
              )
            )
          )
        },
      router = Router(
        fromState = { case State(tab, _) =>
          Root / tab.toLowerCase
        },
        toState = {
          case Root =>
            initialState => Future.successful(initialState)
          case Root / tabName if State.Tabs.exists(_.toLowerCase == tabName.toLowerCase) =>
            initialState =>
              val key = initialState.todos.keys.find(_.toLowerCase == tabName)
              Future.successful(key.fold(initialState)(k => initialState.copy(selectedTab = k)))
        }
      ),
      delayedRender = 1.millis
    )
  }
}

case class State(
  selectedTab: String = "Tab1",
  todos: Map[String, Vector[State.Todo]] = State.Tabs.zipWithIndex.map { case (tab, i) => tab -> State.Todo(i) }.toMap
)

object State {

  final val Tabs = Seq("Tab1", "Tab2", "Tab3")

  val globalContext = Context[Future, State, Any]
  case class Todo(text: String, done: Boolean)
  object Todo {
    def apply(n: Int): Vector[Todo] = (0 to n * 10).toVector map { i =>
      Todo(s"This is TODO #$i", done = false)
    }
  }
}
