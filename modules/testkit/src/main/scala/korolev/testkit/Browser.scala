package spoonbill.testkit

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.NoSuchElementException
import spoonbill.{Context, Qsid, Transition, TransitionAsync}
import spoonbill.Context.{Access, BaseAccessDefault, Binding, ElementId}
import spoonbill.data.Bytes
import spoonbill.effect.Effect
import spoonbill.effect.Stream
import spoonbill.effect.syntax.*
import spoonbill.internal.Frontend.ClientSideException
import spoonbill.util.JsCode
import spoonbill.web.FormData
import org.graalvm.polyglot.HostAccess
import scala.collection.immutable.Seq
import scala.collection.mutable

case class Browser(
  properties: Map[(ElementId, String), String] = Map.empty,
  forms: Map[ElementId, FormData] = Map.empty,
  filesMap: Map[ElementId, Map[String, Array[Byte]]] = Map.empty,
  jsMocks: List[String] = Nil
) {

  def property(id: ElementId, name: String, value: String): Browser =
    copy(properties = properties + ((id -> name, value)))

  def value(id: ElementId, value: String): Browser =
    property(id, "value", value)

  def form(id: ElementId, data: FormData): Browser =
    copy(forms = forms + (id -> data))

  def mockJs(script: String): Browser =
    copy(jsMocks = script :: jsMocks)

  def form(id: ElementId, fields: (String, String)*): Browser = {
    val entries = fields map { case (k, v) =>
      val data = ByteBuffer.wrap(v.getBytes(StandardCharsets.UTF_8))
      FormData.Entry(k, data, Nil)
    }
    copy(forms = forms + ((id, FormData(entries))))
  }

  /**
   * Add `file` to `id`.
   */
  def file(id: ElementId, file: (String, Array[Byte])): Browser =
    filesMap.get(id) match {
      case None             => copy(filesMap = filesMap + (id -> Map(file)))
      case Some(knownFiles) => copy(filesMap = filesMap + (id -> (knownFiles + file)))
    }

  /**
   * Set files at `id` to `filesList`.
   */
  def files(id: ElementId, filesList: (String, Array[Byte])*): Browser =
    copy(filesMap = filesMap + (id -> Map(filesList: _*)))

  /**
   * Simulate event propagation on the given DOM.
   *
   * @example
   * {{{
   * @example
   *   {{{
   *
   * def onClick(access: Access) = ???
   *
   * val dom = body( div("Hello world"), button( event("click")(onClick), name
   * := "my-button", "Click me" ) )
   *
   * val actions = Browser.event( state = myInitialState, dom = dom, event =
   * "click" target = _.byName("by-button").headOption, ) }}}
   * @see
   *   [[access]]
   */
  def event[F[_]: Effect, S, M](
    state: S,
    dom: avocet.Document.Node[Binding[F, S, M]],
    event: String,
    target: PseudoHtml => Option[avocet.Id],
    eventData: String = ""
  ): F[Seq[Action[F, S, M]]] = {

    this.event[F, S, M](
      state = state,
      dom = dom,
      event = event,
      target = (pseudoHtml: PseudoHtml, _: Map[avocet.Id, ElementId]) => target(pseudoHtml),
      eventData = eventData
    )

  }

  /**
   * Simulate event propagation on the given DOM with access to the rendered
   * Avocet-to-ElementId mapping.
   */
  def event[F[_]: Effect, S, M](
    state: S,
    dom: avocet.Document.Node[Binding[F, S, M]],
    event: String,
    target: (PseudoHtml, Map[avocet.Id, ElementId]) => Option[avocet.Id],
    eventData: String
  ): F[Seq[Action[F, S, M]]] = {

    val rr = PseudoHtml.render(dom)

    def collectActions(targetId: avocet.Id, eventType: String): F[Seq[Action[F, S, M]]] = {
      val propagation = avocet.events.calculateEventPropagation(targetId, eventType)

      Effect[F]
        .sequence {
          // (continue propagation, list of batches of actions)
          val (_, actions) = propagation.foldLeft((true, List.empty[F[Seq[Action[F, S, M]]]])) {
            case (continue @ (false, _), _) => continue
            case (continue @ (true, acc), eventId) =>
              rr.events.get(eventId) match {
                case None => continue
                case Some(event) =>
                  val actionsF = access(state, event.effect, eventData, rr.elements)
                  (!event.stopPropagation, actionsF :: acc)
              }
          }
          actions
        }
        .map(_.flatten)
    }

    def findElementPath(
      node: PseudoHtml,
      targetId: avocet.Id
    ): Option[(PseudoHtml.Element, List[PseudoHtml.Element])] = {
      def loop(
        current: PseudoHtml,
        parents: List[PseudoHtml.Element]
      ): Option[(PseudoHtml.Element, List[PseudoHtml.Element])] = current match {
        case element: PseudoHtml.Element =>
          if (element.id == targetId) {
            Some((element, parents))
          } else {
            element.children.foldLeft(Option.empty[(PseudoHtml.Element, List[PseudoHtml.Element])]) {
              case (found @ Some(_), _) => found
              case (None, child)        => loop(child, element :: parents)
            }
          }
        case _ =>
          None
      }
      loop(node, Nil)
    }

    def isSubmitButton(element: PseudoHtml.Element): Boolean = {
      val tagName = element.tagName.toLowerCase
      tagName match {
        case "button" =>
          element.attributes
            .get("type")
            .forall(t => t.toLowerCase != "button" && t.toLowerCase != "reset")
        case "input" =>
          element.attributes
            .get("type")
            .exists(_.toLowerCase == "submit")
        case _ =>
          false
      }
    }

    def findSubmitForm(targetId: avocet.Id): Option[avocet.Id] =
      findElementPath(rr.pseudoDom, targetId).flatMap { case (targetElement, parents) =>
        if (isSubmitButton(targetElement)) {
          parents.find(_.tagName == "form").map(_.id)
        } else {
          None
        }
      }

    target(rr.pseudoDom, rr.elements).fold(Effect[F].pure(Seq.empty[Action[F, S, M]])) { targetId =>
      val clickActionsF = collectActions(targetId, event)
      if (event != "click") {
        clickActionsF
      } else {
        findSubmitForm(targetId) match {
          case None => clickActionsF
          case Some(formId) =>
            clickActionsF.flatMap { clickActions =>
              // Emulate browser default: submit forms on submit button click.
              collectActions(formId, "submit").map(clickActions ++ _)
            }
        }
      }
    }
  }

  /**
   * Simulate event propagation targeting a Spoonbill ElementId directly.
   */
  def eventByElementId[F[_]: Effect, S, M](
    state: S,
    dom: avocet.Document.Node[Binding[F, S, M]],
    event: String,
    targetElementId: ElementId,
    eventData: String = ""
  ): F[Seq[Action[F, S, M]]] =
    this.event[F, S, M](
      state = state,
      dom = dom,
      event = event,
      target = (_: PseudoHtml, elementMap: Map[avocet.Id, ElementId]) =>
        elementMap.collectFirst {
          case (domId, elementId) if elementId == targetElementId => domId
        },
      eventData = eventData
    )

  /**
   * Applies `f` to the Browser using [[Context.Access]].
   * @param elements
   *   evalJs uses this mapping to search elements on the client side.
   */
  def access[F[_]: Effect, S, M](
    initialState: S,
    f: Access[F, S, M] => F[Unit],
    eventData: String = "",
    elements: Map[avocet.Id, ElementId] = Map.empty
  ): F[Seq[Action[F, S, M]]] = {

    val ed           = eventData
    val actions      = mutable.Buffer.empty[Action[F, S, M]]
    var browser      = this
    var currentState = initialState
    val stub = new BaseAccessDefault[F, S, M] {

      def property(id: ElementId): Context.PropertyHandler[F] =
        new Context.PropertyHandler[F] {
          def get(propName: String): F[String] =
            Effect[F].pure(browser.properties(id -> propName))
          def set(propName: String, value: Any): F[Unit] =
            Effect[F].delay {
              browser = browser.property(id, propName, value.toString)
              actions += Action.PropertySet(id, propName, value.toString)
            }
        }

      def focus(id: ElementId): F[Unit] =
        Effect[F].delay(actions += Action.Focus(id))

      def resetForm(id: ElementId): F[Unit] =
        Effect[F].delay(actions += Action.ResetForm(id))

      def state: F[S] = Effect[F].delay(currentState)

      def transition(f: Transition[S]): F[Unit] =
        Effect[F].delay {
          currentState = f(currentState)
          actions += Action.Transition(currentState)
        }

      def transitionAsync(f: TransitionAsync[F, S]): F[Unit] =
        f(currentState).map { newState =>
          currentState = newState
          actions += Action.Transition(newState)
        }

      def transitionForce(f: Transition[S]): F[Unit] =
        transition(f)

      def transitionForceAsync(f: TransitionAsync[F, S]): F[Unit] =
        transitionAsync(f)

      def sessionId: F[Qsid] = Effect[F].pure(Qsid("test-device", "test-session"))

      def eventData: F[String] = Effect[F].pure(ed)

      def publish(message: M): F[Unit] = Effect[F].delay(actions += Action.Publish(message))

      def downloadFormData(id: ElementId): F[FormData] =
        Effect[F].delay(browser.forms(id))

      def downloadFiles(id: ElementId): F[List[(Context.FileHandler, Bytes)]] =
        Effect[F].delay {
          browser.filesMap(id).toList map { case (name, bytes) =>
            Context.FileHandler(name, bytes.length.toLong)(id) -> Bytes.wrap(bytes)
          }
        }

      def downloadFilesAsStream(id: ElementId): F[List[(Context.FileHandler, Stream[F, Bytes])]] =
        Effect[F].sequence {
          browser.filesMap(id).toList map { case (name, ba) =>
            val bytes = Bytes.wrap(ba)
            val h     = Context.FileHandler(name, ba.length.toLong)(id)
            Stream(bytes).mat().map { s =>
              (h, s)
            }
          }
        }

      def downloadFileAsStream(handler: Context.FileHandler): F[Stream[F, Bytes]] =
        Effect[F].delayAsync {
          val bytesOpt = browser.filesMap.collectFirst {
            Function.unlift { case (_, files) =>
              files.get(handler.fileName)
            }
          }
          bytesOpt match {
            case None => Effect[F].fail(new NoSuchElementException(handler.fileName))
            case Some(ba) =>
              val bytes = Bytes.wrap(ba)
              Stream(bytes).mat()
          }
        }

      def listFiles(id: ElementId): F[List[Context.FileHandler]] =
        Effect[F].delay {
          browser.filesMap(id).toList map { case (name, bytes) =>
            Context.FileHandler(name, bytes.length.toLong)(id)
          }
        }

      def uploadFile(name: String, stream: Stream[F, Bytes], size: Option[Long], mimeType: String): F[Unit] =
        Effect[F].unit

      def evalJs(code: JsCode): F[String] = Effect[F]
        .promise[String] { cb =>

          import org.graalvm.polyglot.{Context => GraalContext}

          // TODO prevent reparsing
          // TODO handle system errors

          val context   = GraalContext.create()
          val finalCode = code.mkString(elements.map(_.swap))
          val bindings  = context.getBindings("js")
          val handler   = new Browser.Handler(actions, cb)

          bindings.putMember("code", finalCode)
          bindings.putMember("handler", handler)

          // Preserve JS template literals by escaping Scala interpolation.
          context.eval(
            "js",
            jsMocks.mkString("\n") + s"""
              var result;
              var status = 0;
              try {
                result = eval(code);
              } catch (e) {
                console.error(`Error evaluating code $${code}`, e);
                result = e;
                status = 1;
              }

              if (result instanceof Promise) {
                result.then(
                  (res) => handler.result(JSON.stringify(res)),
                  (err) => {
                    console.error(`Error evaluating code $${code}`, err);
                    handler.error(err.toString())
                  }
                );
              } else {
                var resultString;
                if (status === 1) {
                  resultString = result.toString();
                  handler.error(resultString);
                } else {
                  resultString = JSON.stringify(result);
                  handler.result(resultString);
                }
              }
            """
          )
          ()
        }

      def registerCallback(name: String)(f: String => F[Unit]): F[Unit] =
        Effect[F].delay {
          actions += Action.RegisterCallback(name, f)
        }
    }

    f(stub).map(_ => actions.toSeq)
  }

}

object Browser {

  class Handler[F[_], S, M](actions: mutable.Buffer[Action[F, S, M]], cb: Either[Throwable, String] => Unit) {
    @HostAccess.Export
    def result(value: String): Unit = {
      val result = Right(value)
      actions += Action.EvalJs(result)
      cb(result)
    }
    @HostAccess.Export
    def error(value: String): Unit = {
      val result = Left(ClientSideException(value))
      actions += Action.EvalJs(result)
      cb(result)
    }
  }
}
