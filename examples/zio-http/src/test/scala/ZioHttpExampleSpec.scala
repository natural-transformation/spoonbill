import spoonbill.Context
import avocet.{RenderContext, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ListBuffer
import zio.ZIO

final class ZioHttpExampleSpec extends AnyFlatSpec with Matchers {

  private type ZTask[A] = ZIO[Any, Throwable, A]

  private final class MiscCollectingRenderContext[M] extends RenderContext[M] {
    val misc: ListBuffer[M] = ListBuffer.empty
    def openNode(xmlns: XmlNs, name: String): Unit               = ()
    def closeNode(name: String): Unit                            = ()
    def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = ()
    def setStyle(name: String, value: String): Unit              = ()
    def addTextNode(text: String): Unit                          = ()
    def addMisc(item: M): Unit                                   = misc += item
  }

  "ZioHttpExample.document" should "include a delay binding for Some state" in {
    val rc =
      new MiscCollectingRenderContext[Context.Binding[ZTask, Option[Int], Any]]()

    ZioHttpExample.document(Some(1))(rc)

    val hasDelay = rc.misc.exists {
      case Context.Delay(_, _) => true
      case _                   => false
    }
    hasDelay shouldBe true
  }
}
