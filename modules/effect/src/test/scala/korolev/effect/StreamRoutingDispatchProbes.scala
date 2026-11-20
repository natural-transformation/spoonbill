package spoonbill.effect

import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

/** The probes of `StreamRoutingDispatchSpec`. */
private[effect] object StreamRoutingDispatchProbes {

  // A reader fiber's commands call back, to settle a pull or to give the reader its turn: each call enters the probe.
  def probed(inside: Exclusion): Effect[Future] = new ReaderFiberFutureEffect {
    override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] =
      super.promise[A](callback => register(result => inside(callback(result))))
  }

  /**
   * Detects two threads inside dispatch. The callback pump's commands read
   * upstream and complete pulls. A reader fiber's commands only call back.
   * `used` tells whether anything entered the probe at all: one that nothing
   * enters checks nothing.
   */
  final class Exclusion {
    private val inside       = new AtomicInteger()
    @volatile var overlapped = false
    @volatile var used       = false
    def apply[B](body: => B): B = {
      if (!used) used = true
      if (inside.incrementAndGet() != 1) overlapped = true
      try body
      finally inside.decrementAndGet()
    }

    /**
     * Runs `body` as the pull's continuation. One that already runs while it is
     * attached is on the attaching thread, outside dispatch.
     */
    def notified[B, C](pull: Future[B])(body: Try[B] => C): Future[C] = {
      val attaching = new AtomicBoolean(true)
      val result = pull.transform { result =>
        Success(if (attaching.get()) body(result) else apply(body(result)))
      }(ExecutionContext.parasitic)
      attaching.set(false)
      result
    }
  }

  /**
   * Holds each read until it is published; counts the reads that entered.
   * `entering` runs inside each read, with the number of that read.
   */
  final class Gated(exclusion: Exclusion = new Exclusion, entering: Int => Unit = _ => ())(using Effect[Future])
      extends Stream[Future, Int] {
    private val pending = new AtomicReference[Promise[Option[Int]]]()
    val reads           = new Semaphore(0)
    val entered         = new AtomicInteger()
    val cancellations   = new AtomicInteger()
    val canceled        = Promise[Unit]()
    def pull(): Future[Option[Int]] = exclusion {
      val next = Promise[Option[Int]]()
      require(pending.compareAndSet(null, next), "Concurrent upstream reads")
      entering(entered.incrementAndGet())
      reads.release()
      next.future
    }
    def cancel(): Future[Unit] = { cancellations.incrementAndGet(); canceled.trySuccess(()); Future.unit }
    def publish(value: Option[Int]): Unit = {
      require(reads.tryAcquire(10, TimeUnit.SECONDS), "No upstream read to publish")
      pending.getAndSet(null).success(value)
    }
  }
}
