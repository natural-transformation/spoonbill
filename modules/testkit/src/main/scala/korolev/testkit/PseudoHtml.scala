package spoonbill.testkit

import spoonbill.Context
import spoonbill.Context.{Binding, ComponentEntry, ElementId}
import avocet.{Id, IdBuilder, RenderContext, XmlNs}
import avocet.Document.Node
import avocet.events.EventId
import scala.annotation.tailrec

sealed trait PseudoHtml {

  import PseudoHtml._

  def id: Id

  def find(f: PseudoHtml => Boolean): List[PseudoHtml] = {
    @tailrec def aux(acc: List[Element], rest: List[PseudoHtml]): List[Element] = rest match {
      case Nil                        => acc
      case (_: Text) :: xs            => aux(acc, xs)
      case (e: Element) :: xs if f(e) => aux(e :: acc, xs ::: e.children)
      case (e: Element) :: xs         => aux(acc, xs ::: e.children)
    }
    aux(Nil, List(this))
  }

  def findElement(f: Element => Boolean): List[Element] = {
    @tailrec def aux(acc: List[Element], rest: List[PseudoHtml]): List[Element] = rest match {
      case Nil                        => acc
      case (_: Text) :: xs            => aux(acc, xs)
      case (e: Element) :: xs if f(e) => aux(e :: acc, xs ::: e.children)
      case (e: Element) :: xs         => aux(acc, xs ::: e.children)
    }
    aux(Nil, List(this))
  }

  def byAttribute(name: String, f: String => Boolean): List[Element] =
    findElement(_.attributes.get(name).exists(f))

  def byAttrEquals(name: String, value: String): List[Element] =
    byAttribute(name, _ == value)

  def byClass(clazz: String): List[Element] =
    byAttribute("class", _.indexOf(clazz) > -1)

  def byName(name: String): List[Element] =
    byAttribute("name", _ == name)

  def firstByTag(tagName: String): Option[Element] = {
    def loop(node: PseudoHtml): Option[Element] = node match {
      case element: Element if element.tagName == tagName =>
        Some(element)
      case element: Element =>
        element.children.foldLeft(Option.empty[Element]) {
          case (found @ Some(_), _) => found
          case (None, child)        => loop(child)
        }
      case _: Text =>
        None
    }
    loop(this)
  }

  def byTag(tagName: String): List[PseudoHtml] =
    findElement(_.tagName == tagName)

  def mkString: String = {
    def aux(node: PseudoHtml, ident: String): String = node match {
      case Text(id, value) => s"$ident$value <!-- ${id.mkString} -->"
      case Element(id, _, tagName, attributes, styles, children) =>
        val renderedAttributes = attributes.map { case (k, v) => s"""$k="$v"""" }
          .mkString(" ")
        val renderedStyles = if (styles.nonEmpty) {
          val s = styles.map { case (k, v) => s"""$k: $v""" }
            .mkString("; ")
          s""" style="$s""""
        } else {
          " "
        }
        val renderedChildren = children.map(aux(_, ident + "  ")).mkString("\n")
        s"$ident<$tagName$renderedStyles$renderedAttributes> <!-- ${id.mkString} -->\n$renderedChildren\n$ident</$tagName>"
    }
    aux(this, "")
  }

  def text: String
}

object PseudoHtml {

  case class Element(
    id: Id,
    ns: XmlNs,
    tagName: String,
    attributes: Map[String, String],
    styles: Map[String, String],
    children: List[PseudoHtml]
  ) extends PseudoHtml {

    lazy val text: String =
      children.foldLeft("")(_ + _.text)
  }

  case class Text(id: Id, value: String) extends PseudoHtml {
    val text: String = value
  }

  private class PseudoDomRenderContext[F[_], S, M] extends RenderContext[Binding[F, S, M]] {

    val idBuilder                               = new IdBuilder(256)
    var currentChildren: List[List[PseudoHtml]] = List(Nil)
    var currentNode                             = List.empty[(XmlNs, String)]
    var currentAttrs                            = List.empty[(XmlNs, String, String)]
    var currentStyles                           = List.empty[(String, String)]

    var elements = List.empty[(avocet.Id, ElementId)]
    var events   = List.empty[(EventId, Context.Event[F, S, M])]

    def openNode(xmlns: XmlNs, name: String): Unit = {
      currentNode = (xmlns, name) :: currentNode
      currentChildren = Nil :: currentChildren
      currentAttrs = Nil
      currentStyles = Nil
      idBuilder.incId()
      idBuilder.incLevel()
    }

    def closeNode(name: String): Unit = {
      idBuilder.decLevel()
      val (xmlns, currentNodeTail) = currentNode match {
        case (ns, _) :: tail => (ns, tail)
        case Nil             => throw new IllegalStateException("No open node to close")
      }
      val (children, currentChildrenTail) = currentChildren match {
        case head :: tail => (head, tail)
        case Nil          => throw new IllegalStateException("Missing children stack")
      }
      val (c2, cct2) = currentChildrenTail match {
        case head :: tail => (head, tail)
        case Nil          => throw new IllegalStateException("Missing parent children stack")
      }
      val node = PseudoHtml.Element(
        id = idBuilder.mkId,
        ns = xmlns,
        tagName = name,
        attributes = currentAttrs.map(x => (x._2, x._3)).toMap,
        styles = currentStyles.toMap,
        children = children.reverse
      )
      currentNode = currentNodeTail
      currentChildren = (node :: c2) :: cct2
    }

    def setAttr(xmlNs: XmlNs, name: String, value: String): Unit =
      currentAttrs = (xmlNs, name, value) :: currentAttrs

    def setStyle(name: String, value: String): Unit =
      currentStyles = (name, value) :: currentStyles

    def addTextNode(text: String): Unit = {
      idBuilder.incId()
      currentChildren match {
        case children :: xs =>
          val updatedChildren = PseudoHtml.Text(idBuilder.mkId, text) :: children
          currentChildren = updatedChildren :: xs
        case Nil =>
          throw new IllegalStateException("Missing current node for text")
      }
    }

    def addMisc(misc: Binding[F, S, M]): Unit = {
      idBuilder.decLevel()
      misc match {
        case ComponentEntry(c, p, _) =>
          val rc = this.asInstanceOf[RenderContext[Context.Binding[F, Any, Any]]]
          c.initialState match {
            case Right(s) => c.render(p, s).apply(rc)
            case Left(_)  => c.renderNoState(p).apply(rc)
          }
        case elementId: ElementId =>
          elements = (idBuilder.mkId, elementId) :: elements
        case event: Context.Event[_, _, _] =>
          val eventId = EventId(idBuilder.mkId, event.`type`, event.phase)
          events = (eventId, event.asInstanceOf[Context.Event[F, S, M]]) :: events
        case _: Context.Delay[_, _, _] =>
          ()
      }
      idBuilder.incLevel()
    }
  }

  case class RenderingResult[F[_], S, M](
    pseudoDom: PseudoHtml,
    elements: Map[avocet.Id, ElementId],
    events: Map[EventId, Context.Event[F, S, M]]
  )

  def render[F[_], S, M](node: Node[Binding[F, S, M]]): RenderingResult[F, S, M] = {

    val rc = new PseudoDomRenderContext[F, S, M]()
    node(rc)
    val top = rc.currentChildren match {
      case (value :: _) :: _ => value
      case _                 => throw new IllegalStateException("Rendered pseudo DOM is empty")
    }
    RenderingResult(top, rc.elements.toMap, rc.events.toMap)
  }

}
