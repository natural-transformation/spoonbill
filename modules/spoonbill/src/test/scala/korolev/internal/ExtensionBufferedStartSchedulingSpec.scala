package spoonbill.internal

import java.util.concurrent.RejectedExecutionException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.{Reports, Worker}

class ExtensionBufferedStartSchedulingSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  "Buffered extension startup" should "attempt scheduling once and retry on the next notification after refusal" in {
    val worker     = new Worker
    val reports    = new Reports
    val refused    = new RejectedExecutionException("first startup task")
    var attempts   = 0
    var rejectNext = false
    given ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit = {
        attempts += 1
        if (rejectNext) { rejectNext = false; throw refused }
        worker.execute(task)
      }
      def reportFailure(error: Throwable): Unit = throw error
    }
    var events = Vector.empty[String]
    val observer = Extension[Future, Int, String]("observer")(_ =>
      Future.successful(
        Extension.Handlers[Future, Int, String](
          onState = value => { events :+= s"state($value)"; Future.unit },
          onMessage = value => { events :+= s"message($value)"; Future.unit }
        )
      )
    )
    val bound = new ExtensionRuntime.Bound[Future] {
      def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
    }
    val runtime = new ExtensionRuntime[Future, Int, String](List(observer), reports, bound, None)
    Await.result(runtime.setup(null, 7, () => Future.unit), 5.seconds)
    runtime.message("first")
    runtime.message("second")
    attempts shouldBe 0

    rejectNext = true
    Await.result(runtime.start(), 5.seconds)
    attempts shouldBe 1
    worker.pending shouldBe 0
    events shouldBe empty
    reports.errors shouldBe Vector("Extension delivery could not be scheduled" -> refused)

    runtime.message("third")
    attempts shouldBe 2
    worker.drain()
    events shouldBe Vector("state(7)", "message(first)", "message(second)", "message(third)")
    reports.errors shouldBe Vector("Extension delivery could not be scheduled" -> refused)
    val destroyed = runtime.destroy()
    worker.drain()
    Await.result(destroyed, 5.seconds)
  }
}
