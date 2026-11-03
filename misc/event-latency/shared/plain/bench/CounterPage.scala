package bench

import avocet.dsl.*
import avocet.dsl.html.*
import scala.concurrent.Future
import spoonbill.Context

/** Counter state. `count` changes on every click; every row depends on it. */
final case class Counter(count: Int)

/**
 * A small "user app": one button and `rows` text rows that all change on each
 * click, so every event produces a real render, diff and DOM patch.
 * Element ids: html=1, injected head=1_1, body=1_2, button=1_2_1.
 */
object CounterPage {
  val rows: Int = sys.props.get("bench.rows").map(_.toInt).getOrElse(50)

  val context = Context[Future, Counter, Any]
  import context.*

  def document(state: Counter): avocet.Document.Node[Context.Binding[Future, Counter, Any]] =
    optimize {
      Html(
        body(
          button(
            "increment",
            event("click")(access => access.transition(current => current.copy(count = current.count + 1)))
          ),
          div(s"count=${state.count}"),
          div((1 to rows).map(row => div(s"row $row value ${state.count * row}")))
        )
      )
    }
}
