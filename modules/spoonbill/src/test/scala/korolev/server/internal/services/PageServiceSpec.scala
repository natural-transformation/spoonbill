package spoonbill.server.internal.services

import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.server.{SpoonbillServiceConfig, StateLoader}
import spoonbill.server.internal.Html5RenderContext
import spoonbill.testExecution.defaultExecutor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

class PageServiceSpec extends AnyFlatSpec with Matchers {

  private def mkConfig(protocolsEnabled: Boolean) =
    SpoonbillServiceConfig[Future, Unit, Unit](
      stateLoader = StateLoader.default(()),
      webSocketProtocolsEnabled = protocolsEnabled
    )

  "appendScripts" should "include wsp flag when protocols are disabled" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val service = new PageService[Future, Unit, Unit](mkConfig(protocolsEnabled = false))
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = false)

    service.appendScripts(rc, Qsid("device", "session"))

    rc.mkString should include("wsp:false")
  }

  it should "omit wsp flag when protocols are enabled" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val service = new PageService[Future, Unit, Unit](mkConfig(protocolsEnabled = true))
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = false)

    service.appendScripts(rc, Qsid("device", "session"))

    rc.mkString should not include "wsp:false"
  }
}
