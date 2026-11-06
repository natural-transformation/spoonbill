package spoonbill.internal

import avocet.{Id, StatefulRenderContext, XmlNs}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.effect.{AsyncTable, Effect, Queue, Reporter, Scheduler}
import spoonbill.state.{StateDeserializer, StateManager, StateSerializer}

/** Same real component/driver contract under Future-like and lazy adapters. */
object TimerAdapterChecks {
  private class Deadlines extends Frontend.RpcDeadlineScheduler {
    val now   = new AtomicLong(0L)
    val tasks = new LinkedBlockingQueue[(FiniteDuration, () => Unit)]()
    def schedule(delay: FiniteDuration)(wake: () => Unit): () => Unit = {
      val canceled = new java.util.concurrent.atomic.AtomicBoolean(false)
      tasks.add(delay -> (() => if (!canceled.get()) wake()))
      () => canceled.set(true)
    }
    def fire(): Unit = {
      val task = tasks.poll(10, TimeUnit.SECONDS)
      assert(task != null, "Missing scheduled timer")
      now.addAndGet(task._1.toNanos)
      task._2()
    }
  }
  private class Reports extends Reporter {
    val failures                                                      = new LinkedBlockingQueue[Throwable]()
    def error(message: String, error: Throwable): Unit                = { failures.add(error); () }
    def error(message: String): Unit                                  = error(message, new RuntimeException(message))
    def warning(message: String, error: Throwable): Unit              = ()
    def warning(message: String): Unit                                = ()
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = ()
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  }
  private def run[F[_]: Effect, A](effect: F[A]): A = Await.result(Effect[F].toFuture(effect), 10.seconds)

  def legacy[F[_]: Effect]()(implicit ec: ExecutionContext): Unit = {
    val clock          = new Deadlines
    val reports        = new Reports
    val callbackThread = new AtomicReference[Thread]()
    val failure        = new IllegalStateException("synthetic delay failure")
    val binding = Context.Delay[F, Int, Any](
      1.millis,
      _ => {
        callbackThread.set(Thread.currentThread())
        throw failure
      }
    )
    val delay = new ComponentInstance.DelayInstance(binding, new Scheduler[F], reports, ec, Some(clock))
    delay.start(null, () => Effect[F].unit)
    assert(callbackThread.get() == null)
    clock.fire()
    assert(reports.failures.poll(10, TimeUnit.SECONDS) eq failure)
    assert(callbackThread.get() ne Thread.currentThread(), "Constructor ran on deadline caller")
    assert(reports.failures.isEmpty, "Failure reported more than once")
  }

  def periodic[F[_]: Effect, S: StateSerializer: StateDeserializer](
    initial: S,
    binding: Context.Binding[F, S, Any],
    first: S,
    second: S,
    waitForReadiness: Boolean = false,
    holdComputationAtClose: Boolean = false
  )(implicit ec: ExecutionContext): Unit = {
    import avocet.dsl.*, avocet.dsl.html.*
    val F          = Effect[F]
    val clock      = new Deadlines
    val reports    = new Reports
    val ready      = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
    val held       = AsyncTable.unsafeCreateEmpty[F, Unit, S]
    given Reporter = reports
    val writes     = new LinkedBlockingQueue[S]()
    val storage    = StateManager.cached[F](onWrite = (_, value) => F.delay { writes.add(value.asInstanceOf[S]); () })
    val frontend   = new Frontend[F](Queue[F, String]().stream, None)
    def proxy(
      rc: StatefulRenderContext[Context.Binding[F, S, Any]],
      add: (StatefulRenderContext[Context.Binding[F, S, Any]], Context.Binding[F, S, Any]) => Unit
    ): StatefulRenderContext[Context.Binding[F, S, Any]] = new StatefulRenderContext[Context.Binding[F, S, Any]] {
      def currentContainerId: Id                                = rc.currentContainerId
      def currentId: Id                                         = rc.currentId
      def subsequentId: Id                                      = rc.subsequentId
      def openNode(ns: XmlNs, name: String): Unit               = rc.openNode(ns, name)
      def closeNode(name: String): Unit                         = rc.closeNode(name)
      def setAttr(ns: XmlNs, name: String, value: String): Unit = rc.setAttr(ns, name, value)
      def setStyle(name: String, value: String): Unit           = rc.setStyle(name, value)
      def addTextNode(text: String): Unit                       = rc.addTextNode(text)
      def addMisc(misc: Context.Binding[F, S, Any]): Unit       = add(this, misc)
    }
    val app = new ApplicationInstance[F, S, Any](
      Qsid("adapter", "timers"),
      frontend,
      storage,
      initial,
      state => Html(body(state.toString, binding)),
      Root,
      Router.empty[F, S],
      proxy,
      new Scheduler[F],
      reports,
      PartialFunction.empty,
      Duration.Zero,
      awaitReady = if (waitForReadiness) Some(() => ready.get(())) else None,
      timerDeadlines = Some(clock),
      timerClock = () => clock.now.get()
    )
    try {
      run(app.initialize())
      assert(writes.isEmpty, "Inert binding changed state during startup")
      val firstNotification = if (waitForReadiness) {
        assert(clock.tasks.isEmpty, "Timer armed before readiness")
        val stream       = run(app.stateStream)
        val notification = F.toFuture(stream.pull())
        run(ready.put((), ()))
        Some(notification)
      } else None
      clock.fire()
      assert(writes.poll(10, TimeUnit.SECONDS) == first)
      firstNotification.foreach(value => assert(Await.result(value, 10.seconds).exists(_._2 == first)))
      clock.fire()
      assert(writes.poll(10, TimeUnit.SECONDS) == second)
      assert(reports.failures.isEmpty)
      if (holdComputationAtClose) {
        val entered = new java.util.concurrent.CountDownLatch(1)
        val transition = F.toFuture(app.topLevelComponentInstance.browserAccess.transitionForceAsync { _ =>
          entered.countDown()
          held.get(())
        })
        assert(entered.await(10, TimeUnit.SECONDS), "Application computation did not enter its lane")
        try {
          run(frontend.close())
          run(app.destroy())
          assert(!transition.isCompleted, "Held application computation unexpectedly completed")
        } finally run(held.putEither((), Right(initial), silent = true))
        assert(Await.result(transition.failed, 10.seconds).isInstanceOf[spoonbill.server.SessionAccessDenied])
        assert(writes.isEmpty, "Retired computation wrote a late result")
      }
    } finally {
      if (waitForReadiness) run(ready.putEither((), Left(new spoonbill.server.SessionAccessDenied), silent = true))
      run(frontend.close())
      run(app.destroy())
    }
  }
}
