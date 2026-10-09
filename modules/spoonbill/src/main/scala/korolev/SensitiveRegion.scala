package spoonbill

import avocet.{Document, XmlNs}
import spoonbill.sensitive.RegionId

/** Empty renderer-owned host. Plaintext belongs to a transient closed shadow
  * root in the browser, never to this render node or a framework snapshot.
  */
object SensitiveRegion {
  def apply[B](region: RegionId): Document.Node[B] = Document.Node { rc =>
    rc.openNode(XmlNs.html, "sb-secret")
    rc.setAttr(XmlNs.html, "data-sb-region", region.value)
    rc.setAttr(XmlNs.html, "role", "region")
    rc.setAttr(XmlNs.html, "aria-label", "Sensitive information")
    rc.closeNode("sb-secret")
  }
}
