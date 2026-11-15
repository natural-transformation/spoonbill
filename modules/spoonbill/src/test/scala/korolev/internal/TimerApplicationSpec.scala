package spoonbill.internal

import TimerTestSupport.*
import avocet.{Id, StatefulRenderContext, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.action.StateUpdate
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.state.{GuardedStateManager, StateDeserializer, StateManager, StateSerializer}
import spoonbill.state.javaSerialization.*
import spoonbill.timer.*

class TimerApplicationSpec extends AnyFlatSpec with Matchers {
  import avocet.dsl.*
  import avocet.dsl.html.*
  private given Effect[Future]               = new Effect.FutureEffect
  private val ctx                            = Context[Future, Int, Unit]
  private val tickDelay                      = DelayDuration.from(1.millis).toOption.get
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private class App(
    document: Int => avocet.Document.Node[Context.Binding[Future, Int, Unit]],
    val storage: StateManager[Future] = StateManager.cached[Future](),
    authorize: Option[() => Future[Unit]] = None,
    recovery: Boolean = false,
    ready: Option[() => Future[Unit]] = None
  ) {
    val worker                              = new Worker
    val clock                               = new Deadlines
    val reports                             = new Reports
    given Reporter                          = reports
    given scala.concurrent.ExecutionContext = worker
    val frontend = new Frontend[Future](
      Queue[Future, String]().stream,
      None,
      authorize = authorize,
      viewRecoveryEpoch = if (recovery) Some(ViewOwnershipEpoch.initial) else None
    )
    // Every top-level write that the instance announces to its observer.
    val observed = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val app = new ApplicationInstance[Future, Int, Unit](
      Qsid("timer", java.util.UUID.randomUUID().toString),
      frontend,
      storage,
      0,
      document,
      Root,
      Router.empty[Future, Int],
      (rc, add) =>
        new StatefulRenderContext[Context.Binding[Future, Int, Unit]] {
          def currentContainerId: Id                                  = rc.currentContainerId
          def currentId: Id                                           = rc.currentId
          def subsequentId: Id                                        = rc.subsequentId
          def openNode(ns: XmlNs, name: String): Unit                 = rc.openNode(ns, name)
          def closeNode(name: String): Unit                           = rc.closeNode(name)
          def setAttr(ns: XmlNs, name: String, value: String): Unit   = rc.setAttr(ns, name, value)
          def setStyle(name: String, value: String): Unit             = rc.setStyle(name, value)
          def addTextNode(text: String): Unit                         = rc.addTextNode(text)
          def addMisc(misc: Context.Binding[Future, Int, Unit]): Unit = add(this, misc)
        },
      new Scheduler[Future],
      reports,
      PartialFunction.empty,
      Duration.Zero,
      awaitReady = ready,
      authorizeProduced = if (recovery) Some(_ => Future.unit) else None,
      timerDeadlines = Some(clock),
      timerClock = () => clock.now,
      observer = Some(new ApplicationInstance.Observer[Int, Unit] {
        def state(value: Int): Unit    = { observed.add(value); () }
        def message(value: Unit): Unit = ()
      })
    )
    def start(): Unit = { result(app.initialize()); worker.drain() }
    def change(value: Int): Unit = {
      result(app.topLevelComponentInstance.browserAccess.transition(_ => value)); worker.drain()
    }
    def fire(): Unit  = { clock.advance(1.millis); clock.fire(); worker.drain() }
    def close(): Unit = { result(frontend.close()); val done = app.destroy(); worker.drain(); result(done) }
  }

  "Timer application integration" should "accept recovery reset and later patches while omitting startup conflicts" in {
    val binding = ctx.timer.after(ctx.timerId("notice"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val valid   = ctx.timer.after(ctx.timerId("valid"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val f       = new App(_ => Html(body(binding, binding, valid)), authorize = Some(() => Future.unit), recovery = true)
    f.start()
    // Dev-mode CSS/event-counter control frames may precede publication.
    result(
      f.frontend.outgoingMessages.collect { case frame if frame.startsWith("[19,") => frame }.pull()
    ).isDefined shouldBe true
    f.clock.active.size shouldBe 1
    f.reports.warnings.size shouldBe 1
    f.fire()
    result(f.storage.read[Int](Id.TopLevel)) shouldBe Some(1)
    result(
      f.frontend.outgoingMessages.collect { case frame if frame.startsWith("[20,") => frame }.pull()
    ).isDefined shouldBe true
    f.reports.warnings.size shouldBe 1
    f.close()
  }

  it should "leave timer metadata unchanged when rendering throws" in {
    val binding = ctx.timer.after(ctx.timerId("notice"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val f       = new App(state => if (state == 5) throw new IllegalStateException("render failed") else Html(body(binding)))
    f.start()
    val original = f.clock.active.head
    f.change(5)
    f.clock.active shouldBe Vector(original)
    f.fire()
    result(f.storage.read[Int](Id.TopLevel)) shouldBe Some(6)
    f.clock.active shouldBe empty
    f.reports.errors.map(_._1).toSet shouldBe Set("Unable to process state update")
    f.close()
  }

  it should "shift after both preceding-lane completion and asynchronous state preparation while preserving FIFO" in {
    val raw       = StateManager.cached[Future]()
    val readGate  = Promise[Unit]()
    var gateReads = false
    val manager = new StateManager[Future] {
      def snapshot = raw.snapshot
      def read[T: StateDeserializer](id: Id) =
        if (gateReads) Effect[Future].flatMap(readGate.future)(_ => raw.read[T](id)) else raw.read[T](id)
      def write[T: StateSerializer](id: Id, value: T) = raw.write(id, value)
      def delete(id: Id)                              = raw.delete(id)
    }
    var reductions = 0
    val binding = ctx.timer
      .after(ctx.timerId("notice"), tickDelay)
      .update(StateUpdate.Modify[Int] { n =>
        reductions += 1; n + 1
      })
    val f = new App(_ => Html(body(binding)), manager)
    f.start()
    val preceding = Promise[Int]()
    result(f.app.topLevelComponentInstance.browserAccess.transitionAsync(_ => preceding.future))
    f.worker.drain()
    f.fire()
    gateReads = true
    preceding.success(10) // Simulate completion on a scheduler thread.
    reductions shouldBe 0
    f.worker.drain() // timer now waits for readGate
    result(f.app.topLevelComponentInstance.browserAccess.transition(_ * 2))
    readGate.success(())
    reductions shouldBe 0
    result(raw.read[Int](Id.TopLevel)) shouldBe Some(10)
    gateReads = false
    f.worker.drain()
    reductions shouldBe 1
    result(raw.read[Int](Id.TopLevel)) shouldBe Some(22)
    f.close()
  }

  it should "skip reduction when a public frontend closes during authorization" in {
    val gate       = Promise[Unit]()
    var holding    = false
    var reductions = 0
    val binding = ctx.timer
      .after(ctx.timerId("notice"), tickDelay)
      .update(StateUpdate.Modify[Int] { n =>
        reductions += 1; n + 1
      })
    val f = new App(_ => Html(body(binding)), authorize = Some(() => if (holding) gate.future else Future.unit))
    f.start(); holding = true; f.fire()
    result(f.frontend.close())
    gate.success(())
    f.worker.drain()
    reductions shouldBe 0
    f.reports.errors shouldBe empty
    f.close()
  }

  it should "retain guarded storage authority checks before reduction and writing" in {
    var currentOwner = true
    val raw          = StateManager.cached[Future](Map(Id.TopLevel -> 0))
    val guarded = new GuardedStateManager[Future, Int](
      raw,
      _ => if (currentOwner) Future.unit else Future.failed(new spoonbill.server.SessionAccessDenied)
    )
    var reductions = 0
    val binding = ctx.timer
      .after(ctx.timerId("notice"), tickDelay)
      .update(StateUpdate.Modify[Int] { n =>
        reductions += 1; n + 1
      })
    val f = new App(_ => Html(body(binding)), guarded, Some(() => Future.unit))
    f.start(); currentOwner = false; f.fire()
    reductions shouldBe 0
    result(raw.read[Int](Id.TopLevel)) shouldBe Some(0)
    f.clock.active shouldBe empty
    f.close()
  }

  it should "reject an old initializer result after replacing a nested component at the same address" in {
    import ctx.*
    val late   = Promise[Int]()
    var values = Vector.empty[Int]
    val first = new Component[Future, Int, Unit, Unit]((_: Unit) => late.future) {
      def render(parameters: Unit, state: Int): context.Node = div(state.toString)
    }
    val second = Component[Future, Int, Unit, Unit](20)((_, _, state) => div(state.toString))
    val storage = StateManager.cached[Future](onWrite = (id, value) => {
      if (id != Id.TopLevel) values :+= value.asInstanceOf[Int]
      Future.unit
    })
    val f = new App(state => Html(body(if (state == 0) first(()) else second(()))), storage)
    f.start()
    f.change(1)
    values shouldBe Vector(20)
    late.success(99)
    f.worker.drain()
    values shouldBe Vector(20)
    f.close()
  }

  it should "hold nested successor initialization behind an admitted predecessor write and deletion" in {
    import ctx.*
    val write = Promise[Unit]()
    var trace = Vector.empty[String]
    val first = new Component[Future, Int, Unit, Unit]((_: Unit) => Future.successful(10)) {
      def render(parameters: Unit, state: Int): context.Node = div(state.toString)
    }
    val second = Component[Future, Int, Unit, Unit](20)((_, _, state) => div(state.toString))
    val storage = StateManager.cached[Future](
      onWrite = (id, value) => {
        if (id == Id.TopLevel) Future.unit
        else { trace :+= s"write:$value"; if (value == 10) write.future else Future.unit }
      },
      onDelete = _ => { trace :+= "delete"; Future.unit }
    )
    val f = new App(state => Html(body(if (state == 0) first(()) else second(()))), storage)
    f.start()
    f.change(1)
    trace shouldBe Vector("write:10")
    write.success(())
    f.worker.drain()
    trace shouldBe Vector("write:10", "delete", "write:20")
    f.close()
  }

  it should "preserve legacy container deadlines, captured callbacks, render-driven overlap, and cancellation" in {
    val oldCallback = Promise[Unit]()
    var captured    = Vector.empty[Int]
    val f = new App(state =>
      Html(body(if (state < 3) {
        Context.Delay[Future, Int, Unit](
          (state + 1).millis,
          _ => {
            captured :+= state
            if (state == 0) oldCallback.future else Future.unit
          }
        )
      } else span("removed")))
    )
    f.start()
    val original = f.clock.active.head
    f.change(1)
    f.clock.active shouldBe Vector(original)
    f.fire()
    captured shouldBe Vector(0)
    f.change(2)
    f.clock.active.head.duration shouldBe 3.millis
    f.clock.advance(3.millis); f.clock.fire(); f.worker.drain()
    captured shouldBe Vector(0, 2) // legacy rearming does not join its older callback
    oldCallback.success(())
    f.worker.drain()
    f.change(0)
    f.clock.active.size shouldBe 1
    f.change(3)
    f.clock.active shouldBe empty
    f.close()
  }

  it should "never acknowledge ordinary publication when the output queue rejected a frame" in {
    val f = new App(_ => Html(body("page")))
    f.start()
    var accepted = 0
    result(f.frontend.outgoingMessages.cancel())
    val rejected = f.frontend.performDomChangesAccepted(_ => (), () => accepted += 1)
    result(rejected.failed) shouldBe a[spoonbill.server.SessionAccessDenied]
    accepted shouldBe 0
    f.close()
  }

  it should "preserve parameter loading and reject its late write after nested replacement" in {
    import ctx.*
    val late       = Promise[Int]()
    var parameters = Vector.empty[Int]
    var written    = Vector.empty[Int]
    val first = new Component[Future, Int, Int, Unit](10) {
      override def maybeUpdateState(parameter: Int, current: Int): Option[Future[Int]] = {
        parameters :+= parameter
        if (parameter == 1) Some(late.future) else None
      }
      def render(parameter: Int, state: Int): context.Node = div(state.toString)
    }
    val second = Component[Future, Int, Int, Unit](20)((_, _, state) => div(state.toString))
    val storage = StateManager.cached[Future](onWrite = (id, value) => {
      if (id != Id.TopLevel) written :+= value.asInstanceOf[Int]
      Future.unit
    })
    val f = new App(state => Html(body(if (state < 2) first(state) else second(state))), storage)
    f.start()
    parameters shouldBe Vector(0)
    f.change(1)
    parameters shouldBe Vector(0, 1)
    f.change(2)
    late.success(99)
    f.worker.drain()
    written shouldBe Vector(20)
    f.close()
  }

  it should "preserve concrete nested content during pre-rendered startup without an extra write" in {
    import ctx.*
    var rendered = Vector.empty[Int]
    var writes   = 0
    val child = Component[Future, Int, Unit, Unit](10) { (_, _, value) =>
      rendered :+= value
      div(value.toString)
    }
    val storage = StateManager.cached[Future](onWrite = (_, _) => { writes += 1; Future.unit })
    val f       = new App(_ => Html(body(child(()))), storage)
    result(f.app.initialize()(f.worker))
    rendered shouldBe Vector(10)
    f.worker.drain()
    writes shouldBe 0
    f.close()
  }

  it should "wait for readiness before arming timers and preserve the first state notification" in {
    val ready   = Promise[Unit]()
    val binding = ctx.timer.after(ctx.timerId("ready"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val f       = new App(_ => Html(body(binding)), ready = Some(() => ready.future))
    f.start()
    f.clock.active shouldBe empty
    f.clock.advance(10.millis)
    ready.success(())
    f.worker.drain()
    f.clock.active.head.duration shouldBe Duration.Zero
    f.clock.fire()
    f.worker.drain()
    // The observer exists from the creation of the instance, so it sees the
    // first timer write.
    f.observed.toArray.toList shouldBe List(1)
    f.close()
  }

  it should "not start timers when readiness fails during teardown" in {
    val ready   = Promise[Unit]()
    val binding = ctx.timer.after(ctx.timerId("ready"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val f       = new App(_ => Html(body(binding)), ready = Some(() => ready.future))
    f.start()
    f.close()
    ready.failure(new spoonbill.server.SessionAccessDenied)
    f.worker.drain()
    f.clock.active shouldBe empty
    f.reports.errors shouldBe empty
  }

  it should "report a failed ownership gate once across unrelated renders without clearing its fence" in {
    import ctx.*
    val storage = StateManager.cached[Future](onDelete = _ => Future.failed(new IllegalStateException("delete failed")))
    val first   = Component[Future, Int, Unit, Unit](10)((_, _, state) => div(state.toString))
    val second  = Component[Future, Int, Unit, Unit](20)((_, _, state) => div(state.toString))
    val f       = new App(state => Html(body(if (state == 0) first(()) else second(()))), storage)
    f.start()
    f.change(1)
    val count = f.reports.errors.count(_._1 == "Component initialization failed")
    count shouldBe 1
    (2 to 5).foreach(f.change)
    f.reports.errors.count(_._1 == "Component initialization failed") shouldBe count
    result(f.frontend.close())
    val closed = f.app.destroy()
    f.worker.drain()
    result(closed.failed).getMessage shouldBe "delete failed"
  }

  it should "retire admission before a guarded drain releases a removed child's last write" in {
    import ctx.*
    val write   = Promise[Unit]()
    var deletes = 0
    val raw = StateManager.cached[Future](
      Map(Id.TopLevel -> 0),
      onWrite = (id, _) => if (id == Id.TopLevel) Future.unit else write.future,
      onDelete = _ => { deletes += 1; Future.unit }
    )
    val guarded = new GuardedStateManager[Future, Int](raw, _ => Future.unit)
    val child = new Component[Future, Int, Unit, Unit]((_: Unit) => Future.successful(10)) {
      def render(parameters: Unit, state: Int): context.Node = div(state.toString)
    }
    val f = new App(
      state => Html(body(if (state == 0) child(()) else div("removed"))),
      guarded,
      authorize = Some(() => Future.unit)
    )
    f.start()
    f.change(1)
    f.app.closeAdmission()
    result(f.frontend.close())
    val draining = guarded.closeAndDrain()
    draining.isCompleted shouldBe false
    write.success(())
    f.worker.drain()
    result(draining)
    val destroyed = f.app.destroy()
    f.worker.drain()
    result(destroyed)
    deletes shouldBe 0
    f.reports.errors.map(_._1) should not contain "Component address cleanup failed"
  }

  it should "finish destruction before an asynchronous transition completes and discard its late result" in {
    val held = Promise[Int]()
    val f    = new App(_ => Html(body("no timer")))
    f.start()
    result(f.app.topLevelComponentInstance.browserAccess.transitionAsync(_ => held.future))
    f.worker.drain()
    result(f.frontend.close())
    val destroyed = f.app.destroy()
    f.worker.drain()
    val completedBeforeApplication = destroyed.isCompleted
    held.success(99)
    f.worker.drain()
    result(destroyed)
    completedBeforeApplication shouldBe true
    result(f.storage.read[Int](Id.TopLevel)) shouldBe None
  }

  it should "reject a later traversal snapshot captured before successor initialization" in {
    import ctx.*
    val oldWrite         = Promise[Unit]()
    val resumeSnapshot   = Promise[Unit]()
    var pauseSnapshot    = false
    var snapshotCaptured = false
    var writes           = Vector.empty[Int]
    var prepared         = Vector.empty[Int]
    val raw = StateManager.cached[Future](onWrite = (id, value) => {
      if (id == Id.TopLevel) Future.unit
      else {
        writes :+= value.asInstanceOf[Int]
        if (value == 10) oldWrite.future else Future.unit
      }
    })
    val storage = new StateManager[Future] {
      def snapshot = Effect[Future].flatMap(raw.snapshot) { captured =>
        if (!pauseSnapshot) Future.successful(captured)
        else {
          pauseSnapshot = false
          snapshotCaptured = true
          Effect[Future].map(resumeSnapshot.future)(_ => captured)
        }
      }
      def read[T: StateDeserializer](id: Id)          = raw.read[T](id)
      def write[T: StateSerializer](id: Id, value: T) = raw.write(id, value)
      def delete(id: Id)                              = raw.delete(id)
    }
    val first = new Component[Future, Int, Int, Unit]((_: Int) => Future.successful(10)) {
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    val successor = new Component[Future, Int, Int, Unit](20) {
      override def maybeUpdateState(parameters: Int, current: Int): Option[Future[Int]] = {
        prepared :+= current
        if (current == 10) Some(Future.successful(99)) else None
      }
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    val f = new App(state => Html(body(if (state == 0) first(state) else successor(state))), storage)
    f.start()
    f.change(1) // successor's first traversal is its loading placeholder
    pauseSnapshot = true
    f.change(2)
    snapshotCaptured shouldBe true
    oldWrite.success(())
    f.worker.drain()
    writes shouldBe Vector(10, 20)
    resumeSnapshot.success(())
    f.worker.drain()
    try {
      prepared should not contain 10
      writes should not contain 99
      prepared should contain(20)
    } finally f.close()
  }

  it should "discard late forced transitions and history computations without joining them at shutdown" in {
    for (history <- List(false, true)) {
      val held = Promise[Int]()
      val f    = new App(_ => Html(body("no timer")))
      f.start()
      val running = if (history) {
        f.app.topLevelComponentInstance.updateFromHistory(_ => Effect[Future].map(held.future)(Some(_)))
      } else f.app.topLevelComponentInstance.browserAccess.transitionForceAsync(_ => held.future)
      f.worker.drain()
      f.close()
      held.isCompleted shouldBe false
      result(f.storage.read[Int](Id.TopLevel)) shouldBe None
      held.success(99)
      f.worker.drain()
      if (history) result(running)
      else result(running.failed) shouldBe a[spoonbill.server.SessionAccessDenied]
      result(f.storage.read[Int](Id.TopLevel)) shouldBe None
      var called = false
      result(f.app.topLevelComponentInstance.updateFromHistory { _ =>
        called = true; Future.successful(Some(42))
      })
      called shouldBe false
    }
  }

  it should "still drain a transition whose storage write was already admitted" in {
    val write = Promise[Unit]()
    val raw   = StateManager.cached[Future](onWrite = (_, _) => write.future)
    val f     = new App(_ => Html(body("no timer")), raw)
    f.start()
    result(f.app.topLevelComponentInstance.browserAccess.transition(_ => 1))
    f.worker.drain()
    result(f.frontend.close())
    val destroyed = f.app.destroy()
    f.worker.drain()
    destroyed.isCompleted shouldBe false
    write.success(())
    f.worker.drain()
    result(destroyed)
    result(raw.read[Int](Id.TopLevel)) shouldBe Some(1)
  }

  it should "fail a transitionForce whose render can no longer happen, so nothing waits for it" in {
    val write = Promise[Unit]()
    val raw   = StateManager.cached[Future](onWrite = (_, _) => write.future)
    val f     = new App(_ => Html(body("no timer")), raw)
    f.start()
    // The write is admitted and waits for the storage.
    val forced = f.app.topLevelComponentInstance.browserAccess.transitionForce(_ => 1)
    f.worker.drain()
    result(f.frontend.close())
    val destroyed = f.app.destroy()
    f.worker.drain()
    forced.isCompleted shouldBe false
    write.success(())
    f.worker.drain()
    result(destroyed)
    // The render queue is closed. The caller is told, and does not wait for ever.
    result(forced.failed) shouldBe a[spoonbill.server.SessionAccessDenied]
  }

  it should "skip a loading-state tick and let a periodic timer run after initialization" in {
    import ctx.*
    for (periodic <- List(false, true)) {
      val initialized = Promise[Int]()
      var childValue  = Option.empty[Int]
      val child = new Component[Future, Int, Unit, Unit]((_: Unit) => initialized.future) {
        private val tick =
          if (periodic)
            context.timer
              .every(context.timerId("loading"), Period.from(1.millis).toOption.get)
              .update(StateUpdate.Modify[Int](_ + 1))
          else context.timer.after(context.timerId("loading"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
        override def renderNoState(parameters: Unit): context.Node = div("loading", tick)
        def render(parameters: Unit, state: Int): context.Node = {
          childValue = Some(state)
          div(state.toString, tick)
        }
      }
      val f = new App(_ => Html(body(child(()))))
      f.start()
      f.fire()
      childValue shouldBe None
      f.reports.errors shouldBe empty
      initialized.success(20)
      f.worker.drain()
      childValue shouldBe Some(20)
      if (periodic) {
        f.fire()
        childValue shouldBe Some(21)
      } else f.clock.active shouldBe empty // A successful one-shot skip is consumed.
      f.close()
    }
  }

  it should "restart an application's timer when its accepted restart key changes" in {
    val f = new App(state =>
      Html(
        body(
          ctx.timer
            .after(ctx.timerId("restart"), tickDelay, RestartKey.Number(state))
            .update(StateUpdate.Modify[Int](_ + 1))
        )
      )
    )
    f.start()
    val stale = f.clock.active.head
    f.change(10)
    stale.canceled shouldBe true
    stale.fire()
    f.worker.drain()
    result(f.storage.read[Int](Id.TopLevel)) shouldBe Some(10)
    f.fire()
    result(f.storage.read[Int](Id.TopLevel)) shouldBe Some(11)
    f.close()
  }

  it should "keep an accepted timer installed when later obsolete-binding cleanup fails" in {
    val cleanupFailure = new IllegalStateException("Synthetic cancellation failure")
    val binding        = ctx.timer.after(ctx.timerId("accepted"), tickDelay).update(StateUpdate.Modify[Int](_ + 1))
    val legacy         = ctx.delay(1.second)(_ => Future.unit)
    val f              = new App(state => Html(body(if (state == 0) legacy else binding)))
    f.start()
    f.clock.onCancel = () => throw cleanupFailure
    f.change(1)
    f.clock.onCancel = () => ()
    f.reports.errors.map(_._2) should contain(cleanupFailure)
    f.clock.active.size shouldBe 1
    f.fire()
    result(f.storage.read[Int](Id.TopLevel)) shouldBe Some(2)
    f.close()
  }

  it should "keep established content visible when a parameter reload completes across a paused snapshot" in {
    import ctx.*
    val reload       = Promise[Int]()
    val resume       = Promise[Unit]()
    var pause        = false
    var captured     = false
    var placeholders = 0
    var rendered     = Vector.empty[Int]
    val raw          = StateManager.cached[Future]()
    val storage = new StateManager[Future] {
      def snapshot = Effect[Future].flatMap(raw.snapshot) { snapshot =>
        if (!pause) Future.successful(snapshot)
        else { pause = false; captured = true; Effect[Future].map(resume.future)(_ => snapshot) }
      }
      def read[T: StateDeserializer](id: Id)          = raw.read[T](id)
      def write[T: StateSerializer](id: Id, value: T) = raw.write(id, value)
      def delete(id: Id)                              = raw.delete(id)
    }
    val child = new Component[Future, Int, Int, Unit](10) {
      override def maybeUpdateState(parameters: Int, current: Int): Option[Future[Int]] =
        if (parameters == 1) Some(reload.future) else None
      override def renderNoState(parameters: Int): context.Node = {
        placeholders += 1
        div("loading")
      }
      def render(parameters: Int, state: Int): context.Node = {
        rendered :+= state
        div(state.toString)
      }
    }
    val f = new App(state => Html(body(child(state))), storage)
    f.start()
    f.change(1)
    pause = true
    f.change(2)
    captured shouldBe true
    reload.success(20)
    f.worker.drain()
    resume.success(())
    f.worker.drain()
    try {
      placeholders shouldBe 0
      rendered.last shouldBe 20
      f.reports.errors shouldBe empty
    } finally f.close()
  }

  it should "still invalidate a pre-initialization snapshot for a fresh generated-state owner" in {
    import ctx.*
    val initialize      = Promise[Int]()
    val resume          = Promise[Unit]()
    var pause           = false
    var captured        = false
    var initializations = 0
    var rendered        = Vector.empty[Int]
    val raw             = StateManager.cached[Future]()
    val storage = new StateManager[Future] {
      def snapshot = Effect[Future].flatMap(raw.snapshot) { snapshot =>
        if (!pause) Future.successful(snapshot)
        else { pause = false; captured = true; Effect[Future].map(resume.future)(_ => snapshot) }
      }
      def read[T: StateDeserializer](id: Id)          = raw.read[T](id)
      def write[T: StateSerializer](id: Id, value: T) = raw.write(id, value)
      def delete(id: Id)                              = raw.delete(id)
    }
    val child = new Component[Future, Int, Unit, Unit]((_: Unit) => {
      initializations += 1
      initialize.future
    }) {
      def render(parameters: Unit, state: Int): context.Node = { rendered :+= state; div(state.toString) }
    }
    val f = new App(_ => Html(body(child(()))), storage)
    f.start()
    initializations shouldBe 1
    pause = true
    f.change(1)
    captured shouldBe true
    initialize.success(20)
    f.worker.drain()
    resume.success(())
    f.worker.drain()
    try {
      initializations shouldBe 1
      rendered.last shouldBe 20
      f.reports.errors shouldBe empty
    } finally f.close()
  }

  it should "complete a no-update render promise once without writing or requesting a render" in {
    var writes  = 0
    var renders = 0
    val storage = StateManager.cached[Future](onWrite = (_, _) => { writes += 1; Future.unit })
    val f       = new App(state => { renders += 1; Html(body(state.toString)) }, storage)
    f.start()
    val initialRenders = renders
    val prepared       = Promise[Option[Int]]()
    val completed      = Promise[Unit]()
    var completions    = 0
    val turn = f.app.topLevelComponentInstance.ownedTurn(
      prepared.future,
      Some { result =>
        completions += 1
        completed.tryComplete(result.toTry)
        ()
      }
    )
    try {
      completed.isCompleted shouldBe false
      prepared.success(None)
      result(turn)
      f.worker.drain()
      completed.future.value shouldBe Some(scala.util.Success(()))
      completions shouldBe 1
      writes shouldBe 0
      renders shouldBe initialRenders
      f.reports.errors shouldBe empty
    } finally f.close()
  }

  it should "deny a no-update render promise after retirement without constructing preparation" in {
    val f = new App(_ => Html(body("closed")))
    f.start()
    f.close()
    var prepared    = false
    var completions = Vector.empty[Either[Throwable, Unit]]
    result(
      f.app.topLevelComponentInstance.ownedTurn(
        {
          prepared = true
          Future.successful(None)
        },
        Some(value => completions :+= value)
      )
    )
    prepared shouldBe false
    completions.size shouldBe 1
    completions.head.left.toOption.get shouldBe a[spoonbill.server.SessionAccessDenied]
  }
}
