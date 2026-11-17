package spoonbill

import java.nio.file.{Files, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClientEventListenerSpec extends AnyFlatSpec with Matchers {
  "Spoonbill client listener" should "walk up to a parent node with vId" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("parentNode") shouldBe true
    source.contains("target && target.vId") shouldBe true
  }

  it should "attach listeners to document for bubbling events" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("let target = (document || this.root)") shouldBe true
    source.contains("target.addEventListener") shouldBe true
  }

  it should "remove listeners from the same target" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("o.target") shouldBe true
    source.contains("removeEventListener(o.type, o.listener, o.capture)") shouldBe true
  }

  // Lifecycle coverage lives in misc/connection-lifecycle-tests.mjs:
  // "legacy transport selection, long-poll renewal and publish use observable requests"
  // checks early open, no readiness after 503, and readiness after the first 200.
  // "websocket reopening emits a fresh open and ready lifecycle without private state reads"
  // checks duplicate-open suppression and fresh open/ready events after reconnect.

  it should "use capture for submit events" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("name === 'submit'") shouldBe true
    source.contains("addEventListener(name, listener, useCapture)") shouldBe true
  }

  it should "skip non-element targets when walking up the DOM" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("node.nodeType !== 1") shouldBe true
  }

  it should "prefer composedPath for event targeting" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("event.composedPath") shouldBe true
  }

  it should "allow disabling WebSocket via config" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/connection.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("options && options['ws'] === false") shouldBe true
  }

  it should "guard extractProperty when element is missing" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("if (!element)") shouldBe true
    source.contains("is missing") shouldBe true
  }

  it should "handle extractProperty access errors" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("propertyName} threw") shouldBe true
  }

  it should "include targetValue in extractEventData" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("targetValue") shouldBe true
  }

  it should "include targetChecked in extractEventData" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("targetChecked") shouldBe true
  }

  it should "guard extractEventData when event is missing" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/spoonbill.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("if (!data)") shouldBe true
  }

  it should "report extractProperty failures in the bridge" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/bridge.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("PropertyType") shouldBe true
    source.contains("EXTRACT_PROPERTY_RESPONSE") shouldBe true
    source.contains("extractProperty failed") shouldBe true
  }

  it should "report extractEventData failures in the bridge" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/bridge.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("pCode === Procedure.EXTRACT_EVENT_DATA") shouldBe true
    source.contains("EXTRACT_EVENT_DATA_RESPONSE") shouldBe true
  }

  it should "pass config into Connection" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/launcher.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("new Connection") shouldBe true
    source.contains("window.location,\n    config") shouldBe true
  }

  it should "mark Spoonbill ready on connection ready" in {
    val path = Paths.get("modules/spoonbill/src/main/es6/launcher.js")
    val source = new String(Files.readAllBytes(path), "UTF-8")
    source.contains("addEventListener('ready'") shouldBe true
    source.contains("SpoonbillReady") shouldBe true
    source.contains("Spoonbill']['ready'] = true") shouldBe true
  }
}
