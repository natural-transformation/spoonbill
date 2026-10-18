package spoonbill

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.sensitive.*

class ActionFormsExecutionSpec extends AsyncFlatSpec with Matchers:
  private given Effect[Future] = new Effect.FutureEffect
  private val actions          = new Actions[Future, Int, String]
  private val name             = ActionName.parse("fixture.execute").toOption.get
  private val region           = RegionId.parse("fixture").toOption.get
  private val purpose          = Purpose.parse("fixture.disclose").toOption.get

  private def fixture(fields: Vector[(String, String)] = Vector.empty, current: () => Boolean = () => true)(
    test: ActionFormsRuntimeFixture[Int] => Future[Assertion]
  ): Future[Assertion] =
    val access  = new ActionFormsRuntimeFixture(10, fields, current)
    val running = Effect[Future].delayAsync(test(access))
    access.bounded(running).transform { result =>
      access.close()
      result
    }

  private def submit(action: PublicAction[Future, Int, Unit], rejection: ActionRejection => StateUpdate[Int]) =
    ActionForms.onSubmit[Future, Int, Unit, Unit](action, rejection)

  private def rejectUnexpected(reason: ActionRejection): StateUpdate[Int] = fail(s"Unexpected rejection: $reason")

  "Typed form execution" should "request no transition for ordinary NoUpdate" in fixture() { access =>
    val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      Future.successful(UiOutcome.noUpdate[Int])
    }
    access.execute(submit(action, rejectUnexpected)).map(_ => access.transitions shouldBe empty)
  }

  it should "hand off one reducer without reading or reducing state early" in fixture() { access =>
    var reduced = 0
    val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      Future.successful(UiOutcome.modify[Int] { state =>
        reduced += 1; state + 1
      })
    }
    access.execute(submit(action, rejectUnexpected)).map { _ =>
      access.transitions.size shouldBe 1
      access.stateReads.get() shouldBe 0
      reduced shouldBe 0
      access.transitions.head(41) shouldBe 42
      reduced shouldBe 1
    }
  }

  for modify <- Vector(false, true) do
    it should s"interpret Invalid with ${if modify then "Modify" else "NoUpdate"} rejection" in fixture() { access =>
      var seen = Vector.empty[ActionRejection]
      val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
        Future.successful(UiOutcome.Invalid[Int](InputError.UnexpectedField))
      }
      access
        .execute(
          submit(
            action,
            reason =>
              seen :+= reason
              if modify then StateUpdate.Modify[Int](_ + 2) else StateUpdate.noUpdate[Int]
          )
        )
        .map { _ =>
          seen shouldBe Vector(ActionRejection.Input(InputError.UnexpectedField))
          access.transitions.size shouldBe (if modify then 1 else 0)
          if modify then access.transitions.head(10) shouldBe 12
          succeed
        }
    }

    it should s"interpret input rejection with ${if modify then "Modify" else "NoUpdate"}" in fixture(
      Vector("extra" -> "value")
    ) { access =>
      var invoked = false
      var seen    = Vector.empty[ActionRejection]
      val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
        invoked = true
        Future.successful(UiOutcome.noUpdate[Int])
      }
      access
        .execute(
          submit(
            action,
            reason =>
              seen :+= reason
              if modify then StateUpdate.Modify[Int](_ + 2) else StateUpdate.noUpdate[Int]
          )
        )
        .map { _ =>
          invoked shouldBe false
          seen shouldBe Vector(ActionRejection.Input(InputError.UnexpectedField))
          access.transitions.size shouldBe (if modify then 1 else 0)
          if modify then access.transitions.head(10) shouldBe 12
          succeed
        }
    }

    it should s"interpret authenticated access rejection with ${if modify then "Modify" else "NoUpdate"}" in fixture() {
      access =>
        var invoked = false
        var seen    = Vector.empty[ActionRejection]
        val authority = new SessionAuthority[Future, String]:
          def resolve(binding: InvocationBinding): Future[Either[AccessDenied, String]] =
            Future.successful(Left(AccessDenied.Unauthenticated))
          def revalidate(binding: InvocationBinding, principal: String): Future[AccessDecision] =
            fail("Unexpected revalidation")
        val action = actions.authenticated(
          name,
          InputSchema.empty,
          ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
        ) { (_, _) =>
          invoked = true
          Future.successful(UiOutcome.noUpdate[Int])
        }
        val binding = ActionForms.onSubmit[Future, Int, Unit, Unit, String](
          action,
          authority,
          reason =>
            seen :+= reason
            if modify then StateUpdate.Modify[Int](_ + 2) else StateUpdate.noUpdate[Int]
        )
        access.execute(binding).map { _ =>
          invoked shouldBe false
          seen shouldBe Vector(ActionRejection.Access(AccessDenied.Unauthenticated))
          access.transitions.size shouldBe (if modify then 1 else 0)
          if modify then access.transitions.head(10) shouldBe 12
          succeed
        }
    }

    for outcome <- DisclosureOutcome.values do
      it should s"wait for $outcome before sensitive ${if modify then "Modify" else "NoUpdate"}" in fixture() {
        access =>
          val callbacks = new AtomicInteger()
          val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, context) =>
            val payload = SensitivePayload.textList(Vector("synthetic-only")).toOption.get
            Future.successful(context.presentSensitive[Int](region, purpose, payload, 1.minute) { result =>
              result shouldBe outcome
              callbacks.incrementAndGet()
              if modify then StateUpdate.Modify[Int](_ + 2) else StateUpdate.noUpdate[Int]
            })
          }
          val running = access.execute(submit(action, rejectUnexpected))
          for
            _ <- access.presentationEntered.future
            _  = callbacks.get() shouldBe 0
            _  = access.transitions shouldBe empty
            _  = running.isCompleted shouldBe false
            _  = access.pendingHolders shouldBe 0
            _  = access.acknowledgement.success(outcome)
            _ <- running
          yield
            callbacks.get() shouldBe 1
            access.transitions.size shouldBe (if modify then 1 else 0)
            if modify then access.transitions.head(40) shouldBe 42
            succeed
      }

  it should "fail a throwing sensitive callback without a transition or holder leak" in fixture() { access =>
    val failure = new IllegalStateException("synthetic completion failure")
    val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, context) =>
      Future.successful(
        context.presentSensitive[Int](
          region,
          purpose,
          SensitivePayload.textList(Vector("synthetic-only")).toOption.get,
          1.minute
        )(_ => throw failure)
      )
    }
    val running = access.execute(submit(action, rejectUnexpected))
    for
      _     <- access.presentationEntered.future
      _      = access.acknowledgement.success(DisclosureOutcome.BrowserProcessed)
      error <- running.failed
    yield
      error shouldBe failure
      access.transitions shouldBe empty
      access.pendingHolders shouldBe 0
  }

  it should "release the sensitive holder when presentation fails without calling completion" in fixture() { access =>
    val failure   = new IllegalStateException("synthetic presentation failure")
    var callbacks = 0
    val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, context) =>
      Future.successful(
        context.presentSensitive[Int](
          region,
          purpose,
          SensitivePayload.textList(Vector("synthetic-only")).toOption.get,
          1.minute
        ) { _ =>
          callbacks += 1
          StateUpdate.noUpdate[Int]
        }
      )
    }
    val running = access.execute(submit(action, rejectUnexpected))
    for
      _     <- access.presentationEntered.future
      _      = access.acknowledgement.failure(failure)
      error <- running.failed
    yield
      error shouldBe failure
      callbacks shouldBe 0
      access.transitions shouldBe empty
      access.pendingHolders shouldBe 0
  }

  it should "complete an expired disclosure with NoUpdate without starting presentation" in fixture() { access =>
    val checks         = new AtomicInteger()
    val checkingOutput = Promise[Unit]()
    val allowOutput    = Promise[AccessDecision]()
    var outcomes       = Vector.empty[DisclosureOutcome]
    val policy = PublicPolicy[Future, Unit] { _ =>
      if checks.incrementAndGet() == 1 then Future.successful(AccessDecision.Allowed)
      else { checkingOutput.trySuccess(()); allowOutput.future }
    }
    val action = actions.public(name, InputSchema.empty, policy) { (_, context) =>
      Future.successful(
        context.presentSensitive[Int](
          region,
          purpose,
          SensitivePayload.textList(Vector("synthetic-only")).toOption.get,
          1.minute
        ) { outcome =>
          outcomes :+= outcome
          StateUpdate.noUpdate[Int]
        }
      )
    }
    val running = access.execute(submit(action, rejectUnexpected))
    for
      _ <- checkingOutput.future
      _  = access.pendingHolders shouldBe 1
      _  = access.expirePending()
      _  = access.pendingHolders shouldBe 0
      _  = allowOutput.success(AccessDecision.Allowed)
      _ <- running
    yield
      outcomes shouldBe Vector(DisclosureOutcome.NotSent)
      access.presentationEntered.isCompleted shouldBe false
      access.transitions shouldBe empty
  }

  for invalid <- Vector(false, true) do
    it should s"capture a throwing ${if invalid then "Invalid" else "access rejection"} callback" in fixture() {
      access =>
        val failure = new IllegalStateException("synthetic rejection failure")
        val policy = PublicPolicy[Future, Unit](_ =>
          Future.successful(if invalid then AccessDecision.Allowed else AccessDecision.Denied(AccessDenied.Forbidden))
        )
        val action = actions.public(name, InputSchema.empty, policy) { (_, _) =>
          Future.successful(UiOutcome.Invalid[Int](InputError.UnexpectedField))
        }
        access.execute(submit(action, _ => throw failure)).failed.map { error =>
          error shouldBe failure
          access.transitions shouldBe empty
        }
    }

  it should "skip a superseded event without calling action or rejection" in fixture(current = () => false) { access =>
    val action = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      fail("Superseded action ran")
    }
    access.execute(submit(action, rejectUnexpected)).map { _ =>
      access.transitions shouldBe empty
      access.retirements.get() shouldBe 0
    }
  }

  it should "retire suppressed output without invoking the rejection callback" in fixture() { access =>
    val checks = new AtomicInteger()
    val policy = PublicPolicy[Future, Unit] { _ =>
      Future.successful(
        if checks.incrementAndGet() == 1 then AccessDecision.Allowed
        else AccessDecision.Denied(AccessDenied.StaleAuthority)
      )
    }
    val action = actions.public(name, InputSchema.empty, policy) { (_, context) =>
      Future.successful(
        context.presentSensitive[Int](
          region,
          purpose,
          SensitivePayload.textList(Vector("synthetic-only")).toOption.get,
          1.minute
        )(_ => fail("Suppressed callback ran"))
      )
    }
    access.execute(submit(action, rejectUnexpected)).map { _ =>
      access.transitions shouldBe empty
      access.presentationEntered.isCompleted shouldBe false
      access.retirements.get() shouldBe 1
      access.pendingHolders shouldBe 0
    }
  }
