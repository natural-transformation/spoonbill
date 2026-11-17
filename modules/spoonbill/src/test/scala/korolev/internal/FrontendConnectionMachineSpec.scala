package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class FrontendConnectionMachineSpec extends AnyFlatSpec with Matchers {
  import FrontendConnectionMachine.*

  "Frontend connection" should "publish one baseline and contiguous revisions only while live" in {
    canPublish(initial, false) shouldBe false
    canPublish(initial, true) shouldBe true
    val baseline = published(initial, true)
    baseline.view shouldBe View.Published(0L)
    canPublish(baseline, true) shouldBe false
    canPublish(baseline, false) shouldBe true
    val patch = published(baseline, false)
    patch.view shouldBe View.Published(1L)
    revision(initial, 0L) shouldBe Revision.Denied
    revision(patch, -1L) shouldBe Revision.Denied
    revision(patch, 2L) shouldBe Revision.Denied
    revision(patch, 0L) shouldBe Revision.Obsolete
    revision(patch, 1L) shouldBe Revision.Current
    canPublish(patch.copy(view = View.Published(maxCounter)), false) shouldBe false
    canPublish(close(patch), false) shouldBe false
    canPublish(replace(patch).get, false) shouldBe false
    published(close(patch), false).status shouldBe Status.Closed
    published(published(patch, false), false).view shouldBe View.Published(3L)
  }

  it should "preserve replacement identity across either close ordering" in {
    val replacement = replace(initial).get
    live(replacement) shouldBe false
    closed(replacement) shouldBe false
    replace(replacement) shouldBe None
    close(replacement) shouldBe replace(close(initial)).get
    replacing(close(replacement)) shouldBe true
    closed(close(replacement)) shouldBe true
    close(close(initial)) shouldBe close(initial)
  }

  it should "keep navigation, observed departure and handled departure independent" in {
    val departed = depart(navigate(initial), 9L)
    departed.navigation shouldBe 1L
    departed.departure shouldBe 9L
    depart(departed, 3L) shouldBe departed
    val handled = handleDeparture(departed, 5L).get
    handled.departure shouldBe 9L
    handled.handledDeparture shouldBe 5L
    handleDeparture(handled, 5L) shouldBe None
    navigate(close(handled)).status shouldBe Status.Closed
  }

  "Frontend close ownership" should "produce the ordered cleanup commands only for the first claim" in {
    import FrontendCloseMachine.*
    val (claimed, steps) = FrontendCloseMachine.close(FrontendCloseMachine.State.Unclaimed)
    steps shouldBe List(
      Command.FailActions,
      Command.CancelDownloads,
      Command.CloseStrings,
      Command.CloseForms,
      Command.CloseFiles,
      Command.CloseFileNames,
      Command.CloseSensitiveOutcomes,
      Command.CloseSensitiveRuntime,
      Command.CancelOutput,
      Command.CancelActions,
      Command.CloseRacks,
      Command.CancelInput
    )
    FrontendCloseMachine.close(claimed) shouldBe (claimed -> Nil)
  }

  "Frontend pending registry" should "detach once, reject late ownership, and preserve a replaced key" in {
    val registry = new FrontendPendingRegistry[String, String]
    registry.put("one", "first") shouldBe true
    registry.put("one", "replacement") shouldBe true
    registry.put("two", "second") shouldBe true
    registry.remove("one") shouldBe Some("replacement")
    registry.close() shouldBe List("second")
    registry.close() shouldBe Nil
    registry.get("two") shouldBe None
    registry.remove("two") shouldBe None
    registry.put("late", "third") shouldBe false
  }
}
