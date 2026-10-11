package spoonbill.internal

import avocet.Id
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.server.SessionAccessDenied
import spoonbill.internal.Frontend.OutputAdmission

/** The admission receiver gets the final immutable value, including values
  * produced after an awaited browser/stream operation. Preliminary checks do
  * not grant permission to emit a different future value.
  */
class ClusteredOutputAdmissionSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[Future] = Effect.futureEffect
  private given Reporter = Reporter.PrintReporter
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private def browser(admission: OutputAdmission[Future]): Frontend[Future] =
    new Frontend[Future](Queue[Future, String]().stream, None,
      authorize = Some(() => Future.unit), outputAdmission = Some(admission))

  "Combined output admission" should "check the fixed output exactly once and deny a queued frame after takeover" in {
    val current = new AtomicBoolean(true)
    val checks = new AtomicInteger()
    val preliminary = new AtomicInteger()
    val fallback = new OutputAdmission[Future] {
      def apply[A](output: A): Future[A] = Future.failed(new AssertionError("Unexpected duplicate output admission"))
    }
    val frontend = new Frontend[Future](Queue[Future, String]().stream, None,
      authorize = Some(() => { preliminary.incrementAndGet(); Future.failed(new AssertionError("Unexpected preliminary check")) }),
      captureOutputAuthorization = Some(() => Future.successful(new Frontend.FusedOutputAuthorization[Future] {
        def apply(): Future[Unit] = { preliminary.incrementAndGet(); Future.failed(new AssertionError("Unexpected producer check")) }
        def apply[A](output: A): Future[A] = {
          checks.incrementAndGet()
          if (current.get()) Future.successful(output) else Future.failed(new SessionAccessDenied)
        }
      })), outputAdmission = Some(fallback))
    try {
      await(frontend.focus(Id("1")))
      checks.get() shouldBe 0 // Enqueueing is not an authority receipt.
      await(frontend.outgoingMessages.pull()).get should include("1")
      checks.get() shouldBe 1
      await(frontend.focus(Id("2")))
      current.set(false)
      intercept[SessionAccessDenied](await(frontend.outgoingMessages.pull()))
      checks.get() shouldBe 2
      preliminary.get() shouldBe 0
    } finally await(frontend.close())
  }

  "Clustered output" should "reject a completed frame when takeover wins after the preliminary checks" in {
    val entered = Promise[String]()
    val resume = Promise[Unit]()
    val current = new AtomicBoolean(true)
    val frontend = browser(new OutputAdmission[Future] {
      def apply[A](output: A): Future[A] = {
        entered.success(output.asInstanceOf[String])
        resume.future.flatMap(_ => if (current.get()) Future.successful(output) else Future.failed(new SessionAccessDenied))
      }
    })
    try {
      await(frontend.focus(Id("1")))
      val delivered = frontend.outgoingMessages.pull()
      await(entered.future) should include("1")
      delivered.isCompleted shouldBe false
      current.set(false)
      resume.success(())
      intercept[SessionAccessDenied](await(delivered))
    } finally await(frontend.close())
  }

  it should "return only the value admitted before takeover when its settlement response is delayed" in {
    val entered = Promise[String]()
    val acknowledged = Promise[Unit]()
    val current = new AtomicBoolean(true)
    val frontend = browser(new OutputAdmission[Future] {
      def apply[A](output: A): Future[A] = {
        if (!current.get()) Future.failed(new SessionAccessDenied)
        else {
          entered.success(output.asInstanceOf[String])
          acknowledged.future.map(_ => output)
        }
      }
    })
    try {
      await(frontend.focus(Id("1")))
      val delivered = frontend.outgoingMessages.pull()
      val admitted = await(entered.future)
      current.set(false)
      acknowledged.success(())
      await(delivered) shouldBe Some(admitted)
      await(frontend.focus(Id("2")))
      intercept[SessionAccessDenied](await(frontend.outgoingMessages.pull()))
    } finally await(frontend.close())
  }

  it should "fence a download chunk after its producer finishes" in {
    val current = new AtomicBoolean(true)
    val frontend = browser(new OutputAdmission[Future] {
      def apply[A](output: A): Future[A] =
        if (current.get()) Future.successful(output) else Future.failed(new SessionAccessDenied)
    })
    val chunks = Queue[Future, Bytes]()
    try {
      await(frontend.downloadFile("synthetic.txt", chunks.stream, None, "text/plain"))
      val frame = await(frontend.outgoingMessages.pull()).get
      val descriptor = "\"([^\"]+)\"".r.findFirstMatchIn(frame).get.group(1)
      val download = await(frontend.resolveFileDownload(descriptor)).get
      val pending = download.stream.pull()
      current.set(false)
      await(chunks.enqueue(Bytes.wrap("synthetic chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
      intercept[SessionAccessDenied](await(pending))
    } finally {
      await(frontend.close())
      await(chunks.close())
    }
  }
}
