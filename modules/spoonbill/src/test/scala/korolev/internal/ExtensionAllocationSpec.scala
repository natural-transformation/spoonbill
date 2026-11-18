package spoonbill.internal

import TimerTestSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect

/**
 * A repeatable allocation observation for the pilot checkpoint, not a latency
 * benchmark.
 */
class ExtensionAllocationSpec extends AnyFlatSpec with Matchers {
  "Extension delivery" should "measure a three-extension burst while retaining no waiting work" in {
    given Effect[Future]   = Effect.futureEffect
    val worker             = new Worker
    given ExecutionContext = worker
    var delivered          = 0
    val extensions = (1 to 3)
      .map(n =>
        Extension[Future, Int, Unit](s"observer-$n")(_ =>
          Future.successful(
            Extension.Handlers[Future, Int, Unit](onState = _ => { delivered += 1; Future.unit })
          )
        )
      )
      .toList
    val bound = new ExtensionRuntime.Bound[Future] {
      def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
    }
    val runtime = new ExtensionRuntime[Future, Int, Unit](extensions, new Reports, bound, None)
    Await.result(runtime.setup(null, 0, () => Future.unit), 5.seconds)
    Await.result(runtime.start(), 5.seconds)
    worker.drain()
    def burst(): Unit = { (1 to 10000).foreach(runtime.state); worker.drain() }
    (1 to 5).foreach(_ => burst())
    val bean = java.lang.management.ManagementFactory.getThreadMXBean match {
      case value: com.sun.management.ThreadMXBean if value.isThreadAllocatedMemorySupported => Some(value)
      case _                                                                                => None
    }
    bean.foreach(_.setThreadAllocatedMemoryEnabled(true))
    def allocated = bean.fold(0L)(_.getThreadAllocatedBytes(Thread.currentThread().threadId()))
    val before    = allocated
    burst()
    val bytes = allocated - before
    delivered shouldBe 180003
    runtime.pending shouldBe 0
    runtime.stop()
    val destroyed = runtime.destroy()
    worker.drain()
    Await.result(destroyed, 5.seconds)
    info(
      s"Three extensions, 10000 notifications: thread allocation=$bytes bytes, ${bytes / 10000} bytes/notification; counter available=${bean.nonEmpty}"
    )
  }
}
