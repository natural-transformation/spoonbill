import spoonbill.testkit._
import avocet.{Id, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PseudoHtmlSpec extends AnyFlatSpec with Matchers {

  "PseudoDom.render" should "map avocet.Node to PseudoDom.Element" in {
    import avocet.dsl.html._

    val node = div()
    val rr   = PseudoHtml.render(node)

    rr.pseudoDom shouldEqual PseudoHtml.Element(Id("1"), XmlNs.html, "div", Map.empty, Map.empty, List.empty)
  }

  it should "map nested avocet.Node to correspondent pseudo DOM elements" in {
    import avocet.dsl._
    import avocet.dsl.html._
    import PseudoHtml._

    val node = body(ul(li("1"), li("2"), li("3")))
    val rr   = PseudoHtml.render(node)

    rr.pseudoDom shouldEqual Element(
      Id("1"),
      XmlNs.html,
      "body",
      Map.empty,
      Map.empty,
      List(
        Element(
          Id("1_1"),
          XmlNs.html,
          "ul",
          Map.empty,
          Map.empty,
          List(
            Element(Id("1_1_1"), XmlNs.html, "li", Map.empty, Map.empty, List(Text(Id("1_1_1_1"), "1"))),
            Element(Id("1_1_2"), XmlNs.html, "li", Map.empty, Map.empty, List(Text(Id("1_1_2_1"), "2"))),
            Element(Id("1_1_3"), XmlNs.html, "li", Map.empty, Map.empty, List(Text(Id("1_1_3_1"), "3")))
          )
        )
      )
    )
  }

  it should "map attributes well" in {
    import avocet.dsl.html._

    val node = div(clazz := "foo bar", id := "baz")
    val rr   = PseudoHtml.render(node)

    rr.pseudoDom shouldEqual PseudoHtml.Element(
      Id("1"),
      XmlNs.html,
      "div",
      Map("class" -> "foo bar", "id" -> "baz"),
      Map.empty,
      List.empty
    )
  }

  it should "map styles well" in {
    import avocet.dsl.html._

    val node = div(backgroundColor @= "red", border @= "1px")
    val rr   = PseudoHtml.render(node)

    rr.pseudoDom shouldEqual PseudoHtml.Element(
      Id("1"),
      XmlNs.html,
      "div",
      Map.empty,
      Map("background-color" -> "red", "border" -> "1px"),
      List.empty
    )
  }

  "byName" should "find list of Element by value of name attribute" in {

    import avocet.dsl._
    import avocet.dsl.html._

    val dom = body(
      div("Hello world"),
      button(
        name := "my-button",
        "Click me"
      )
    )

    val pd = PseudoHtml.render(dom).pseudoDom
    pd.byName("my-button").headOption.map(_.id) shouldEqual Some(Id("1_2"))
  }

  "byAttrEquals" should "find list of Element by exact attribute value" in {

    import avocet.dsl._
    import avocet.dsl.html._

    val dom = body(
      button(name := "alpha", "A"),
      button(name := "beta", "B")
    )

    val pd = PseudoHtml.render(dom).pseudoDom

    pd.byAttrEquals("name", "beta").headOption.map(_.id) shouldEqual Some(Id("1_2"))
  }

  "firstByTag" should "return the first matching element by tag" in {

    import avocet.dsl._
    import avocet.dsl.html._

    val dom = body(
      div("One"),
      div("Two"),
      button("Click")
    )

    val pd = PseudoHtml.render(dom).pseudoDom

    pd.firstByTag("div").map(_.id) shouldEqual Some(Id("1_1"))
    pd.firstByTag("button").map(_.id) shouldEqual Some(Id("1_3"))
    pd.firstByTag("section") shouldEqual None
  }
}
