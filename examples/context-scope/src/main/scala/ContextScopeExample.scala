import ViewState.Tab.{About, Blog}
import spoonbill.*
import spoonbill.akka.*
import spoonbill.server.*
import spoonbill.state.javaSerialization.*
import spoonbill.util.Lens
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ContextScopeExample extends SimpleAkkaHttpSpoonbillApp {

  val context = Context[Future, ViewState, Any]

  import context._
  import avocet.dsl._
  import avocet.dsl.html._

  final private val blogLens = Lens[ViewState, Blog](
    read = { case ViewState(_, blog: Blog) => blog },
    write = { case (orig, blog) => orig.copy(tab = blog) }
  )

  final private val blogView = new BlogView(context.scope(blogLens))

  val service: AkkaHttpService = akkaHttpService {
    SpoonbillServiceConfig[Future, ViewState, Any](
      stateLoader = StateLoader.default(ViewState("My blog", Blog.default)),
      document = { state =>
        val isBlog  = state.tab.isInstanceOf[Blog]
        val isAbout = state.tab.isInstanceOf[About]

        optimize {
          Html(
            body(
              h1(state.blogName),
              div(
                div(
                  when(isBlog)(fontWeight @= "bold"),
                  when(isBlog)(borderBottom @= "1px solid black"),
                  event("click")(access => access.transition(_.copy(tab = Blog.default))),
                  padding @= "5px",
                  display @= "inline-block",
                  "Blog"
                ),
                div(
                  when(isAbout)(fontWeight @= "bold"),
                  when(isAbout)(borderBottom @= "1px solid black"),
                  event("click")(access => access.transition(_.copy(tab = About.default))),
                  padding @= "5px",
                  display @= "inline-block",
                  "About"
                )
              ),
              div(
                marginTop @= "20px",
                state.tab match {
                  case blog: Blog   => blogView(blog)
                  case about: About => p(about.text)
                }
              )
            )
          )
        }
      }
    )
  }
}
