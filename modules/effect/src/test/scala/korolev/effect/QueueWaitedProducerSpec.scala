package spoonbill.effect

import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/**
 * A producer that had to wait runs again some time after its wake-up. These
 * tests hold it between the two, change the queue, and then let it go.
 */
class QueueWaitedProducerSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  /** Holds one capacity waiter between its wake-up and its next attempt. */
  private final class HeldWake extends ReaderFiberFutureEffect {
    private val armed = new AtomicReference(Option.empty[Promise[Unit]])

    /** Holds the next `promise` after its completion. Returns the release. */
    def holdNext(): () => Unit = {
      val hold = Promise[Unit]()
      armed.set(Some(hold))
      () => hold.success(())
    }

    override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] = {
      given ExecutionContext = ExecutionContext.parasitic
      val woken              = super.promise(register)
      armed.getAndSet(None).fold(woken)(hold => woken.flatMap(value => hold.future.map(_ => value)))
    }
  }

  private val failure = new IllegalStateException("pull failure")

  "A producer that had to wait" should "take nothing when the queue fails, stops or closes before its next attempt" in {
    val endings = Vector[(String, Queue[Future, Int] => Future[Unit])](
      "fail"  -> (_.fail(failure)),
      "stop"  -> (_.stop()),
      "close" -> (_.close())
    )
    endings.foreach { case (name, end) =>
      withClue(s"$name: ") {
        val effect = new HeldWake
        val queue  = new Queue[Future, Int](1)(using effect)
        await(queue.enqueue(1))
        val release = effect.holdNext()
        val waiting = queue.enqueue(2)
        // The pull makes room and wakes the producer. The producer is held there.
        await(queue.stream.pull()) shouldBe Some(1)
        await(end(queue))
        waiting.isCompleted shouldBe false
        release()
        waiting.isCompleted shouldBe true
        await(waiting)
        // There was room, but the queue had ended before the producer ran again.
        await(queue.dequeueAll()).toVector shouldBe Vector.empty
        await(queue.close())
      }
    }
  }

  it should "still be offered room by a failed queue when it starts anew" in {
    val effect = new HeldWake
    val queue  = new Queue[Future, Int](1)(using effect)
    await(queue.enqueue(1))
    val release = effect.holdNext()
    val waiting = queue.enqueue(2)
    await(queue.stream.pull()) shouldBe Some(1)
    await(queue.fail(failure))
    release()
    await(waiting)
    // The same value in a new enqueue: its first attempt is an offer.
    await(queue.enqueue(2))
    await(queue.dequeueAll()).toVector shouldBe Vector(2)
    await(queue.close())
  }

  it should "hand its value to the waiting pulls and pass the capacity hint on, as an offer does" in {
    val effect = new HeldWake
    val queue  = new Queue[Future, Int](1)(using effect)
    await(queue.enqueue(1))
    // The spare waiter registers first, so the pull wakes the producer.
    val spare   = queue.canOffer
    val release = effect.holdNext()
    val waiting = queue.enqueue(2)
    await(queue.stream.pull()) shouldBe Some(1)
    spare.isCompleted shouldBe false
    val first  = queue.stream.pull()
    val second = queue.stream.pull()
    first.isCompleted shouldBe false
    second.isCompleted shouldBe false

    release()
    waiting.isCompleted shouldBe true
    await(first) shouldBe Some(2)
    await(second) shouldBe Some(2)
    // Direct handoff left the buffer free. The hint reaches one more waiter.
    spare.isCompleted shouldBe true
    await(queue.size()) shouldBe 0
    await(queue.close())
  }
}
