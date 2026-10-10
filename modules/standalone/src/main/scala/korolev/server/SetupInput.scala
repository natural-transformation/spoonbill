package spoonbill.server

import scala.collection.mutable
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*
import scala.util.control.NonFatal

/** Application input that can be released without closing the transport.
  *
  * Reads wait only during setup. After [[SetupInput.couple]], pulls go straight
  * to `upstream`. Duplex registers [[SetupInput.onInputEnd]] for cancellation;
  * the adapter observes transport termination outside the ordinary pull path.
  * [[SetupInput.releaseApplication]] completes pending reads and ignores later
  * ones.
  */
private[server] final class SetupInput[F[_]: Effect, A](upstream: Stream[F, A]) {
  private val lock = new Object
  // 0 setup, 1 coupled, 2 released. Volatile so the coupled path does not lock.
  @volatile private var phase = 0
  private var onEnded: () => F[Unit] = () => upstream.cancel()
  private val ended = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val waiters = mutable.ListBuffer.empty[Either[Throwable, Option[A]] => Unit]

  val stream: Stream[F, A] = new Stream[F, A] {
    def pull(): F[Option[A]] =
      if (phase != 1) pullDuringSetup()
      else upstream.pull()

    def cancel(): F[Unit] = Effect[F].delayAsync {
      val (pending, wasCoupled) = lock.synchronized {
        val coupled = phase == 1
        phase = 2
        val pending = waiters.toList
        waiters.clear()
        (pending, coupled)
      }
      pending.foreach(_(Right(None)))
      if (wasCoupled) endOnce() else Effect[F].unit
    }
  }

  /** Run once when coupled input is canceled.
    * Replaces the default upstream cancel. The callback owns transport closure.
    */
  def onInputEnd(callback: () => F[Unit]): Unit = {
    onEnded = callback
  }

  /** Attach waiting and future reads to the transport.
    * Returns true when input was already released; the transport is then closed
    * so a duplex result cannot continue on a canceled application input.
    */
  def couple(): F[Boolean] = Effect[F].delayAsync {
    val (pending, released) = lock.synchronized {
      phase match {
        case 0 =>
          val pending = waiters.toList
          waiters.clear()
          phase = 1
          (pending, false)
        case 1 => (Nil, false)
        case _ => (Nil, true)
      }
    }
    pending.foreach { callback =>
      upstream.pull().runAsync(callback)
    }
    if (released) upstream.cancel().as(true) else Effect[F].pure(false)
  }

  def releaseApplication(): F[Unit] = stream.cancel()

  def closeTransport(): F[Unit] = upstream.cancel()

  /** Pull and drop transport input until it ends, fails, or `isTerminal`.
    * One chunk is in flight; payloads are not accumulated. `onTerminated` runs
    * once so the caller can abort output that is still pending. A WebSocket
    * close frame is terminal: the peer may keep TCP open while waiting for the
    * server's close.
    */
  def drain(isTerminal: A => Boolean, onTerminated: () => F[Unit]): F[Unit] = {
    def loop(): F[Unit] =
      upstream.pull().flatMap {
        case None                                        => onTerminated()
        case Some(value) if isTerminal(value)            => onTerminated()
        case Some(_)                                     => loop()
      }.recoverF { case NonFatal(_) => onTerminated() }
    loop()
  }

  private def endOnce(): F[Unit] = Effect[F].delayAsync {
    if (ended.compareAndSet(false, true)) onEnded() else Effect[F].unit
  }

  private def pullDuringSetup(): F[Option[A]] = Effect[F].promise { callback =>
    val direct = lock.synchronized {
      phase match {
        case 1 => true
        case 2 =>
          callback(Right(None))
          false
        case _ =>
          waiters += callback
          false
      }
    }
    if (direct) {
      upstream.pull().runAsync(callback)
    }
  }
}
