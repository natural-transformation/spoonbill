package spoonbill.internal

import TimerTestSupport.*
import avocet.{Id, StatefulRenderContext, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.action.StateUpdate
import spoonbill.effect.{Effect, Queue, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*
import spoonbill.timer.*

class TimerRuntimeSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]               = new Effect.FutureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private class Fixture(capacity: Int = 4) {
    val worker  = new Worker
    val clock   = new Deadlines
    val reports = new Reports
    val runtime = new TimerRuntime[Future](clock, () => clock.now, worker, reports, TimerSettings(capacity))
    val ctx     = Context[Future, Int, Unit]
    val id      = ctx.timerId("notice")
    runtime.start()

    class Target(number: Long) extends TimerRuntime.Target[Future] {
      val owner                                             = TimerMachine.Owner(number)
      var active                                            = true
      val lane                                              = mutable.Queue.empty[ComponentLaneEntry[Future]]
      var gate: Future[Unit]                                = Future.unit
      var state                                             = 0
      var writes                                            = 0
      var afterWrite: () => Future[Unit]                    = () => Future.unit
      def offer(entry: ComponentLaneEntry[Future]): Boolean = if (!active) false else { lane.enqueue(entry); true }
      def deliver(select: () => Option[Any => Option[Any]]): Future[Unit] = Effect[Future].flatMap(gate) { _ =>
        Effect[Future].fork(Effect[Future].delayAsync {
          if (!active) Future.unit
          else
            select().flatMap(_(state)) match {
              case None        => Future.unit
              case Some(value) => state = value.asInstanceOf[Int]; writes += 1; afterWrite()
            }
        })(worker)
      }
      def run(): Future[Unit] = lane.dequeue().run()
      def close(): Unit = {
        active = false
        runtime.close(owner)
        lane.dequeueAll(_ => true).foreach(_.discard())
      }
    }
    val target = new Target(1)
    def declaration(
      id: TimerId[Int] = this.id,
      target: Target = this.target,
      key: RestartKey = RestartKey.Stable,
      periodic: Boolean = false,
      update: Int => Option[Int] = n => Some(n + 1)
    ): Unit =
      runtime.collect(target, id, 1000000L, periodic, key, update)
    def render(body: => Unit): Unit = {
      runtime.beginRender(); body; runtime.prepareRender(); runtime.acceptTimerRender(); runtime.flush()
    }
    def due(): Unit                              = { clock.advance(1.millis); clock.fire(); worker.drain() }
    def turn(target: Target = this.target): Unit = { val running = target.run(); worker.drain(); result(running) }
  }

  "Timer runtime" should "install only accepted renders, refresh updates without moving deadlines, and run one-shot once" in {
    val f = new Fixture
    import f.*
    runtime.beginRender(); declaration(); runtime.prepareRender()
    clock.active shouldBe empty
    runtime.abortRender()
    render(declaration())
    val deadline = clock.active.head
    render(declaration(update = n => Some(n + 42)))
    clock.active shouldBe Vector(deadline)
    due(); turn()
    target.state shouldBe 42
    render(declaration())
    clock.active shouldBe empty
    runtime.retainedDescriptions shouldBe 0
  }

  it should "omit all duplicate identities, retain existing priority, and release overflow descriptions before publication" in {
    val f = new Fixture(1)
    import f.*
    val b = ctx.timerId("B")
    val c = ctx.timerId("C")
    render { declaration(); declaration(b); declaration(); declaration(b); declaration(c) }
    reports.warnings.size shouldBe 1
    reports.warnings.head should include("conflicts=2")
    runtime.snapshot.slots.keys.map(_.id).toSet shouldBe Set(c)
    runtime.beginRender()
    (1 to 10000).foreach(n => declaration(ctx.timerId(s"new:$n")))
    declaration(c)
    runtime.prepareRender()
    runtime.retainedDescriptions should be <= 2 // installed and staged share one retained identity
    runtime.acceptTimerRender(); runtime.flush()
    runtime.snapshot.slots.keys.map(_.id).toSet shouldBe Set(c)
    render { declaration(c); declaration(ctx.timerId("")) }
    reports.warnings.last should not include "new:"
  }

  it should "charge retired active work and require a later render to admit an omitted timer" in {
    val f = new Fixture(1)
    import f.*
    val gate = Promise[Unit]()
    target.gate = gate.future
    render(declaration())
    due()
    val running = target.run()
    worker.drain()
    val other = new Target(2)
    render(declaration(target = other))
    runtime.snapshot.slots.size shouldBe 1
    reports.warnings.last should include("retired-unsettled=1")
    gate.success(())
    worker.drain(); result(running)
    target.writes shouldBe 0
    clock.active shouldBe empty
    other.lane shouldBe empty
    render(declaration(target = other))
    clock.active.head.duration shouldBe 1.millis
    due(); turn(other)
    other.state shouldBe 1
  }

  it should "keep a thousand queued restarts bounded and deliver only the latest generation" in {
    val f = new Fixture(1)
    import f.*
    render(declaration())
    due()
    for (n <- 1 to 1000) render(declaration(key = RestartKey.Number(n), update = _ => Some(n)))
    due()
    target.lane.size shouldBe 1
    turn() // discard old entry, then dispatch successor
    target.writes shouldBe 0
    target.lane.size shouldBe 1
    turn()
    target.state shouldBe 1000
  }

  it should "settle close before offer, buffered discard, and active authorization exactly once" in {
    for (phase <- 0 to 2) {
      val f = new Fixture(1)
      import f.*
      val gate = Promise[Unit]()
      target.gate = gate.future
      render(declaration(periodic = true))
      clock.advance(1.millis); clock.fire()
      if (phase > 0) worker.drain()
      val running = if (phase == 2) Some(target.run()) else None
      if (phase == 2) worker.drain()
      target.close()
      gate.success(())
      worker.drain()
      running.foreach(result)
      runtime.snapshot.slots shouldBe empty
      target.writes shouldBe 0
      clock.active shouldBe empty
    }
  }

  it should "refresh until final admission and keep another owner progressing during slow authorization" in {
    val f = new Fixture
    import f.*
    val gate = Promise[Unit]()
    target.gate = gate.future
    val other = new Target(2)
    render { declaration(); declaration(target = other) }
    clock.advance(1.millis)
    clock.active.foreach(_.fire())
    worker.drain()
    val waiting = target.run()
    worker.drain()
    turn(other)
    other.state shouldBe 1
    render { declaration(update = _ => Some(42)); declaration(target = other) }
    gate.success(())
    target.state shouldBe 0
    worker.drain(); result(waiting)
    target.state shouldBe 42
  }

  it should "not rearm when an inline notification removes its own periodic binding" in {
    for (asyncNotification <- List(false, true)) {
      val f = new Fixture
      import f.*
      val notification = Promise[Unit]()
      target.afterWrite = () => { render {}; if (asyncNotification) notification.future else Future.unit }
      render(declaration(periodic = true))
      due()
      val running = target.run()
      worker.drain()
      if (asyncNotification) {
        runtime.snapshot.slots.size shouldBe 1
        notification.success(())
      }
      worker.drain(); result(running)
      target.state shouldBe 1
      runtime.snapshot.slots shouldBe empty
      clock.active shouldBe empty
    }
  }

  it should "stop unexpected failures and coalesce diagnostics until a clean render" in {
    val f = new Fixture
    import f.*
    render(declaration(update = _ => throw new IllegalStateException("synthetic")))
    due(); turn()
    reports.errors.size shouldBe 1
    render(declaration())
    clock.active shouldBe empty
    render(declaration(ctx.timerId("")))
    val count = reports.warnings.size
    (1 to 100).foreach(_ => render(declaration(ctx.timerId(""))))
    reports.warnings.size shouldBe count
    render {}
    render(declaration(ctx.timerId("")))
    reports.warnings.size shouldBe count + 1
  }

  it should "reserve replacements across stalled publication and replay old due wakes on abort" in {
    for (accept <- List(false, true)) {
      val f = new Fixture(1)
      import f.*
      render(declaration())
      val next = ctx.timerId("replacement")
      runtime.beginRender(); declaration(next); runtime.prepareRender()
      due()
      target.lane shouldBe empty
      runtime.snapshot.slots.size shouldBe 1
      if (accept) {
        runtime.acceptTimerRender(); runtime.flush()
        clock.active.head.duration shouldBe 1.millis
        due()
      } else {
        runtime.abortRender(); worker.drain()
      }
      turn()
      target.state shouldBe 1
      runtime.snapshot.slots.keys.head.id shouldBe (if (accept) next else id)
    }
  }

  it should "release omitted callbacks on abort and reject stale and early driver callbacks" in {
    val f = new Fixture(1)
    import f.*
    render(declaration())
    val original = clock.active.head
    clock.advance(999.microseconds)
    original.fire()
    clock.active.head.duration shouldBe 1.millis
    original.fire() // consumed arm cannot schedule twice
    clock.active.size shouldBe 1
    runtime.beginRender()
    (1 to 1000).foreach(n => declaration(ctx.timerId(s"omitted:$n")))
    runtime.prepareRender(); runtime.abortRender()
    runtime.retainedDescriptions shouldBe 1
    clock.advance(1.microsecond); clock.fire(); worker.drain(); turn()
    target.state shouldBe 1
  }

  it should "keep equal names isolated by owner and preserve identity when document order changes" in {
    val f = new Fixture
    import f.*
    val other = new Target(2)
    val b     = ctx.timerId("other-site:notice")
    render { declaration(); declaration(b); declaration(target = other) }
    val arms = clock.active
    render { declaration(target = other); declaration(b); declaration() }
    clock.active shouldBe arms
    clock.advance(1.millis); arms.foreach(_.fire()); worker.drain()
    turn(); turn(); turn(other)
    target.state shouldBe 2
    other.state shouldBe 1
  }

  it should "stop generations after scheduling or dispatch rejection without leaking an entry" in {
    for (rejectSchedule <- List(true, false)) {
      val f       = new Fixture
      val failure = new java.util.concurrent.RejectedExecutionException("synthetic rejection")
      val deadlines = if (rejectSchedule) new Frontend.RpcDeadlineScheduler {
        def schedule(delay: FiniteDuration)(wake: () => Unit): () => Unit = throw failure
      }
      else f.clock
      val worker =
        if (rejectSchedule) f.worker
        else
          new scala.concurrent.ExecutionContext {
            def execute(task: Runnable): Unit         = throw failure
            def reportFailure(error: Throwable): Unit = throw error
          }
      val runtime = new TimerRuntime[Future](deadlines, () => f.clock.now, worker, f.reports, TimerSettings(1))
      runtime.start()
      def render(): Unit = {
        runtime.beginRender()
        runtime.collect(f.target, f.id, 1000000L, false, RestartKey.Stable, (n: Int) => Some(n + 1))
        runtime.prepareRender(); runtime.acceptTimerRender(); runtime.flush()
      }
      render()
      if (!rejectSchedule) { f.clock.advance(1.millis); f.clock.fire() }
      f.reports.errors.map(_._2) shouldBe Vector(failure)
      runtime.snapshot.slots.values.head.phase shouldBe TimerMachine.Phase.Failed
      runtime.snapshot.slots.values.head.entry shouldBe None
      render()
      f.reports.errors.size shouldBe 1
    }
  }

  it should "bound a representative thousand-item list across pending and completed rerenders" in {
    val f = new Fixture(1024)
    import f.*
    val ids = Vector.tabulate(1024)(n => ctx.timerId(s"notice-list:$n"))
    val bean = java.lang.management.ManagementFactory.getThreadMXBean match {
      case value: com.sun.management.ThreadMXBean if value.isThreadAllocatedMemorySupported => Some(value)
      case _                                                                                => None
    }
    def allocated: Long = bean.fold(0L)(_.getThreadAllocatedBytes(Thread.currentThread().threadId()))
    val allocationStart = allocated
    val started         = System.nanoTime()
    render(ids.foreach(id => declaration(id)))
    (1 to 20).foreach(_ => render(ids.reverse.foreach(id => declaration(id))))
    clock.tasks.size shouldBe 1024
    val pendingNanos = System.nanoTime() - started
    clock.advance(1.millis)
    clock.active.foreach(_.fire())
    worker.drain()
    target.lane.size shouldBe 1024
    runtime.snapshot.slots.values.count(_.entry.nonEmpty) shouldBe 1024
    (1 to 1024).foreach(_ => turn())
    runtime.retainedDescriptions shouldBe 0
    val completedStart = System.nanoTime()
    (1 to 20).foreach(_ => render(ids.foreach(id => declaration(id))))
    val completedNanos = System.nanoTime() - completedStart
    target.state shouldBe 1024
    clock.active shouldBe empty
    runtime.retainedDescriptions shouldBe 0
    info(
      s"1024 IDs: pending rerenders=${pendingNanos / 1000000L}ms, completed rerenders=${completedNanos / 1000000L}ms, " +
        s"thread allocations=${allocated - allocationStart} bytes; peak outstanding=1024, completed callbacks retained=0"
    )
  }

  private def proxy[S](
    rc: StatefulRenderContext[Context.Binding[Future, S, Unit]],
    add: (StatefulRenderContext[Context.Binding[Future, S, Unit]], Context.Binding[Future, S, Unit]) => Unit
  ): StatefulRenderContext[Context.Binding[Future, S, Unit]] =
    new StatefulRenderContext[Context.Binding[Future, S, Unit]] {
      def currentContainerId: Id                                = rc.currentContainerId
      def currentId: Id                                         = rc.currentId
      def subsequentId: Id                                      = rc.subsequentId
      def openNode(ns: XmlNs, name: String): Unit               = rc.openNode(ns, name)
      def closeNode(name: String): Unit                         = rc.closeNode(name)
      def setAttr(ns: XmlNs, name: String, value: String): Unit = rc.setAttr(ns, name, value)
      def setStyle(name: String, value: String): Unit           = rc.setStyle(name, value)
      def addTextNode(text: String): Unit                       = rc.addTextNode(text)
      def addMisc(misc: Context.Binding[Future, S, Unit]): Unit = add(this, misc)
    }

  it should "run actual component updates after no-output startup and cancel on public close" in {
    import avocet.dsl.*, avocet.dsl.html.*
    val worker                              = new Worker
    val clock                               = new Deadlines
    val reports                             = new Reports
    val incoming                            = Queue[Future, String]()
    given spoonbill.effect.Reporter         = reports
    given scala.concurrent.ExecutionContext = worker
    val frontend                            = new Frontend[Future](incoming.stream, None)
    val storage                             = StateManager.cached[Future]()
    val ctx                                 = Context[Future, Int, Unit]
    val binding = ctx.timer
      .every(ctx.timerId("tick"), Period.from(1.millis).toOption.get)
      .update(StateUpdate.Modify[Int](_ + 1))
    val app = new ApplicationInstance[Future, Int, Unit](
      Qsid("timer", "startup"),
      frontend,
      storage,
      0,
      state => Html(body(state.toString, binding)),
      Root,
      Router.empty[Future, Int],
      proxy[Int],
      new Scheduler[Future],
      reports,
      PartialFunction.empty,
      Duration.Zero,
      timerDeadlines = Some(clock),
      timerClock = () => clock.now
    )
    result(app.initialize())
    worker.drain()
    result(storage.read[Int](Id.TopLevel)) shouldBe None
    clock.advance(1.millis); clock.fire(); worker.drain()
    result(storage.read[Int](Id.TopLevel)) shouldBe Some(1)
    clock.active.size shouldBe 1
    result(frontend.close())
    val closed = app.destroy()
    worker.drain(); result(closed)
    clock.active shouldBe empty
    reports.errors shouldBe empty
  }
}
