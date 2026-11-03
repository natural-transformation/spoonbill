package spoonbill.auth

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.Effect

/**
 * Bounded ownership across provider settlement and local publication. Write
 * turns, which are the ordinary, cleanup and close turns, run one at a time.
 * Read turns run concurrently with each other and never with a write turn, at
 * most `readCapacity` at once and at most one per key. Turns are granted first
 * come, first served: a waiting write turn blocks every later read turn, so
 * writers cannot starve, and a read turn whose key is running stays in place
 * without holding a running slot while later read turns pass it. Only
 * turn-grant callbacks are queued, bounded by `capacity`; a full queue or a
 * requested close rejects ordinary and read turns. Each operation runs in its
 * original caller effect, preserving native fiber-local state and runtime
 * context. Cancellation cannot abandon an admitted operation or its cleanup.
 * A turn requested inside another turn waits for it forever: reentry is not
 * detected.
 */
private[auth] final class BrowserCoordinatorGate[F[_]](capacity: Int, readCapacity: Int = 1)(using F: Effect[F]) {
  require(capacity > 0 && readCapacity > 0)
  import BrowserCoordinatorGate.Stats
  private enum Admission {
    case Ordinary, Cleanup, Close
    case Read(key: Any)
  }
  /** A queued turn-grant callback. A read turn remembers whether its key made it wait. */
  private enum Entry {
    case Writer(callback: Effect.Promise[Boolean], since: Long)
    case Reader(callback: Effect.Promise[Boolean], key: Any, waited: Boolean)
  }
  /** What a running turn holds, released when it finishes. */
  private enum Turn {
    case Write
    case Read(key: Any)
  }
  private val lock            = new Object
  private var pending         = Vector.empty[Entry]
  private var writing         = false
  private var reads           = 0
  private var active          = Set.empty[Any]
  private var closing         = false
  private var closeResult     = Option.empty[Either[Throwable, Unit]]
  private var closeWaiters    = List.empty[Effect.Promise[Boolean]]
  private var bindingWaits    = 0L
  private var rejections      = 0L
  private var writerWaitNanos = 0L

  private def reject: Throwable = new IllegalStateException("Browser coordinator is closed or at capacity")

  /** True once close was requested, even before its turn runs. */
  def isClosing: Boolean = lock.synchronized(closing)

  /** A snapshot of the counters, for sizing the read cap and diagnosing overload. */
  def stats: Stats = lock.synchronized {
    Stats(
      runningReads = reads,
      queuedReads = pending.count(_.isInstanceOf[Entry.Reader]),
      queuedWrites = pending.count(_.isInstanceOf[Entry.Writer]),
      bindingWaits = bindingWaits,
      rejections = rejections,
      writerWaitNanos = writerWaitNanos
    )
  }
  private def signal(callback: Effect.Promise[Boolean], result: Either[Throwable, Boolean]): Unit =
    ExecutionContext.parasitic.execute(() => callback(result))

  /** true owns a turn; false joins an already settled global close. */
  private def acquire(kind: Admission): F[Boolean] = F.promise { callback =>
    val rejectable = kind match {
      case Admission.Ordinary | Admission.Read(_) => true
      case _                                      => false
    }
    val (outcome, grants): (Option[Either[Throwable, Boolean]], List[Effect.Promise[Boolean]]) = lock.synchronized {
      if (rejectable && (closing || pending.size >= capacity)) {
        rejections += 1
        (Some(Left(reject)), Nil)
      } else if (!rejectable && closing) {
        closeResult match {
          case Some(result) => (Some(result.map(_ => false)), Nil)
          case None         => closeWaiters = callback :: closeWaiters; (None, Nil)
        }
      } else {
        if (kind == Admission.Close) closing = true
        pending = pending :+ (kind match {
          case Admission.Read(key) => Entry.Reader(callback, key, waited = false)
          case _                   => Entry.Writer(callback, System.nanoTime())
        })
        (None, dispatch())
      }
    }
    outcome.foreach(signal(callback, _))
    grants.foreach(signal(_, Right(true)))
  }

  /**
   * Grant turns from the front of the queue, under the lock. Read turns are
   * granted until the cap or the first write turn; one whose key is running
   * stays in place and holds no slot. A write turn is granted only when it is
   * first and nothing runs. Returns the callbacks to signal outside the lock.
   */
  private def dispatch(): List[Effect.Promise[Boolean]] = {
    val grants   = List.newBuilder[Effect.Promise[Boolean]]
    var blocked  = Vector.empty[Entry]
    var index    = 0
    var scanning = true
    while (scanning && index < pending.size) {
      pending(index) match {
        case Entry.Writer(callback, since) =>
          if (!writing && reads == 0 && blocked.isEmpty) {
            writing = true
            writerWaitNanos += System.nanoTime() - since
            grants += callback
            index += 1
          }
          scanning = false
        case Entry.Reader(callback, key, waited) =>
          if (writing || reads >= readCapacity) scanning = false
          else if (active(key)) {
            blocked = blocked :+ Entry.Reader(callback, key, waited = true)
            index += 1
          } else {
            reads += 1
            active += key
            if (waited) bindingWaits += 1
            grants += callback
            index += 1
          }
      }
    }
    pending = blocked ++ pending.drop(index)
    grants.result()
  }

  private def finished(turn: Turn, closeOwner: Boolean, result: Either[Throwable, Unit]): Unit = {
    val (waiters, grants) = lock.synchronized {
      val waiters = if (closeOwner) {
        closeResult = Some(result)
        val all = closeWaiters
        closeWaiters = Nil
        all
      } else Nil
      turn match {
        case Turn.Write     => writing = false
        case Turn.Read(key) => reads -= 1; active -= key
      }
      waiters -> dispatch()
    }
    waiters.foreach(callback =>
      try signal(callback, result.map(_ => false))
      catch { case NonFatal(_) => () }
    )
    grants.foreach(signal(_, Right(true)))
  }

  private def execute[A](operation: => F[A], turn: Turn, closeOwner: Boolean): F[A] = {
    val attempted = F.recover(F.map(F.delayAsync(operation))(value => Right(value): Either[Throwable, A])) {
      case error => Left(error)
    }
    F.flatMap(attempted) { result =>
      F.flatMap(F.delay(finished(turn, closeOwner, result.map(_ => ())))) { _ =>
        result.fold(F.fail, F.pure)
      }
    }
  }

  def apply[A](operation: => F[A]): F[A] = F.uncancelable {
    F.flatMap(acquire(Admission.Ordinary))(_ => execute(operation, Turn.Write, closeOwner = false))
  }

  /**
   * A read turn: concurrent with other read turns, exclusive with write turns,
   * and one at a time per key. Over the cap it waits in the queue; only the
   * queue bound or a requested close rejects it.
   */
  def read[A](key: Any)(operation: => F[A]): F[A] = F.uncancelable {
    F.flatMap(acquire(Admission.Read(key)))(_ => execute(operation, Turn.Read(key), closeOwner = false))
  }

  /**
   * One reserved release per capacity-bounded live owner. During shutdown the
   * final close owns releases, so cleanup joins that operation's actual result.
   */
  def cleanup(operation: => F[Unit]): F[Unit] = F.uncancelable {
    F.flatMap(acquire(Admission.Cleanup)) {
      case true  => execute(operation, Turn.Write, closeOwner = false)
      case false => F.unit
    }
  }
  def close(operation: => F[Unit]): F[Unit] = F.uncancelable {
    F.flatMap(acquire(Admission.Close)) {
      case true  => execute(operation, Turn.Write, closeOwner = true)
      case false => F.unit
    }
  }
}

private[auth] object BrowserCoordinatorGate {

  /**
   * Counters for sizing the read cap and diagnosing overload. The running and
   * queued counts are current; the rest accumulate since construction.
   */
  final case class Stats(
    runningReads: Int,
    queuedReads: Int,
    queuedWrites: Int,
    bindingWaits: Long,
    rejections: Long,
    writerWaitNanos: Long
  )
}
