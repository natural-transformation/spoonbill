/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.server.internal

import spoonbill.Context
import spoonbill.Context._
import spoonbill.effect.Effect
import avocet.{IdBuilder, RenderContext, XmlNs}
import avocet.impl.TextPrettyPrintingConfig

private[spoonbill] final class Html5RenderContext[F[_]: Effect, S, M](presetId: Boolean)
    extends avocet.impl.Html5RenderContext[Binding[F, S, M]](TextPrettyPrintingConfig.noPrettyPrinting) {

  private lazy val idb = IdBuilder()
  private var pendingId: Option[(XmlNs, String)] = None

  // Avocet emits styles before attributes. Keep the preset id pending until
  // the element's styles are complete, without changing its allocated id.
  private def flushPresetId(): Unit = {
    pendingId.foreach { case (xmlns, value) => super.setAttr(xmlns, "k", value) }
    pendingId = None
  }

  override def addMisc(misc: Binding[F, S, M]): Unit = misc match {
    case ComponentEntry(component, parameters, _) =>
      val rc = this.asInstanceOf[RenderContext[Context.Binding[F, Any, Any]]]
      // Static pages always made from scratch
      component.initialState match {
        case Right(state) => component.render(parameters, state).apply(rc)
        case Left(_)      => component.renderNoState(parameters).apply(rc)
      }
    case _ => ()
  }

  override def openNode(xmlns: XmlNs, name: String): Unit = {
    flushPresetId()
    if (presetId) idb.incId()
    super.openNode(xmlns, name)
    if (presetId) {
      pendingId = Some((xmlns, idb.mkId.toList.last.toString))
      idb.incLevel()
    }
  }

  override def closeNode(name: String): Unit = {
    flushPresetId()
    super.closeNode(name)
    if (presetId) idb.decLevel()
  }

  override def addTextNode(text: String): Unit = {
    flushPresetId()
    if (presetId) idb.incId()
    super.addTextNode(text)
  }

  override def setAttr(xmlns: XmlNs, name: String, value: String): Unit = {
    flushPresetId()
    super.setAttr(xmlns, name, value)
  }
}
