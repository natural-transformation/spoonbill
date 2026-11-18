package spoonbill.http

import java.util.concurrent.atomic.AtomicLong
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class RequestDeadlineLifecycleSpec extends AnyFlatSpec with Matchers {
  private val duration = 1.minute
  private def withDeadline(run: (RequestDeadline, AtomicLong) => Unit): Unit = {
    val now      = new AtomicLong(0L)
    val deadline = new RequestDeadline(duration, () => fail("A completed deadline must not expire"), () => now.get())
    try run(deadline, now)
    finally deadline.close()
  }

  "An HTTP deadline" should "close permanently when completion precedes start" in withDeadline { (deadline, _) =>
    deadline.complete() shouldBe false
    deadline.start()
    deadline.complete() shouldBe false
  }

  it should "keep the first start's absolute boundary when start is repeated" in withDeadline { (deadline, now) =>
    deadline.start()
    now.set(duration.toNanos / 2)
    deadline.start()
    now.set(duration.toNanos)
    deadline.complete() shouldBe false
    now.set(0L)
    deadline.start()
    deadline.complete() shouldBe false
  }

  it should "allow a fresh interval after a timely completion and close on a duplicate completion" in withDeadline {
    (deadline, now) =>
      deadline.start()
      now.set(duration.toNanos - 1)
      deadline.complete() shouldBe true
      now.set(duration.toNanos * 2)
      deadline.start()
      now.set(duration.toNanos * 3 - 1)
      deadline.complete() shouldBe true
      deadline.complete() shouldBe false
      deadline.start()
      deadline.complete() shouldBe false
  }

  it should "use elapsed monotonic time across signed wrap" in withDeadline { (deadline, now) =>
    val start = Long.MaxValue - duration.toNanos / 2
    now.set(start)
    deadline.start()
    now.set(start + duration.toNanos - 1)
    deadline.complete() shouldBe true
    deadline.start()
    now.set(now.get() + duration.toNanos)
    deadline.complete() shouldBe false
  }

  it should "make close idempotent before start, while armed and after completion without consulting the clock" in {
    for (phase <- 0 to 2) {
      var reads    = 0
      val deadline = new RequestDeadline(duration, () => fail("Closed deadline expired"), () => { reads += 1; 0L })
      if (phase > 0) deadline.start()
      if (phase > 1) deadline.complete() shouldBe true
      val before = reads
      deadline.close(); deadline.close(); deadline.start()
      deadline.complete() shouldBe false
      reads shouldBe before
    }
  }
}
