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

  "A native form binding" should "mark an ordinary HTML form without installing a typed action" in {
    val node: Document.Node[Binding] = form(
      method := "post", action := "/auth/logout", button("Sign out"),
      ActionForms.nativeSubmit[Future, Int, Unit]
    )
    val rc = new Html5RenderContext[Binding](TextPrettyPrintingConfig.noPrettyPrinting)
    node(rc)
    val html = rc.mkString
    val openingForm = html.take(html.indexOf('>') + 1)
    openingForm should include("data-spoonbill-native-submit=\"true\"")
    openingForm should include("method=\"post\"")
    openingForm should include("action=\"/auth/logout\"")
    html should not include "data-spoonbill-action-fields"
    html.substring(html.indexOf('>') + 1) should not include "data-spoonbill-native-submit="
  }

  it should "retain the typed descriptor when both explicit bindings are supplied" in {
    val actions = new Actions[Future, Int, Nothing]
    val action = actions.public(ActionName.parse("fixture.priority").toOption.get,
      InputSchema.empty, PublicPolicy.allow[Future, Unit])((_, _) => Future.successful(UiOutcome.unchanged[Int]))
    val node: Document.Node[Binding] = form(
      ActionForms.nativeSubmit[Future, Int, Unit],
      ActionForms.onSubmit[Future, Int, Unit, Unit](action, _ => identity), button("Submit")
    )
    val rc = new Html5RenderContext[Binding](TextPrettyPrintingConfig.noPrettyPrinting)
    node(rc)
    rc.mkString should include("data-spoonbill-native-submit=\"true\"")
    rc.mkString should include("data-spoonbill-action-fields=\"[]\"")
  }
}
