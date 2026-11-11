package spoonbill.server

import avocet.RendererCollisionWitnesses as W
import avocet.dsl.*
import avocet.dsl.html.*
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import spoonbill.{Component, Context}
import spoonbill.state.javaSerialization.*

/**
 * Synthetic pages for the F2 real-service renderer fixture.
 *
 * Every page has the same frame: the scenario container is the first body
 * child (`1_2_1`), followed by the `advance` (`1_2_2`) and `current`
 * (`1_2_3`) buttons. State is the stage: 0 is the server-rendered page and 1
 * is the page after one ordinary `advance` click. The browser runner
 * hard-codes the server ids derived from these trees, so a change here must
 * be matched in `misc/renderer-acceptance-cases.cjs`.
 */
object RendererAcceptanceDocuments {

  val scenarios: Set[String] = Set("long-names", "collisions", "encoding", "first-patch")

  /** Handler invocations observed by the service; read through the status control. */
  final class Counters {
    val advance   = new AtomicInteger()
    val previous  = new AtomicInteger()
    val current   = new AtomicInteger()
    val component = new AtomicInteger()
    def json: String =
      s""""advance":${advance.get()},"previous":${previous.get()},"current":${current.get()},"component":${component.get()}"""
  }

  // Former Avocet 1.x fixed-header limits: 10-char tags, 29-char attribute names.
  private val filler = "abcdefghijklmnopqrstuvwxyz0123456789"

  /** A lowercase name of exactly `length` chars that starts with `stem` and ends with `last`. */
  def longName(stem: String, length: Int, last: Char): String = {
    val body = stem + Iterator.continually(filler).flatten.take(length - stem.length - 1).mkString
    body + last
  }

  /** Long legal names. Pairs share their first 10 (tag) or 29 (attribute) characters. */
  object Names {
    val tagA      = "f2-element-a" // 12 chars, shares 10 with tagB
    val tagB      = "f2-element-b"
    val tag64     = longName("f2-tag-", 64, 'z')
    val tag1024   = longName("f2-tag-", 1024, 'a')
    val tag1024b  = longName("f2-tag-", 1024, 'b')
    val attrA     = longName("data-f2-attribute-prefix-", 29, 'x') + "-a" // 31 chars, shares 29 with attrB
    val attrB     = longName("data-f2-attribute-prefix-", 29, 'x') + "-b"
    val attr256   = longName("data-f2-", 256, 'a')
    val attr256b  = longName("data-f2-", 256, 'b')
    val attr1024  = longName("data-f2-", 1024, 'a')
    val attr1024b = longName("data-f2-", 1024, 'b')

    /** Long names the injected truncation fault shortens to the former limits. */
    val truncatable: Vector[(String, Int)] =
      Vector(tag1024b -> 10, attrA -> 29, attr256b -> 29, attr1024 -> 29, attr1024b -> 29)

    def json: String =
      Vector(
        "tagA"      -> tagA,
        "tagB"      -> tagB,
        "tag64"     -> tag64,
        "tag1024"   -> tag1024,
        "tag1024b"  -> tag1024b,
        "attrA"     -> attrA,
        "attrB"     -> attrB,
        "attr256"   -> attr256,
        "attr256b"  -> attr256b,
        "attr1024"  -> attr1024,
        "attr1024b" -> attr1024b
      ).map { case (key, value) => s""""$key":"$value"""" }.mkString("{", ",", "}")
  }

  /**
   * Quote-, delimiter- and markup-bearing data. A value that escaped its attribute,
   * declaration or text position would add an attribute, a style property, an
   * element or a handler that sets `window.__f2Injected`.
   */
  object Encoded {
    val title0 = "a\"b'c<d>e&f=g;h:i/j\\k onclick=\"window.__f2Injected='attr'\"\n\ttab é☃"
    val title1 = "x\" onmouseover=\"window.__f2Injected='patch'\" y='z' </div><script>window.__f2Injected='script'</script>&amp;&lt;"
    // U+2028 is valid in JSON but was a line terminator in older JavaScript string literals.
    val data0  = "{\"a\":\"b\\\"c\",\"on\":\"x\\\\y\",\"u\":\"" + 0x2028.toChar + "\"}"
    val data1  = """[1,"two",{"three":"'4'"}]"""
    val delims0 = "\"a;b:c{d}e\""
    val delims1 = "'x;y:z'"
    val inject0 = "x\" onmouseover=\"window.__f2Injected='style'"
    val inject1 = "y\" onfocus=\"window.__f2Injected='patch-style'"
    val angle   = "\"</div><img src=x onerror=window.__f2Injected='angle'>\""
    val content0 = "\"a;b\""
    val content1 = "\"c;d\""
    val text0  = "<b>bold?</b> & \"quoted\" 'single' </p><script>window.__f2Injected='text'</script>"
    val text1  = "<i>still text</i> &lt; ]]> --> <!-- x"
  }

  private val context = Context[Future, Int, Any]
  import context.*

  private def frame(counters: Counters, stage: Int, scenario: Node): Node =
    Html(
      head(title("F2 renderer acceptance")),
      body(
        scenario,
        button(
          id := "advance",
          "Advance",
          event("click") { access =>
            counters.advance.incrementAndGet()
            access.transition(_ => 1)
          }
        ),
        button(
          id := "current",
          "Current",
          // The patch replaces this handler without touching the button.
          if (stage == 0) event("click") { _ => counters.previous.incrementAndGet(); Future.unit }
          else event("click") { _ => counters.current.incrementAndGet(); Future.unit }
        )
      )
    )

  private def longNames(stage: Int): Node = {
    import Names.*
    div(
      id := "long-names",
      TagDef(tagA)(id := "ln-a", AttrDef(attrA) := s"a$stage", AttrDef(attrB) := "b0", "text-a"),
      TagDef(tagB)(id := "ln-b", AttrDef(attrA) := "a0", "text-b"),
      if (stage == 0) TagDef(tag64)(id := "ln-64", AttrDef(attr256) := "v256")
      else TagDef(tag64)(id := "ln-64", AttrDef(attr256b) := "v256b"),
      TagDef(tag1024)(id := "ln-1024", AttrDef(attr1024) := s"v$stage", s"text-1024-$stage"),
      when(stage == 1)(TagDef(tag1024b)(id := "ln-new", AttrDef(attr1024b) := "created"))
    )
  }

  private def collisions(stage: Int): Node = {
    def pick(a: String, b: String): String = if (stage == 0) a else b
    div(
      id := "collisions",
      TagDef(pick(W.nameA, W.nameB))(id := "co-tag", "tag"),
      div(id := "co-attr", AttrDef(pick(W.nameA, W.nameB)) := "1"),
      div(id := "co-text", pick(W.valueA, W.valueB)),
      div(id := "co-class", clazz := pick(W.valueA, W.valueB)),
      div(id := "co-style", StyleDef("--f2-collision") @= pick(W.valueA, W.valueB)),
      section(id := "co-subtree", div(pick(W.subtreeTextA, W.subtreeTextB))),
      p(id := "co-after", "after")
    )
  }

  private def encoding(stage: Int): Node = {
    import Encoded.*
    def pick(a: String, b: String): String = if (stage == 0) a else b
    div(
      id := "encoding",
      div(id := "en-attr", title := pick(title0, title1), AttrDef("data-f2") := pick(data0, data1), "attr host"),
      div(
        id := "en-style",
        StyleDef("--f2-delims") @= pick(delims0, delims1),
        StyleDef("--f2-inject") @= pick(inject0, inject1),
        StyleDef("--f2-angle") @= angle,
        StyleDef("content") @= pick(content0, content1),
        "style host"
      ),
      // Same-name style and attribute: only the style changes here, only the attribute below.
      canvas(id := "en-same-style", width @= pick("10px", "11px"), AttrDef("width") := "20"),
      canvas(id := "en-same-attr", width @= "10px", AttrDef("width") := pick("20", "21")),
      p(id := "en-text", pick(text0, text1)),
      p(id := "en-after", "after")
    )
  }

  // A component boundary whose subtree the patch must leave intact.
  private val boundary = Component[Future, Int, String, Any](0) { (scope, label, _) =>
    import scope.*
    div(
      id          := "fp-component",
      borderColor @= "rgb(4, 5, 6)",
      p(id := "fp-component-child", s"component $label"),
      button(
        id := "fp-component-button",
        "Component",
        event("click") { _ => activeCounters.get().foreach(_.component.incrementAndGet()); Future.unit }
      )
    )
  }

  // The component is shared by runs, which are sequential in this JVM.
  private val activeCounters = new java.util.concurrent.atomic.AtomicReference[Option[Counters]](None)

  private def firstPatch(stage: Int): Node =
    div(
      id := "first-patch",
      div(
        id               := "fp-styled",
        color            @= "rgb(1, 2, 3)",
        StyleDef("--f2-parent") @= "kept",
        AttrDef("data-role") := "styled-parent",
        span(id := "fp-target", if (stage == 0) "before" else "after"),
        span(id := "fp-keep", "keep")
      ),
      // A text node after an element sibling takes part in id numbering.
      if (stage == 0) "loose text" else "loose text patched",
      boundary("boundary"),
      p(id := "fp-later", "later sibling"),
      p(id := "fp-last", AttrDef("data-stage") := stage.toString, "last")
    )

  def document(scenario: String, run: Counters)(stage: Int): Node = {
    activeCounters.set(Some(run))
    val content = scenario match {
      case "long-names"  => longNames(stage)
      case "collisions"  => collisions(stage)
      case "encoding"    => encoding(stage)
      case "first-patch" => firstPatch(stage)
    }
    frame(run, stage, content)
  }
}
