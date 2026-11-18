package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Try
import spoonbill.effect.AsyncTable.{AlreadyContainsKeyException, RemovedBeforePutException}

class AsyncTableLifecycleSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  "Async table lifecycle" should "settle absent keys without creating a waiting entry" in {
    val table = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    await(table.settled("key"))
    var fills = 0
    await(table.getFill("key") { fills += 1; Future.successful(7) }) shouldBe 7
    fills shouldBe 1
    await(table.getImmediately("key")) shouldBe Some(7)
  }

  it should "share one fill and replay the completed result" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val factory = Promise[Int]()
    var fills   = 0
    val first   = table.getFill("key") { fills += 1; factory.future }
    val second  = table.getFill("key") { fills += 1; Future.successful(99) }
    val settled = table.settled("key")
    settled.isCompleted shouldBe false
    factory.success(7)
    await(first) shouldBe 7
    await(second) shouldBe 7
    await(settled)
    await(table.getFill("key") { fills += 1; Future.successful(99) }) shouldBe 7
    fills shouldBe 1
  }

  it should "let an explicit failure settle all waiters and preserve it on silent duplicate puts" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val failure = new IllegalStateException("failure")
    val first   = table.get("key")
    val second  = table.get("key")
    val settled = table.settled("key")
    await(table.fail("key", failure))
    await(first.failed) should be theSameInstanceAs failure
    await(second.failed) should be theSameInstanceAs failure
    await(settled)
    await(table.putEither("key", Right(99), silent = true))
    await(table.get("key").failed) should be theSameInstanceAs failure
    await(table.getImmediately("key")) shouldBe None
    await(table.put("key", 99).failed) shouldBe AlreadyContainsKeyException("key")
    await(table.remove("key"))
    await(table.put("key", 1))
    await(table.get("key")) shouldBe 1
  }

  it should "complete removal waiters without treating the absent key as settled data" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val value   = table.get("key")
    val settled = table.settled("key")
    await(table.remove("key"))
    await(value.failed) shouldBe RemovedBeforePutException("key")
    await(settled)
    await(table.getImmediately("key")) shouldBe None
    await(table.getFill("key")(Future.successful(8))) shouldBe 8
  }

  it should "publish a failed fill to joined and later readers" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val factory = Promise[Int]()
    val failure = new IllegalStateException("factory failed")
    val first   = table.getFill("key")(factory.future)
    val joined  = table.get("key")
    val settled = table.settled("key")
    factory.failure(failure)
    await(first.failed) should be theSameInstanceAs failure
    joined.isCompleted shouldBe true
    await(joined.failed) should be theSameInstanceAs failure
    await(settled)
    await(table.get("key").failed) should be theSameInstanceAs failure
  }

  it should "capture a synchronous factory throw and cache it like an explicit failure" in {
    val table     = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val failure   = new IllegalStateException("factory threw")
    val attempted = Try(table.getFill("key")(throw failure))
    attempted.isSuccess shouldBe true
    await(attempted.get.failed) should be theSameInstanceAs failure
    await(table.get("key").failed) should be theSameInstanceAs failure
    await(table.settled("key"))
  }

  it should "preserve publication conflicts without replacing the winning explicit put" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val factory = Promise[Int]()
    val filling = table.getFill("key")(factory.future)
    await(table.put("key", 7))
    factory.success(8)
    await(filling.failed) shouldBe AlreadyContainsKeyException("key")
    await(table.get("key")) shouldBe 7
  }

  it should "preserve explicit put-on-absent behavior when a fill finishes after removal" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val factory = Promise[Int]()
    val filling = table.getFill("key")(factory.future)
    await(table.remove("key"))
    factory.success(8)
    await(filling.failed) shouldBe RemovedBeforePutException("key")
    await(table.get("key")) shouldBe 8
  }

  it should "preserve the factory error when the factory already published that failure" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val failure = new IllegalStateException("already published")
    val filling = table.getFill("key") {
      await(table.fail("key", failure))
      Future.failed(failure)
    }
    await(filling.failed) should be theSameInstanceAs failure
    await(table.get("key").failed) should be theSameInstanceAs failure
  }

  it should "preserve an external value while returning the original failed factory outcome" in {
    val table   = AsyncTable.unsafeCreateEmpty[Future, String, Int]
    val factory = Promise[Int]()
    val failure = new IllegalStateException("factory failed after put")
    val filling = table.getFill("key")(factory.future)
    await(table.put("key", 7))
    factory.failure(failure)
    await(filling.failed) should be theSameInstanceAs failure
    await(table.get("key")) shouldBe 7
  }
}
