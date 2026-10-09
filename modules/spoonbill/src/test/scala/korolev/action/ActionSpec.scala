package spoonbill.action

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

class ActionSpec extends AsyncFlatSpec with Matchers:
  private given Effect[Future] = new Effect.FutureEffect

  private val binding = new InvocationBinding(
    InvocationId.fromUuid(new UUID(0L, 1L)),
    ConnectionId.fromUuid(new UUID(0L, 2L))
  )
  private def name(value: String): ActionName = ActionName.parse(value).fold(error => fail(error.toString), identity)
  private def field(value: String): FieldName = FieldName.parse(value).fold(error => fail(error.toString), identity)
  private def applyResult[S](result: InvocationResult[S], state: S): S = result match
    case InvocationResult.Completed(UiOutcome.Updated(update)) => update(state)
    case other                                                 => fail(s"Expected a completed update, got $other")

  private case class Principal(user: String, generation: Long)
  private val principal = Principal("user-1", 7L)
  private def authority(current: AtomicBoolean): SessionAuthority[Future, Principal] =
    new SessionAuthority[Future, Principal]:
      def resolve(binding: InvocationBinding): Future[Either[AccessDenied, Principal]] =
        Future.successful(if current.get() then Right(principal) else Left(AccessDenied.Unauthenticated))
      def revalidate(binding: InvocationBinding, observed: Principal): Future[AccessDecision] =
        Future.successful(
          if current.get() && observed == principal then AccessDecision.Allowed
          else AccessDecision.Denied(AccessDenied.StaleAuthority)
        )

  "Public actions" should "implement ordinary CRUD by stable IDs without authentication infrastructure" in {
    case class Todo(id: String, title: String, done: Boolean)
    type State = Vector[Todo]
    val actions   = new Actions[Future, State, Nothing]
    val allowText = PublicPolicy.allow[Future, String]
    val create = actions.public(name("todo.create"), InputSchema.text(field("title"), 100), allowText) { (title, _) =>
      Future.successful(UiOutcome.update[State](_ :+ Todo("new", title, false)))
    }
    val toggle = actions.public(name("todo.toggle"), InputSchema.text(field("id"), 50), allowText) { (id, _) =>
      Future.successful(
        UiOutcome.update[State](_.map(todo => if todo.id == id then todo.copy(done = !todo.done) else todo))
      )
    }
    val remove = actions.public(name("todo.remove"), InputSchema.text(field("id"), 50), allowText) { (id, _) =>
      Future.successful(UiOutcome.update[State](_.filterNot(_.id == id)))
    }
    for
      created <- ActionDispatcher.public(create, Vector("title" -> "Write tests"), binding)
      toggled <- ActionDispatcher.public(toggle, Vector("id" -> "new"), binding)
      removed <- ActionDispatcher.public(remove, Vector("id" -> "old"), binding)
    yield
      val initial    = Vector(Todo("old", "Old row", false))
      val reordered  = applyResult(created, initial).reverse
      val finalState = applyResult(removed, applyResult(toggled, reordered))
      finalState shouldBe Vector(Todo("new", "Write tests", true))
  }

  it should "support pure component-local updates and typed child-to-parent events" in {
    case class Counter(value: Int)
    case class Page(left: Counter, right: Counter, lastDelta: Int)
    enum CounterEvent:
      case Changed(delta: Int)
    def child(delta: Int): (StateUpdate[Counter], CounterEvent) =
      (StateUpdate(counter => counter.copy(value = counter.value + delta)), CounterEvent.Changed(delta))
    def parent(event: CounterEvent): StateUpdate[Page] = event match
      case CounterEvent.Changed(delta) => StateUpdate(page => page.copy(lastDelta = delta))
    val actions = new Actions[Future, Page, Nothing]
    val increment = actions.public(name("counter.increment"), InputSchema.empty, PublicPolicy.allow[Future, Unit]) {
      (_, _) =>
        val (update, event) = child(2)
        val zoomed          = update.zoom[Page](_.left)((page, counter) => page.copy(left = counter))
        Future.successful(UiOutcome.Updated(zoomed.andThen(parent(event))))
    }
    ActionDispatcher.public(increment, Vector.empty, binding).map { result =>
      applyResult(result, Page(Counter(1), Counter(9), 0)) shouldBe Page(Counter(3), Counter(9), 2)
    }
  }

  "Authenticated actions" should "issue trusted context and return a pure update after current checks" in {
    val actions = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal]((_, actor) =>
        Future.successful(
          if actor.user == "user-1" then AccessDecision.Allowed else AccessDecision.Denied(AccessDenied.Forbidden)
        )
      )
    ) { (_, context) =>
      context.principal shouldBe principal
      Future.successful(UiOutcome.update[Int](_ + 1))
    }
    ActionDispatcher.authenticated(action, Vector.empty, binding, authority(new AtomicBoolean(true))).map { result =>
      applyResult(result, 4) shouldBe 5
    }
  }

  it should "not start a handler when authority is absent" in {
    val called  = new AtomicBoolean(false)
    val actions = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, _) =>
      called.set(true)
      Future.successful(UiOutcome.update[Int](_ + 1))
    }
    ActionDispatcher.authenticated(action, Vector.empty, binding, authority(new AtomicBoolean(false))).map { result =>
      result shouldBe InvocationResult.Rejected(ActionRejection.Access(AccessDenied.Unauthenticated))
      called.get() shouldBe false
    }
  }

  it should "not start a handler when policy denies even with a valid principal" in {
    val called  = new AtomicBoolean(false)
    val actions = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal]((_, _) => Future.successful(AccessDecision.Denied(AccessDenied.Forbidden)))
    ) { (_, _) =>
      called.set(true)
      Future.successful(UiOutcome.update[Int](_ + 1))
    }
    ActionDispatcher.authenticated(action, Vector.empty, binding, authority(new AtomicBoolean(true))).map { result =>
      result shouldBe InvocationResult.Rejected(ActionRejection.Access(AccessDenied.Forbidden))
      called.get() shouldBe false
    }
  }

  it should "revalidate after an awaited policy before starting an eager Future handler" in {
    val current       = new AtomicBoolean(true)
    val called        = new AtomicBoolean(false)
    val policyStarted = Promise[Unit]()
    val policyResult  = Promise[AccessDecision]()
    val actions       = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal] { (_, _) =>
        policyStarted.success(())
        policyResult.future
      }
    ) { (_, _) =>
      called.set(true)
      Future.successful(UiOutcome.update[Int](_ + 1))
    }
    val running = ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current))
    policyStarted.future.flatMap { _ =>
      current.set(false)
      policyResult.success(AccessDecision.Allowed)
      running.map { result =>
        result shouldBe InvocationResult.Rejected(ActionRejection.Access(AccessDenied.StaleAuthority))
        called.get() shouldBe false
      }
    }
  }

  it should "suppress revoked output while acknowledging that the handler already ran" in {
    val current = new AtomicBoolean(true)
    val started = Promise[Unit]()
    val outcome = Promise[UiOutcome[Int]]()
    val actions = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.empty,
      ActionPolicy[Future, Unit, Principal]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, context) =>
      context.principal shouldBe principal
      context.invocationId shouldBe binding.invocationId
      started.success(())
      outcome.future
    }
    val running = ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current))
    started.future.flatMap { _ =>
      current.set(false)
      outcome.success(UiOutcome.update[Int](_ + 1))
      running.map(_ shouldBe InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority))
    }
  }

  it should "reject malformed input without resolving authority or starting the handler" in {
    val resolved = new AtomicBoolean(false)
    val called   = new AtomicBoolean(false)
    val port = new SessionAuthority[Future, Principal]:
      def resolve(binding: InvocationBinding): Future[Either[AccessDenied, Principal]] =
        resolved.set(true)
        Future.successful(Right(principal))
      def revalidate(binding: InvocationBinding, value: Principal): Future[AccessDecision] =
        Future.successful(AccessDecision.Allowed)
    val actions = new Actions[Future, Int, Principal]
    val action = actions.authenticated(
      name("secure.change"),
      InputSchema.checked(field("flag")),
      ActionPolicy[Future, Boolean, Principal]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, _) =>
      called.set(true)
      Future.successful(UiOutcome.unchanged[Int])
    }
    ActionDispatcher.authenticated(action, Vector("flag" -> "yes"), binding, port).map { result =>
      result shouldBe InvocationResult.Rejected(ActionRejection.Input(InputError.InvalidBoolean(field("flag"))))
      resolved.get() shouldBe false
      called.get() shouldBe false
    }
  }
