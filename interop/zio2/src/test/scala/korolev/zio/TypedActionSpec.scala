package spoonbill.zio

import _root_.zio.{Promise, Ref, Task, ZIO}
import _root_.zio.test.{assertTrue, suite, test, ZIOSpecDefault}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

/**
 * The same action contracts exercised with the real ZIO 2 effect adapter. These
 * are API/runtime-port tests, not an authenticated browser implementation.
 */
object TypedActionSpec extends ZIOSpecDefault:
  private given Effect[Task] = taskEffectInstance(runtime)

  private val binding = new InvocationBinding(
    InvocationId.fromUuid(new UUID(0L, 1L)),
    ConnectionId.fromUuid(new UUID(0L, 2L))
  )
  private def name(value: String): ActionName =
    ActionName.parse(value).fold(_ => throw new IllegalArgumentException("Invalid test action name"), identity)
  private def field(value: String): FieldName =
    FieldName.parse(value).fold(_ => throw new IllegalArgumentException("Invalid test field name"), identity)
  private def updated[S](result: InvocationResult[S], state: S): S = result match
    case InvocationResult.Completed(UiOutcome.Updated(update)) => update(state)
    case _                                                     => throw new IllegalStateException("Expected a completed state update")

  private case class Principal(subject: String, generation: Long)
  private val principal = Principal("subject-1", 4L)
  private def authority(current: Ref[Boolean]): SessionAuthority[Task, Principal] =
    new SessionAuthority[Task, Principal]:
      def resolve(binding: InvocationBinding): Task[Either[AccessDenied, Principal]] =
        current.get.map(valid => if valid then Right(principal) else Left(AccessDenied.Unauthenticated))
      def revalidate(binding: InvocationBinding, observed: Principal): Task[AccessDecision] =
        current.get.map { valid =>
          if valid && observed == principal then AccessDecision.Allowed
          else AccessDecision.Denied(AccessDenied.StaleAuthority)
        }

  override def spec = suite("Typed actions with ZIO 2")(
    test("public CRUD uses stable row IDs without authentication services") {
      case class Todo(id: String, title: String, done: Boolean)
      type State = Vector[Todo]
      val actions    = new Actions[Task, State, Nothing]
      val publicText = PublicPolicy.allow[Task, String]
      val title      = InputSchema.text(field("title"), 100)
      val rowId      = InputSchema.text(field("id"), 40)
      val create = actions.public(name("todo.create"), title, publicText) { (value, _) =>
        ZIO.succeed(UiOutcome.update[State](_ :+ Todo("new", value, false)))
      }
      val edit = actions.public(name("todo.edit"), rowId.zip(title), PublicPolicy.allow[Task, (String, String)]) {
        case ((id, value), _) =>
          ZIO.succeed(UiOutcome.update[State](_.map { todo =>
            if todo.id == id then todo.copy(title = value) else todo
          }))
      }
      val toggle = actions.public(name("todo.toggle"), rowId, publicText) { (id, _) =>
        ZIO.succeed(UiOutcome.update[State](_.map { todo =>
          if todo.id == id then todo.copy(done = !todo.done) else todo
        }))
      }
      val remove = actions.public(name("todo.remove"), rowId, publicText) { (id, _) =>
        ZIO.succeed(UiOutcome.update[State](_.filterNot(_.id == id)))
      }
      for
        created <- ActionDispatcher.public(create, Vector("title" -> "Draft"), binding)
        edited  <- ActionDispatcher.public(edit, Vector("id" -> "new", "title" -> "Reviewed"), binding)
        toggled <- ActionDispatcher.public(toggle, Vector("id" -> "new"), binding)
        removed <- ActionDispatcher.public(remove, Vector("id" -> "old"), binding)
      yield
        val initial   = Vector(Todo("old", "Older row", false))
        val reordered = updated(created, initial).reverse
        val result    = updated(removed, updated(toggled, updated(edited, reordered)))
        assertTrue(result == Vector(Todo("new", "Reviewed", true)))
    },
    test("public validation rejects input before creating the handler effect") {
      val created = new AtomicBoolean(false)
      val code = ValidationCode
        .parse("title.required")
        .fold(_ => throw new IllegalArgumentException("Invalid test code"), identity)
      val schema  = InputSchema.text(field("title"), 100).validate(code)(_.trim.nonEmpty)
      val actions = new Actions[Task, Int, Nothing]
      val create = actions.public(name("todo.create"), schema, PublicPolicy.allow[Task, String]) { (_, _) =>
        created.set(true)
        ZIO.succeed(UiOutcome.update[Int](_ + 1))
      }
      val pending     = ActionDispatcher.public(create, Vector("title" -> "  "), binding)
      val wasDeferred = !created.get()
      pending.map { result =>
        assertTrue(
          wasDeferred,
          !created.get(),
          result == InvocationResult.Rejected(ActionRejection.Input(InputError.InvalidValue(code)))
        )
      }
    },
    test("component-local reducers and typed child events update only the selected instance") {
      case class Counter(value: Int)
      case class Page(left: Counter, right: Counter, lastDelta: Int)
      enum CounterEvent:
        case Changed(delta: Int)
      def child(delta: Int): (StateUpdate[Counter], CounterEvent) =
        (StateUpdate(counter => counter.copy(value = counter.value + delta)), CounterEvent.Changed(delta))
      def parent(event: CounterEvent): StateUpdate[Page] = event match
        case CounterEvent.Changed(delta) => StateUpdate(page => page.copy(lastDelta = delta))
      val actions = new Actions[Task, Page, Nothing]
      val increment = actions.public(name("counter.increment"), InputSchema.empty, PublicPolicy.allow[Task, Unit]) {
        (_, _) =>
          val (local, event) = child(3)
          val zoomed         = local.zoom[Page](_.right)((page, counter) => page.copy(right = counter))
          ZIO.succeed(UiOutcome.Updated(zoomed.andThen(parent(event))))
      }
      ActionDispatcher.public(increment, Vector.empty, binding).map { result =>
        assertTrue(updated(result, Page(Counter(1), Counter(8), 0)) == Page(Counter(1), Counter(11), 3))
      }
    },
    test("protected actions receive server authority and produce a pure update") {
      for
        current <- Ref.make(true)
        actions  = new Actions[Task, String, Principal]
        action = actions.authenticated(
                   name("profile.show"),
                   InputSchema.empty,
                   ActionPolicy[Task, Unit, Principal]((_, _) => ZIO.succeed(AccessDecision.Allowed))
                 ) { (_, ctx) =>
                   ZIO.succeed(UiOutcome.update[String](_ => ctx.principal.subject))
                 }
        result <- ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current))
      yield assertTrue(updated(result, "") == "subject-1")
    },
    test("policy denial does not construct the handler effect") {
      val created = new AtomicBoolean(false)
      for
        current <- Ref.make(true)
        actions  = new Actions[Task, Int, Principal]
        action =
          actions.authenticated(
            name("profile.change"),
            InputSchema.empty,
            ActionPolicy[Task, Unit, Principal]((_, _) => ZIO.succeed(AccessDecision.Denied(AccessDenied.Forbidden)))
          ) { (_, _) =>
            created.set(true)
            ZIO.succeed(UiOutcome.update[Int](_ + 1))
          }
        result <- ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current))
      yield assertTrue(
        !created.get(),
        result == InvocationResult.Rejected(ActionRejection.Access(AccessDenied.Forbidden))
      )
    },
    test("revocation during an awaited policy prevents handler construction") {
      val created = new AtomicBoolean(false)
      for
        current <- Ref.make(true)
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, AccessDecision]
        actions  = new Actions[Task, Int, Principal]
        action = actions.authenticated(
                   name("profile.change"),
                   InputSchema.empty,
                   ActionPolicy[Task, Unit, Principal]((_, _) => started.succeed(()) *> release.await)
                 ) { (_, _) =>
                   created.set(true)
                   ZIO.succeed(UiOutcome.update[Int](_ + 1))
                 }
        running <- ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current)).fork
        _       <- started.await
        _       <- current.set(false)
        _       <- release.succeed(AccessDecision.Allowed)
        result  <- running.join
      yield assertTrue(
        !created.get(),
        result == InvocationResult.Rejected(ActionRejection.Access(AccessDenied.StaleAuthority))
      )
    },
    test("revocation during handler work suppresses output without claiming rollback") {
      for
        current   <- Ref.make(true)
        started   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        committed <- Ref.make(false)
        actions    = new Actions[Task, Int, Principal]
        action = actions.authenticated(
                   name("profile.change"),
                   InputSchema.empty,
                   ActionPolicy[Task, Unit, Principal]((_, _) => ZIO.succeed(AccessDecision.Allowed))
                 ) { (_, _) =>
                   started.succeed(()) *> release.await *> committed.set(true).as(UiOutcome.update[Int](_ + 1))
                 }
        running   <- ActionDispatcher.authenticated(action, Vector.empty, binding, authority(current)).fork
        _         <- started.await
        _         <- current.set(false)
        _         <- release.succeed(())
        result    <- running.join
        didCommit <- committed.get
      yield assertTrue(didCommit, result == InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority))
    }
  )
