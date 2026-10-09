package spoonbill.server

import java.util.concurrent.{ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*

/** Own an upgrade until its first output pull, including a late service result.
  * All transitions run inside F. Cleanup cannot recursively wait on itself.
  */
private[server] final class SetupDeadline[F[_]: Effect](
  timeout: FiniteDuration,
  releaseInput: () => F[Unit],
  shutdown: () => F[Unit]
)(implicit ec: ExecutionContext) {
  private type Release = () => F[Unit]
  private sealed trait State
  private case class Pending(release: Option[Release]) extends State
  private case class Attached(release: Release) extends State
  private case object Finished extends State
  private val state = new AtomicReference[State](Pending(None))
  @volatile private var detachObserver: () => Unit = () => ()
  private val scheduled = SetupDeadline.timer.schedule(new Runnable {
    def run(): Unit = ec.execute(new Runnable {
      def run(): Unit = expire().runAsync(_ => ())
    })
  }, timeout.toNanos, TimeUnit.NANOSECONDS)

  /** Registration can race with expiry/close; either side removes the observer. */
  def onFinished(detach: () => Unit): Unit = {
    detachObserver = detach
    if (state.get() == Finished) detach()
  }

  def acquire(release: () => F[Unit]): F[Boolean] = Effect[F].delayAsync {
    @tailrec def claim(): Boolean = state.get() match {
      case pending: Pending =>
        if (state.compareAndSet(pending, Pending(Some(release)))) true else claim()
      case _ => false
    }
    if (claim()) Effect[F].pure(true)
    else release().recover { case NonFatal(_) => () }.as(false)
  }

  def attach(): F[Boolean] = Effect[F].delay {
    @tailrec def claim(): Boolean = state.get() match {
      case pending @ Pending(Some(release)) =>
        if (state.compareAndSet(pending, Attached(release))) true else claim()
      case _: Attached => true
      case _ => false
    }
    val attached = claim()
    if (attached) cancelTimer()
    attached
  }

  /** Deadline affects setup only. Exposed internally for deterministic races. */
  def expire(): F[Unit] = terminate(setupOnly = true, closeTransport = true)

  def transportEnded(): F[Unit] = terminate(setupOnly = false, closeTransport = true)

  /** A failed service can still return an HTTP rejection before attachment. */
  def reject(): F[Unit] = terminate(setupOnly = true, closeTransport = false)

  private def terminate(setupOnly: Boolean, closeTransport: Boolean): F[Unit] = Effect[F].delayAsync {
    @tailrec def claim(): Option[Option[Release]] = state.get() match {
      case pending: Pending =>
        if (state.compareAndSet(pending, Finished)) Some(pending.release) else claim()
      case attached: Attached if !setupOnly =>
        if (state.compareAndSet(attached, Finished)) Some(Some(attached.release)) else claim()
      case _ => None
    }
    claim() match {
      case None => Effect[F].unit
      case Some(release) =>
        cancelTimer()
        detachObserver()
        (if (closeTransport) shutdown() else Effect[F].unit) *>
          release.fold(Effect[F].unit)(_.apply().recover { case NonFatal(_) => () }) *>
          releaseInput()
    }
  }

  private def cancelTimer(): Unit = {
    // A sub-millisecond deadline can run before schedule() returns its handle.
    val task = scheduled
    if (task != null) task.cancel(false)
  }
}

private[server] object SetupDeadline {
  // Shared, bounded daemon scheduler: cancellation removes the task immediately,
  // so successful upgrades retain neither response nor connection until expiry.
  private val timer = new ScheduledThreadPoolExecutor(1, new ThreadFactory {
    def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "spoonbill-websocket-setup")
      thread.setDaemon(true)
      thread
    }
  })
  timer.setRemoveOnCancelPolicy(true)
}
