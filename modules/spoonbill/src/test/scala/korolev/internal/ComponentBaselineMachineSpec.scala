package spoonbill.internal

import ComponentBaselineMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ComponentBaselineMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Component baseline metadata" should "distinguish initial loading from refreshes of ready and unready baselines" in {
    initial(reused = false) shouldBe State.Ready
    initial(reused = true) shouldBe State.NeedsBaseline
    val initialLoad = begin(State.Ready, Load.Initial)
    initialLoad shouldBe State.LoadingInitial
    hasBaseline(initialLoad) shouldBe true
    val refresh = begin(State.Ready, Load.Refresh)
    refresh shouldBe State.RefreshingReady
    hasBaseline(refresh) shouldBe true
    val restoring = begin(State.NeedsBaseline, Load.Refresh)
    restoring shouldBe State.RefreshingBaseline
    hasBaseline(restoring) shouldBe false
    for (state <- Vector(initialLoad, refresh, restoring); kind <- Load.values)
      begin(state, kind) shouldBe state
    begin(State.NeedsBaseline, Load.Initial) shouldBe State.NeedsBaseline
    written shouldBe State.Ready
  }

  it should "retry failures only for an open owner without losing concurrently established baseline readiness" in {
    failed(State.RefreshingBaseline, ownerOpen = true) shouldBe State.NeedsBaseline
    failed(State.RefreshingReady, ownerOpen = true) shouldBe State.Ready
    failed(State.LoadingInitial, ownerOpen = true) shouldBe State.Ready
    val suppressed = failed(State.RefreshingBaseline, ownerOpen = false)
    suppressed shouldBe State.SuppressedBaseline
    acceptsLoad(suppressed) shouldBe false
    hasBaseline(suppressed) shouldBe false
    failed(State.RefreshingReady, ownerOpen = false) shouldBe State.SuppressedReady
    // A later failure never undoes a successful write's ready baseline.
    failed(written, ownerOpen = true) shouldBe State.Ready
    failed(written, ownerOpen = false) shouldBe State.Ready
  }

  it should "preserve the original readiness and admission observations through generated histories" in {
    forAll(Gen.oneOf(true, false), Gen.listOf(Gen.choose(0, 4))) { (reused, events) =>
      events.foldLeft((initial(reused), !reused, false)) { case ((state, ready, loading), event) =>
        val (next, expectedReady, expectedLoading) = event match {
          case 0 => (begin(state, Load.Initial), ready, loading || ready)
          case 1 => (begin(state, Load.Refresh), ready, true)
          case 2 => (written, true, false)
          case 3 => (failed(state, ownerOpen = true), ready, false)
          case _ => (failed(state, ownerOpen = false), ready, loading)
        }
        hasBaseline(next) shouldBe expectedReady
        acceptsLoad(next) shouldBe !expectedLoading
        (next, expectedReady, expectedLoading)
      }
    }
  }

  it should "preserve parameter equality and null behavior while distinguishing unseen metadata from data" in {
    val values: Vector[Any] = Vector(null, 0, 1, 1L, "same", new String("same"), (), null, Vector(1), Vector(1))
    values.foldLeft((emptyParameters[Any], null: Any)) { case ((stored, previous), current) =>
      parametersDiffer(stored, current) shouldBe (previous != current)
      (remember[Any](current), current)
    }
    parametersDiffer(emptyParameters[String], null) shouldBe false
    parametersDiffer(remember[String](null), "value") shouldBe true
    parametersDiffer(remember[String]("value"), null) shouldBe true
  }
}
