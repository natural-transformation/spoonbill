package spoonbill.internal

import avocet.{Document, XmlNs}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

class ApplicationLifecycleSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private class Fixture {
    val worker = new TimerTestSupport.Worker
    val reports = new TimerTestSupport.Reports
    given scala.concurrent.ExecutionContext = worker
    given Reporter = reports
    var authorization: Future[Unit] = Future.unit
    val renders = new AtomicInteger()
    val frontend = new Frontend[Future](Queue[Future, String]().stream, None,
      authorize = Some(() => authorization))
    val application = new ApplicationInstance[Future, Int, Unit](
      Qsid("application-lifecycle", java.util.UUID.randomUUID().toString), frontend,
      StateManager.cached[Future](), 0,
      state => Document.Node[Context.Binding[Future, Int, Unit]] { rc =>
        renders.incrementAndGet()
        rc.openNode(XmlNs.html, "html")
        rc.openNode(XmlNs.html, "body")
        rc.addTextNode(state.toString)
        rc.closeNode("body")
        rc.closeNode("html")
      },
      Root, Router.empty[Future, Int], (rc, _) => rc,
      new Scheduler[Future], reports, PartialFunction.empty, Duration.Zero)

    def initialize(): Future[Unit] = application.initialize()
    def close(): Unit = {
      authorization = Future.unit
      result(frontend.close())
      val done = application.destroy()
      worker.drain()
      result(done)
    }
  }

  "Application initialization" should "start one render and one set of consumers for repeated calls" in {
    val fixture = new Fixture
    try {
      result(fixture.initialize())
      fixture.worker.drain()
      result(fixture.initialize())
      fixture.renders.get() shouldBe 1
    } finally fixture.close()
  }

  it should "join an initialization that is still waiting for authorization" in {
    val fixture = new Fixture
    val gate = Promise[Unit]()
    fixture.authorization = gate.future
    try {
      val first = fixture.initialize()
      val second = fixture.initialize()
      first.isCompleted shouldBe false
      second.isCompleted shouldBe false
      fixture.renders.get() shouldBe 0
      gate.success(())
      result(first)
      result(second)
      fixture.renders.get() shouldBe 1
    } finally { gate.trySuccess(()); fixture.close() }
  }

  it should "reject startup after destruction without evaluating the renderer" in {
    val fixture = new Fixture
    try {
      val destroyed = fixture.application.destroy()
      fixture.worker.drain()
      result(destroyed)
      result(fixture.initialize().failed) shouldBe a[SessionAccessDenied]
      fixture.renders.get() shouldBe 0
    } finally fixture.close()
  }

  it should "reject a delayed authorization after destruction and release joined callers" in {
    val fixture = new Fixture
    val gate = Promise[Unit]()
    fixture.authorization = gate.future
    try {
      val first = fixture.initialize()
      val joined = fixture.initialize()
      val destroyed = fixture.application.destroy()
      fixture.worker.drain()
      result(destroyed)
      result(joined.failed) shouldBe a[SessionAccessDenied]
      gate.success(())
      result(first.failed) shouldBe a[SessionAccessDenied]
      fixture.renders.get() shouldBe 0
    } finally { gate.trySuccess(()); fixture.close() }
  }

  it should "replay the first startup failure without another authorization or render" in {
    val fixture = new Fixture
    val failure = new IllegalStateException("synthetic startup refusal")
    fixture.authorization = Future.failed(failure)
    try {
      result(fixture.initialize().failed) shouldBe failure
      fixture.authorization = Future.unit
      result(fixture.initialize().failed) shouldBe failure
      fixture.renders.get() shouldBe 0
    } finally fixture.close()
  }
}
