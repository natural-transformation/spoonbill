package spoonbill.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BrowserPersistenceUsageSpec extends AnyFlatSpec with Matchers {
  "Browser capacity warnings" should "separate retained and active limits at exact thresholds" in {
    val limits = BrowserPersistenceLimits(
      retainedBindings = 5,
      retainedViews = 10,
      retainedCeremonies = 10,
      deliveryMaterials = 10,
      activeViewsPerBinding = 5,
      ceremoniesPerBinding = 5
    )
    val usage = BrowserPersistenceUsage(4, 7, 1, 10, 0, 1, 4, limits)
    usage.warnings().map(_.resource) shouldBe Vector("retainedBindings", "retainedCeremonies", "ceremoniesPerBinding")
    usage.warnings().filter(_.exhausted).map(_.resource) shouldBe Vector("retainedCeremonies")
    usage.warnings(100).map(_.resource) shouldBe Vector("retainedCeremonies")
    usage.copy(activeViews = 0, maxActiveViewsPerBinding = 0).warnings() shouldBe usage.warnings()
    intercept[IllegalArgumentException](usage.warnings(0))
    intercept[IllegalArgumentException](usage.warnings(101))
  }

  it should "round small limits upward and handle large limits without overflow" in {
    val limits = BrowserPersistenceLimits(retainedBindings = Int.MaxValue, retainedViews = 3)
    val usage  = BrowserPersistenceUsage(Int.MaxValue.toLong, 2, 0, 0, 0, 0, 0, limits)
    usage.warnings().map(_.resource) shouldBe Vector("retainedBindings")
    usage.copy(retainedViews = 3).warnings().map(_.resource) should contain("retainedViews")
  }
}
