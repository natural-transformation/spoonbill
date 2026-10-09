package spoonbill.internal

import avocet.Id
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.data.Bytes
import spoonbill.Context
import spoonbill.server.SessionAccessDenied

class BrowserRpcLifecycleSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter

  private class ManualDeadlines extends Frontend.RpcDeadlineScheduler {
    private case class Task(expire: () => Unit, canceled: AtomicBoolean)
    private val next = new AtomicInteger(0)
    private val tasks = TrieMap.empty[Int, Task]
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
      val task = Task(expire, new AtomicBoolean(false))
      tasks.put(next.getAndIncrement(), task)
      () => { task.canceled.set(true); () }
    }
    def active: Int = tasks.values.count(task => !task.canceled.get())
    def expireNext(): Unit = {
      val task = tasks.toVector.sortBy(_._1).collectFirst { case (_, value) if !value.canceled.get() => value }
        .getOrElse(fail("No pending deadline"))
      task.expire()
    }
    def replayAllExpiredCallbacks(): Unit = tasks.values.toList.foreach(_.expire())
  }

  private def frontend(clock: ManualDeadlines, incoming: Queue[Future, String]): Frontend[Future] =
    new Frontend[Future](incoming.stream, Some(2), authorize = Some(() => Future.unit), rpcDeadlineScheduler = Some(clock))

  private def descriptor(frame: String): String =
    "\"([^\"]*)\"".r.findFirstMatchIn(frame).map(_.group(1)).getOrElse(fail("Missing descriptor"))

  private def emitted(frontend: Frontend[Future]): Future[String] =
    frontend.outgoingMessages.pull().map(_.getOrElse(fail("Missing request frame")))

  "Browser RPC ownership" should "register before an immediate reply and retire its deadline" in {
    val clock = new ManualDeadlines
    val requests = new OwnedBrowserRequests[Future, String](clock, 8, () => new SessionAccessDenied)
    val result = requests.request("request", 1.second, () => new IllegalStateException("timeout")) { _ =>
      requests.complete("request", Right("answer")).map(_ => ())
    }
    result.flatMap { value =>
      value shouldBe "answer"
      requests.pendingCount shouldBe 0
      clock.active shouldBe 0
      clock.replayAllExpiredCallbacks()
      requests.complete("request", Right("late")).map(_ shouldBe false)
    }
  }

  it should "fail the actual evalJs future and run its handler continuation on close" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val rpcRef = new AtomicReference(Option.empty[Future[String]])
    val handlerFinished = Promise[Unit]()
    val action = browser.runUserAction {
      val rpc = browser.evalJs("return 42")
      rpcRef.set(Some(rpc))
      rpc.recoverWith { case error => handlerFinished.trySuccess(()); Future.failed(error) }
    }
    for {
      frame <- emitted(browser)
      _ = frame should startWith("[10,")
      _ <- browser.close()
      rpcFailure <- rpcRef.get().getOrElse(fail("Handler did not create its RPC")).failed
      _ <- handlerFinished.future
      actionFailure <- action.failed
    } yield {
      rpcFailure shouldBe a[SessionAccessDenied]
      actionFailure shouldBe a[SessionAccessDenied]
      clock.active shouldBe 0
    }
  }

  it should "settle registration when close wins before deadline installation" in {
    val canceled = new AtomicBoolean(false)
    val sent = new AtomicBoolean(false)
    val owner = new AtomicReference(Option.empty[OwnedBrowserRequests[Future, String]])
    val deadline = new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
        owner.get().getOrElse(fail("Missing owner")).close()
        () => { canceled.set(true); () }
      }
    }
    val requests = new OwnedBrowserRequests[Future, String](deadline, 8, () => new SessionAccessDenied)
    owner.set(Some(requests))
    val result = requests.request("request", 1.second, () => new IllegalStateException("timeout")) { _ =>
      sent.set(true)
      Future.unit
    }
    result.failed.map { error =>
      error shouldBe a[SessionAccessDenied]
      sent.get() shouldBe false
      canceled.get() shouldBe true
      requests.pendingCount shouldBe 0
    }
  }

  it should "settle either ordering of concurrent close and registration without retaining a request" in {
    val clock = new ManualDeadlines
    val requests = new OwnedBrowserRequests[Future, String](clock, 8, () => new SessionAccessDenied)
    val gate = Promise[Unit]()
    val result = gate.future.flatMap { _ =>
      requests.request("request", 1.second, () => new IllegalStateException("timeout"))(_ => Future.unit)
    }(ExecutionContext.global)
    val closed = gate.future.flatMap(_ => requests.close())(ExecutionContext.global)
    gate.success(())
    for {
      _ <- closed
      error <- result.failed
      late <- requests.complete("request", Right("late"))
    } yield {
      error shouldBe a[SessionAccessDenied]
      requests.pendingCount shouldBe 0
      clock.active shouldBe 0
      late shouldBe false
    }
  }

  it should "give a bounded timeout to evalJs and unblock the next serial action" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val waiting = browser.runUserAction(browser.evalJs("return neverCompletes"))
    val following = browser.runUserAction(Future.successful("next action"))
    for {
      _ <- emitted(browser)
      _ = clock.expireNext()
      error <- waiting.failed
      next <- following
      _ <- browser.close()
    } yield {
      error shouldBe a[Frontend.ClientSideException]
      error.getMessage shouldBe "EvalJs timed out"
      next shouldBe "next action"
      clock.active shouldBe 0
    }
  }

  it should "expire the actual response wait even while emission itself is stalled" in {
    val clock = new ManualDeadlines
    val requests = new OwnedBrowserRequests[Future, String](clock, 8, () => new SessionAccessDenied)
    val send = Promise[Unit]()
    val result = requests.request("request", 1.second, () => Frontend.ClientSideException("RPC timed out"))(_ => send.future)
    clock.expireNext()
    result.failed.flatMap { error =>
      error.getMessage shouldBe "RPC timed out"
      requests.pendingCount shouldBe 0
      send.success(())
      requests.complete("request", Right("late")).map(_ shouldBe false)
    }
  }

  it should "reject excess requests without disturbing an admitted request" in {
    val clock = new ManualDeadlines
    val requests = new OwnedBrowserRequests[Future, String](clock, 1, () => new SessionAccessDenied)
    val first = requests.request("first", 1.second, () => new IllegalStateException("timeout"))(_ => Future.unit)
    val excess = requests.request("second", 1.second, () => new IllegalStateException("timeout"))(_ => Future.unit)
    for {
      error <- excess.failed
      _ = requests.pendingCount shouldBe 1
      _ <- requests.complete("first", Right("retained"))
      value <- first
    } yield {
      error.getMessage shouldBe "Too many pending browser requests"
      value shouldBe "retained"
      clock.active shouldBe 0
    }
  }

  it should "drop a request which expires while still queued for browser emission" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val request = browser.evalJs("must not execute")
    clock.expireNext()
    for {
      error <- request.failed
      _ <- browser.focus(Id("1"))
      frame <- emitted(browser)
      _ <- browser.close()
    } yield {
      error shouldBe a[Frontend.ClientSideException]
      frame should startWith("[5,")
    }
  }

  it should "fail closed on a saturated output queue without retaining an RPC send waiter" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    for {
      _ <- Future.sequence(Vector.fill(256)(browser.focus(Id("1"))))
      error <- browser.evalJs("no output capacity").failed
      remaining <- browser.outgoingMessages.pull()
    } yield {
      error shouldBe a[SessionAccessDenied]
      remaining shouldBe None
      clock.active shouldBe 0
    }
  }

  it should "ignore late and duplicate replies while a subsequent request still succeeds" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val first = browser.evalJs("return 1")
    for {
      frame <- emitted(browser)
      id = descriptor(frame)
      _ = clock.expireNext()
      _ <- first.failed
      _ <- incoming.enqueue(s"""[4,"$id:0:late"]""")
      _ <- incoming.enqueue("[6]")
      heartbeat <- emitted(browser)
      _ = heartbeat shouldBe "[16]"
      second = browser.evalJs("return 2")
      nextFrame <- emitted(browser)
      nextId = descriptor(nextFrame)
      _ = nextId should not be id
      _ <- incoming.enqueue(s"""[4,"$id:0:duplicate"]""")
      _ <- incoming.enqueue(s"""[4,"$nextId:0:2"]""")
      value <- second
      _ <- browser.close()
    } yield {
      value shouldBe "2"
      clock.active shouldBe 0
    }
  }

  it should "keep response families separate and redact browser failure payloads" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val request = browser.evalJs("return 42")
    for {
      frame <- emitted(browser)
      id = descriptor(frame)
      _ <- incoming.enqueue(s"""[2,"$id:0:wrong-family"]""")
      _ <- incoming.enqueue(s"""[4,"$id:1:synthetic-private-error"]""")
      error <- request.failed
      _ <- browser.close()
    } yield {
      error.getMessage shouldBe "Browser evaluation failed"
      error.toString should not include "synthetic-private-error"
      clock.active shouldBe 0
    }
  }

  it should "still deliver ordinary property and event-data replies" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val property = browser.extractProperty(Id("1"), "value")
    for {
      propertyFrame <- emitted(browser)
      propertyId = descriptor(propertyFrame)
      _ <- incoming.enqueue(s"""[2,"$propertyId:0:value"]""")
      propertyValue <- property
      event = browser.extractEventData(Frontend.DomEventMessage(0, Id("1"), "click"))
      eventFrame <- emitted(browser)
      eventId = descriptor(eventFrame)
      _ <- incoming.enqueue(s"""[5,"$eventId:{}"]""")
      eventValue <- event
      _ <- browser.close()
    } yield {
      propertyValue shouldBe "value"
      eventValue shouldBe "{}"
      clock.active shouldBe 0
    }
  }

  it should "close every request family and cancel an unowned late file stream" in {
    val clock = new ManualDeadlines
    val incoming = Queue[Future, String]()
    val browser = frontend(clock, incoming)
    val form = browser.uploadForm(Id("1"))
    val names = browser.listFiles(Id("2"))
    val file = browser.uploadFile(Id("3"), Context.FileHandler("fixture.txt", 1L)(new Context.ElementId(Some("upload"))))
    val property = browser.extractProperty(Id("4"), "value")
    val event = browser.extractEventData(Frontend.DomEventMessage(0, Id("5"), "input"))
    val canceled = new AtomicBoolean(false)
    val lateFile = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.successful(None)
      def cancel(): Future[Unit] = { canceled.set(true); Future.unit }
    }
    for {
      _ <- emitted(browser)
      _ <- emitted(browser)
      _ <- emitted(browser)
      _ <- emitted(browser)
      _ <- emitted(browser)
      _ <- browser.close()
      formError <- form.failed
      namesError <- names.failed
      fileError <- file.failed
      propertyError <- property.failed
      eventError <- event.failed
      _ <- browser.resolveFile("unregistered", lateFile)
    } yield {
      formError shouldBe a[SessionAccessDenied]
      namesError shouldBe a[SessionAccessDenied]
      fileError shouldBe a[SessionAccessDenied]
      propertyError shouldBe a[SessionAccessDenied]
      eventError shouldBe a[SessionAccessDenied]
      canceled.get() shouldBe true
      clock.active shouldBe 0
    }
  }

  it should "retire send failures without letting a late response recreate the entry" in {
    val clock = new ManualDeadlines
    val requests = new OwnedBrowserRequests[Future, String](clock, 8, () => new SessionAccessDenied)
    val failure = new IllegalStateException("Send failed")
    val result = requests.request("request", 1.second, () => new IllegalStateException("timeout"))(_ => Future.failed(failure))
    for {
      error <- result.failed
      late <- requests.complete("request", Right("late"))
    } yield {
      error shouldBe failure
      late shouldBe false
      requests.pendingCount shouldBe 0
      clock.active shouldBe 0
    }
  }

  it should "reject settings which disable a deadline or remove the outstanding-request bound" in {
    intercept[IllegalArgumentException](Frontend.RpcSettings(requestTimeout = Duration.Zero))
    intercept[IllegalArgumentException](Frontend.RpcSettings(fileTimeout = 6.minutes))
    intercept[IllegalArgumentException](Frontend.RpcSettings(maxPendingPerKind = 0))
    succeed
  }
}
