package spoonbill.action

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors

class StateUpdateSpec extends AnyFlatSpec with Matchers:
  private def evaluate[S](update: StateUpdate[S], state: S): S = update match
    case StateUpdate.NoUpdate()     => state
    case StateUpdate.Modify(reduce) => reduce(state)

  "StateUpdate" should "reuse either nonempty operand without evaluating reducers" in {
    var calls = 0
    val modify = StateUpdate.Modify[Int] { state =>
      calls += 1; state + 1
    }
    val skip = StateUpdate.noUpdate[Int]
    (skip.andThen(modify) eq modify) shouldBe true
    (modify.andThen(skip) eq modify) shouldBe true
    calls shouldBe 0
    evaluate(skip.andThen(modify), 1) shouldBe 2
    calls shouldBe 1
    skip.andThen(skip) shouldBe a[StateUpdate.NoUpdate[?]]
  }

  it should "compose in order and obey observational identity and associativity" in {
    val f = StateUpdate.Modify[Int](_ + 2)
    val g = StateUpdate.Modify[Int](_ * 3)
    val h = StateUpdate.Modify[Int](_ - 1)
    evaluate(f.andThen(g), 4) shouldBe 18
    for state <- -3 to 3 do
      evaluate(f.andThen(StateUpdate.noUpdate), state) shouldBe evaluate(f, state)
      evaluate(StateUpdate.noUpdate[Int].andThen(f), state) shouldBe evaluate(f, state)
      evaluate(f.andThen(g).andThen(h), state) shouldBe evaluate(f.andThen(g.andThen(h)), state)
    f.andThen(StateUpdate.Modify[Int](_ - 2)) shouldBe a[StateUpdate.Modify[?]]
    StateUpdate.Modify[Int](identity).andThen(StateUpdate.noUpdate) shouldBe a[StateUpdate.Modify[?]]
  }

  it should "keep zoom pure and apply it to the parent supplied at execution" in {
    case class Parent(child: Int, label: String)
    var reads                          = 0
    var writes                         = 0
    var reductions                     = 0
    val read: Parent => Int            = parent => { reads += 1; parent.child }
    val write: (Parent, Int) => Parent = (parent, child) => { writes += 1; parent.copy(child = child) }
    StateUpdate.noUpdate[Int].zoom(read)(write) shouldBe a[StateUpdate.NoUpdate[?]]
    val child = StateUpdate.Modify[Int] { state =>
      reductions += 1; state + 2
    }
    val zoomed = child.zoom(read)(write)
    (reads, writes, reductions) shouldBe (0, 0, 0)
    evaluate(zoomed, Parent(40, "current")) shouldBe Parent(42, "current")
    (reads, writes, reductions) shouldBe (1, 1, 1)
    StateUpdate.Modify[Int](identity).zoom(read)(write) shouldBe a[StateUpdate.Modify[?]]
    val first  = StateUpdate.Modify[Int](_ + 3)
    val second = StateUpdate.Modify[Int](_ * 2)
    val parent = Parent(5, "kept")
    evaluate(first.andThen(second).zoom(read)(write), parent) shouldBe
      evaluate(first.zoom(read)(write).andThen(second.zoom(read)(write)), parent)
  }

  it should "accept both state-update cases through the public completion and rejection APIs" in {
    typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => StateUpdate.noUpdate[Int])
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => StateUpdate.Modify[Int](_ + 1))
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => StateUpdate.noUpdate[Int])
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => StateUpdate.Modify[Int](_ + 1))
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => StateUpdate.noUpdate[Int])
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => StateUpdate.Modify[Int](_ + 1))
        val controls: Vector[UiOutcome[Int]] = Vector(
          UiOutcome.CommitAuthentication[Int](java.util.UUID.randomUUID()),
          UiOutcome.CommitReplacement[Int](handoff),
          UiOutcome.RecoverReplacement[Int](CeremonyId(java.util.UUID.randomUUID())),
          UiOutcome.Invalid[Int](InputError.UnexpectedField),
          nested
        )
        val direct: UiOutcome[Int] = StateUpdate.Modify[Int](_ + 1)
        val wide: UiOutcome[Int] = UiOutcome.noUpdate[Int]
      }
    """) shouldBe empty
  }

  it should "reject authentication commits through both form overloads and sensitive completion" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        val outcome = UiOutcome.CommitAuthentication[Int](java.util.UUID.randomUUID())
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => outcome)
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => outcome)
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => outcome)
      }
    """)
    errors.size shouldBe 3
    errors.foreach(error => error.message should include("StateUpdate"))
  }

  it should "reject replacement commits through both form overloads and sensitive completion" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        val outcome = UiOutcome.CommitReplacement[Int](handoff)
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => outcome)
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => outcome)
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => outcome)
      }
    """)
    errors.size shouldBe 3
    errors.foreach(error => error.message should include("StateUpdate"))
  }

  it should "reject replacement recovery through both form overloads and sensitive completion" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        val outcome = UiOutcome.RecoverReplacement[Int](CeremonyId(java.util.UUID.randomUUID()))
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => outcome)
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => outcome)
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => outcome)
      }
    """)
    errors.size shouldBe 3
    errors.foreach(error => error.message should include("StateUpdate"))
  }

  it should "reject invalid input outcomes through both form overloads and sensitive completion" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        val outcome = UiOutcome.Invalid[Int](InputError.UnexpectedField)
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => outcome)
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => outcome)
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => outcome)
      }
    """)
    errors.size shouldBe 3
    errors.foreach(error => error.message should include("StateUpdate"))
  }

  it should "reject nested disclosures through both form overloads and sensitive completion" in {
    val errors = typeCheckErrors("""
      import spoonbill.action.*
      import spoonbill.ActionForms
      import spoonbill.auth.{CeremonyId, CommittedReplacement}
      import spoonbill.sensitive.*
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*
      def check(public: PublicAction[Future, Int, Unit], authenticated: AuthenticatedAction[Future, Int, Unit, String],
        authority: SessionAuthority[Future, String], context: PublicActionContext, region: RegionId, purpose: Purpose,
        payload: SensitivePayload, handoff: CommittedReplacement, nested: UiOutcome.PresentSensitive[Int]) = {
        val outcome = nested
        ActionForms.onSubmit[Future, Int, Unit, Unit](public, _ => outcome)
        ActionForms.onSubmit[Future, Int, Unit, Unit, String](authenticated, authority, _ => outcome)
        context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => outcome)
      }
    """)
    errors.size shouldBe 3
    errors.foreach(error => error.message should include("StateUpdate"))
  }

  it should "remove identity-based constructors and the function-like update API" in {
    typeCheckErrors("""
      import spoonbill.action.*
      val modified: StateUpdate[Int] = StateUpdate.Modify[Int](_ + 1)
      val skipped: StateUpdate[Int] = StateUpdate.noUpdate[Int]
      val ordinary: UiOutcome[Int] = UiOutcome.modify[Int](_ + 1)
      val noOutcome: UiOutcome[Int] = UiOutcome.noUpdate[Int]
      val evaluated = modified match {
        case StateUpdate.Modify(reduce) => reduce(0)
        case StateUpdate.NoUpdate() => 0
      }
    """) shouldBe empty
    val removed = Vector(
      "apply"     -> typeCheckErrors("spoonbill.action.StateUpdate.apply[Int](_ + 1)"),
      "unchanged" -> typeCheckErrors("spoonbill.action.StateUpdate.unchanged[Int]"),
      "update"    -> typeCheckErrors("spoonbill.action.UiOutcome.update[Int](_ + 1)"),
      "unchanged" -> typeCheckErrors("spoonbill.action.UiOutcome.unchanged[Int]"),
      "Updated"   -> typeCheckErrors("spoonbill.action.UiOutcome.Updated(spoonbill.action.StateUpdate.noUpdate[Int])")
    )
    removed.foreach { case (name, errors) =>
      withClue(s"Removed API $name: ") {
        errors.exists(error => error.message.contains(name) && error.message.contains("not a member")) shouldBe true
      }
    }
    val functionLike = typeCheckErrors("""
      val update: spoonbill.action.StateUpdate[Int] = spoonbill.action.StateUpdate.Modify[Int](_ + 1)
      update.apply(0)
    """)
    functionLike.exists(error =>
      error.message.contains("apply") && error.message.contains("not a member")
    ) shouldBe true
    typeCheckErrors("new spoonbill.action.UiOutcome[Int] {}").exists(_.message.contains("sealed")) shouldBe true
    val invariant =
      typeCheckErrors("val update: spoonbill.action.StateUpdate[Any] = spoonbill.action.StateUpdate.noUpdate[Int]")
    invariant.exists(_.message.contains("StateUpdate[Any]")) shouldBe true
  }

  it should "support contextual Future returns and helper widening for invariant effects" in {
    typeCheckErrors("""
      import spoonbill.action.*
      import scala.concurrent.Future
      val actions = new Actions[Future, Int, Unit]
      val name = ActionName.parse("fixture.inference").toOption.get
      val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
        Future.successful(if true then StateUpdate.Modify[Int](_ + 1) else StateUpdate.noUpdate[Int])
      }
      final class Invariant[A](val value: A)
      def pure[A](value: A): Invariant[A] = new Invariant(value)
      val wide = pure(UiOutcome.modify[Int](_ + 1))
      val result: Invariant[UiOutcome[Int]] = wide
    """) shouldBe empty
    typeCheckErrors("""
      import spoonbill.action.*
      final class Invariant[A](val value: A)
      val narrow = new Invariant(StateUpdate.noUpdate[Int])
      val result: Invariant[UiOutcome[Int]] = narrow
    """) should not be empty
  }
