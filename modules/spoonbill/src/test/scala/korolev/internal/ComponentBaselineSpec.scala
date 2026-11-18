package spoonbill.internal

import TimerTestSupport.*
import avocet.{Id, StatefulRenderContext, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

class ComponentBaselineSpec extends AnyFlatSpec with Matchers {
  import avocet.dsl.*
  import avocet.dsl.html.*
  private given Effect[Future]               = Effect.futureEffect
  private val ctx                            = Context[Future, Int, Unit]
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private class App(
    document: Int => avocet.Document.Node[Context.Binding[Future, Int, Unit]],
    val storage: StateManager[Future] = StateManager.cached[Future]()
  ) {
    val worker                              = new Worker
    val reports                             = new Reports
    given Reporter                          = reports
    given scala.concurrent.ExecutionContext = worker
    val frontend                            = new Frontend[Future](Queue[Future, String]().stream, None)
    val app = new ApplicationInstance[Future, Int, Unit](
      Qsid("component-baseline", java.util.UUID.randomUUID().toString),
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
      Duration.Zero
    )
    def start(): Unit = { result(app.initialize()); worker.drain() }
    def change(value: Int): Unit = {
      result(app.topLevelComponentInstance.browserAccess.transition(_ => value)); worker.drain()
    }
    def close(): Unit = { result(frontend.close()); val done = app.destroy(); worker.drain(); result(done) }
  }

  "A component initial loader" should "run once while a fresh instance is still loading across repeated renders" in {
    import ctx.*
    val loaded = Promise[Int]()
    var starts = Vector.empty[Int]
    val child = new Component[Future, Int, Int, Unit]((parameters: Int) => {
      starts :+= parameters
      loaded.future
    }) {
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    val f = new App(state => Html(body(child(state))))
    try {
      f.start()
      starts shouldBe Vector(0)
      f.change(1)
      f.change(2)
      starts shouldBe Vector(0)
    } finally {
      loaded.trySuccess(10)
      f.worker.drain()
      f.close()
    }
  }

  it should "offer the newest parameters to maybeUpdateState after the initial parameters finish loading" in {
    import ctx.*
    val loaded  = Promise[Int]()
    var updates = Vector.empty[(Int, Int)]
    val child = new Component[Future, Int, Int, Unit]((_: Int) => loaded.future) {
      override def maybeUpdateState(parameters: Int, currentState: Int): Option[Future[Int]] = {
        updates :+= parameters -> currentState
        None
      }
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    val f = new App(state => Html(body(child(state))))
    try {
      f.start()
      f.change(1)
      f.change(2)
      updates shouldBe empty
      loaded.success(10)
      f.worker.drain()
      updates shouldBe Vector(2 -> 10)
    } finally {
      loaded.trySuccess(10)
      f.worker.drain()
      f.close()
    }
  }

  it should "retry a failed fresh loader on a later render and retain its original error reporting" in {
    import ctx.*
    for (throws <- List(false, true)) {
      val failure  = new IllegalStateException("initial loader failed")
      var attempts = 0
      val child = new Component[Future, Int, Int, Unit]((_: Int) => {
        attempts += 1
        if (attempts > 1) Future.successful(20)
        else if (throws) throw failure
        else Future.failed(failure)
      }) {
        def render(parameters: Int, state: Int): context.Node = div(state.toString)
      }
      val f = new App(state => Html(body(child(state))))
      try {
        f.start()
        attempts shouldBe 1
        f.reports.errors shouldBe Vector("Unhandled error" -> failure)
        f.change(1)
        attempts shouldBe 2
        f.change(2)
        attempts shouldBe 2
        f.reports.errors shouldBe Vector("Unhandled error" -> failure)
      } finally f.close()
    }
  }

  it should "retry when the effect runtime refuses to observe a fresh loader" in {
    import ctx.*
    val refused    = new IllegalStateException("loader effect startup refused")
    val rejectNext = new java.util.concurrent.atomic.AtomicBoolean(false)
    val effect = new Effect[Future] {
      export Effect.futureEffect.{
        pure, delay, uncancelable, fail, unit, never, fromTry,
        promise, promiseF, flatMap, map, recover, recoverF, start,
        blocking, fork, sequence, run, toFuture
      }
      override def runAsync[A](operation: Future[A])(callback: Either[Throwable, A] => Unit): Unit =
        if (rejectNext.compareAndSet(true, false)) throw refused
        else Effect.futureEffect.runAsync(operation)(callback)
    }
    val pending = Promise[Int]()
    var starts  = 0
    val child = new Component[Future, Int, Int, Unit]((_: Int) => {
      starts += 1
      if (starts == 1) { rejectNext.set(true); pending.future }
      else Future.successful(20)
    }) {
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    given Effect[Future]                    = effect
    val worker                              = new Worker
    given scala.concurrent.ExecutionContext = worker
    val reports                             = new Reports
    given Reporter                          = reports
    val frontend                            = new Frontend[Future](Queue[Future, String]().stream, None)
    val storage                             = StateManager.cached[Future]()
    val changes                             = Queue[Future, (Id, Any, Option[Effect.Promise[Unit]])]()
    val instance = new ComponentInstance[Future, Int, Unit, Int, Int, Unit](
      Id("1_2"),
      Qsid("component", "refused-start"),
      frontend,
      new EventRegistry(frontend),
      storage,
      child,
      changes,
      (rc, _) => rc,
      new Scheduler[Future],
      reports,
      PartialFunction.empty,
      worker
    )
    val rc = new StatefulRenderContext[Context.Binding[Future, Int, Unit]] {
      def currentContainerId: Id                                  = Id.TopLevel
      def currentId: Id                                           = Id.TopLevel
      def subsequentId: Id                                        = Id("1")
      def openNode(ns: XmlNs, name: String): Unit                 = ()
      def closeNode(name: String): Unit                           = ()
      def setAttr(ns: XmlNs, name: String, value: String): Unit   = ()
      def setStyle(name: String, value: String): Unit             = ()
      def addTextNode(text: String): Unit                         = ()
      def addMisc(misc: Context.Binding[Future, Int, Unit]): Unit = ()
    }
    try {
      intercept[IllegalStateException](instance.applyRenderContext(1, rc, result(instance.stateSnapshot))) should
        be theSameInstanceAs refused
      starts shouldBe 1
      instance.applyRenderContext(2, rc, result(instance.stateSnapshot))
      starts shouldBe 2
    } finally {
      result(frontend.close())
      val retired = instance.destroy()
      worker.drain()
      result(retired)
      result(changes.close())
      pending.trySuccess(10)
      worker.drain()
    }
  }

  it should "keep reused-address initialization single-flight across later parameter renders" in {
    import ctx.*
    val loaded = Promise[Int]()
    var starts = Vector.empty[Int]
    val first  = Component[Future, Int, Unit, Unit](10)((_, _, value) => div(value.toString))
    val child = new Component[Future, Int, Int, Unit]((parameters: Int) => {
      starts :+= parameters
      loaded.future
    }) {
      def render(parameters: Int, state: Int): context.Node = div(state.toString)
    }
    val f = new App(state => Html(body(if (state == 0) first(()) else child(state))))
    try {
      f.start()
      f.change(1)
      starts shouldBe Vector(1)
      f.change(2)
      starts shouldBe Vector(1)
    } finally {
      loaded.trySuccess(10)
      f.worker.drain()
      f.close()
    }
  }
}
