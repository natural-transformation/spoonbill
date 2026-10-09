package spoonbill

import avocet.Document
import avocet.dsl.*
import avocet.dsl.html.*
import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import spoonbill.action.*
import spoonbill.effect.Effect

final class ActionFormsSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private type Binding = Context.Binding[Future, Int, Unit]

  "A typed form binding" should "place its descriptor on the form even after child arguments" in {
    val name    = ActionName.parse("fixture.submit").fold(error => fail(error.toString), identity)
    val field   = FieldName.parse("value").fold(error => fail(error.toString), identity)
    val actions = new Actions[Future, Int, Nothing]
    val action = actions.public(name, InputSchema.text(field, 30), PublicPolicy.allow[Future, String]) { (_, _) =>
      Future.successful(UiOutcome.update[Int](_ + 1))
    }
    val node: Document.Node[Binding] = form(
      input(avocet.dsl.html.name := "value"),
      button("Submit"),
      ActionForms.onSubmit[Future, Int, Unit, String](action, _ => identity)
    )
    val rc = new Html5RenderContext[Binding](TextPrettyPrintingConfig.noPrettyPrinting)
    node(rc)
    val html        = rc.mkString
    val openingForm = html.take(html.indexOf('>') + 1)
    openingForm should include("data-spoonbill-action-fields=")
    openingForm should include("value")
    html.substring(html.indexOf('>') + 1) should not include "data-spoonbill-action-fields="
  }
}
