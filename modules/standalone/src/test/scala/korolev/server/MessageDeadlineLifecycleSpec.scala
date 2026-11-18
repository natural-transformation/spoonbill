package spoonbill.server

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class MessageDeadlineLifecycleSpec extends AnyFlatSpec with Matchers {
  private val duration = 1.minute
  private def withDeadline(run: (MessageDeadline, AtomicLong) => Unit): Unit = {
    val now      = new AtomicLong(0L)
    val deadline = new MessageDeadline(duration, () => fail("A completed deadline must not expire"), () => now.get())
    try run(deadline, now)
    finally deadline.close()
  }
  private def expired(deadline: MessageDeadline): Unit =
    intercept[TimeoutException](deadline.complete()).getMessage shouldBe "WebSocket message assembly expired"

  "A WebSocket message deadline" should "close permanently when completion precedes start" in withDeadline {
    (deadline, _) =>
      expired(deadline)
      deadline.start()
      expired(deadline)
  }

  it should "keep the first start's absolute boundary when start is repeated" in withDeadline { (deadline, now) =>
    deadline.start()
    now.set(duration.toNanos / 2)
    deadline.start()
    now.set(duration.toNanos)
    expired(deadline)
    now.set(0L)
    deadline.start()
    expired(deadline)
  }

  it should "allow a fresh interval after a timely completion and close on a duplicate completion" in withDeadline {
    (deadline, now) =>
      deadline.start()
      now.set(duration.toNanos - 1)
      deadline.complete()
      now.set(duration.toNanos * 2)
      deadline.start()
      now.set(duration.toNanos * 3 - 1)
      deadline.complete()
      expired(deadline)
      deadline.start()
      expired(deadline)
  }

  it should "use elapsed monotonic time across signed wrap" in withDeadline { (deadline, now) =>
    val start = Long.MaxValue - duration.toNanos / 2
    now.set(start)
    deadline.start()
    now.set(start + duration.toNanos - 1)
    deadline.complete()
    deadline.start()
    now.set(now.get() + duration.toNanos)
    expired(deadline)
  }

  it should "make close idempotent before start, while armed and after completion without consulting the clock" in {
    for (phase <- 0 to 2) {
      var reads    = 0
      val deadline = new MessageDeadline(duration, () => fail("Closed deadline expired"), () => { reads += 1; 0L })
      if (phase > 0) deadline.start()
      if (phase > 1) deadline.complete()
      val before = reads
      deadline.close(); deadline.close(); deadline.start()
      expired(deadline)
      reads shouldBe before
    }
  }
}
