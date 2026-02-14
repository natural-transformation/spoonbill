package spoonbill

import spoonbill.Context.ElementId
import spoonbill.util.JsCode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsCodeSpec extends AnyFlatSpec with Matchers {

  import JsCode._

  "JsCode.apply" should "construct correct list" in {
    val el1    = new ElementId(Some("el1"))
    val el2    = new ElementId(Some("el2"))
    val jsCode = JsCode(List("--", "++", "//"), List(el1, el2))

    jsCode should equal(Part("--", Element(el1, Part("++", Element(el2, Part("//", End))))))
  }

  "jsCode.mkString" should "construct correct string" in {
    val el1 = new ElementId(Some("el1"))
    val el2 = new ElementId(Some("el2"))
    val el2id: ElementId => avocet.Id = {
      case `el1` => avocet.Id("1_1")
      case `el2` => avocet.Id("1_2")
    }
    val jsCode = "swapElements(" :: el1 :: ", " :: el2 :: ");" :: End

    jsCode.mkString(el2id) should equal("swapElements(Spoonbill.element('1_1'), Spoonbill.element('1_2'));")
  }
}
