package spoonbill.server.internal

import avocet.{Document, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.{Component, Context}
import spoonbill.effect.Effect
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor

import scala.concurrent.Future

class Html5RenderContextSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  "Preset DOM ids" should "follow styles while preserving attribute escaping and text sibling positions" in {
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = true)
    rc.openNode(XmlNs.html, "div")
    rc.setStyle("color", "red\" onmouseover=\"alert(1)&<")
    rc.setAttr(XmlNs.html, "title", "\"<&")
    rc.addTextNode("<&")
    rc.openNode(XmlNs.html, "span")
    rc.setStyle("display", "block")
    rc.closeNode("span")
    rc.openNode(XmlNs.html, "input")
    rc.closeNode("input")
    rc.closeNode("div")

    rc.mkString shouldBe
      "<div style=\"color:red&quot; onmouseover=&quot;alert(1)&amp;&lt;;\" k=\"1\" title=\"&quot;&lt;&amp;\">&lt;&amp;" +
        "<span style=\"display:block;\" k=\"2\"></span><input k=\"3\"/></div>"
  }

  it should "flush a styled parent's id before a component and preserve subsequent sibling ids" in {
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = true)
    val component = new Component[Future, Unit, Unit, Unit](()) {
      def render(parameters: Unit, state: Unit): context.Node = Document.Node { target =>
        target.openNode(XmlNs.html, "span")
        target.setStyle("color", "blue")
        target.addTextNode("component")
        target.closeNode("span")
      }
    }
    rc.openNode(XmlNs.html, "div")
    rc.setStyle("display", "block")
    rc.addMisc(Context.ComponentEntry[Future, Unit, Unit, Unit, Unit, Unit](component, (), (_, _) => Future.unit))
    rc.openNode(XmlNs.html, "span")
    rc.closeNode("span")
    rc.closeNode("div")

    rc.mkString shouldBe
      "<div style=\"display:block;\" k=\"1\"><span style=\"color:blue;\" k=\"1\">component</span><span k=\"2\"></span></div>"
  }

  it should "leave ordinary markup free of preset ids when disabled" in {
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = false)
    rc.openNode(XmlNs.html, "div")
    rc.setStyle("color", "blue")
    rc.setAttr(XmlNs.html, "title", "plain")
    rc.closeNode("div")

    rc.mkString shouldBe "<div style=\"color:blue;\" title=\"plain\"></div>"
  }
}
