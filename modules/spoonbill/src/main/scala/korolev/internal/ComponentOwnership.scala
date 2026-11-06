package spoonbill.internal

import avocet.Id
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.{Effect, Reporter}
import spoonbill.effect.syntax.*
import spoonbill.state.{StateDeserializer, StateManager}

/** Local address handoff. Distributed authorization remains in StateManager. */
private[spoonbill] final class ComponentOwnership[F[_]: Effect](
  storage: StateManager[F],
  reporter: Reporter,
  executionContext: ExecutionContext
) {
  private val lock            = new Object
  private val tails           = mutable.Map.empty[Id, Owner]
  private val live            = mutable.Set.empty[Owner]
  private var closed          = false
  private var nextIncarnation = 0L
  private var baselineVersion = new Object

  private case class Baseline(incarnation: Long, revision: Long)
  private final class Snapshot(
    val version: AnyRef,
    val baselines: Map[Id, Baseline],
    underlying: StateManager.Snapshot
  ) extends StateManager.Snapshot {
    val source                                              = ComponentOwnership.this
    def apply[T: StateDeserializer](address: Id): Option[T] = underlying[T](address)
  }

  /**
   * Capture owner baselines before the storage read. A handoff or loader
   * completion invalidates affected captures without rejecting other owners.
   */
  def snapshot: F[StateManager.Snapshot] = Effect[F]
    .delay(lock.synchronized {
      (baselineVersion, tails.iterator.map { case (address, owner) => address -> owner.baseline }.toMap)
    })
    .flatMap { case (version, baselines) =>
      storage.snapshot.map(value => new Snapshot(version, baselines, value))
    }

  def closeAdmission(): Unit = lock.synchronized { closed = true }

  private final class Signal[A] {
    private var result  = Option.empty[Either[Throwable, A]]
    private var waiters = List.empty[Effect.Promise[A]]
    def await: F[A] = Effect[F].promise { cb =>
      val ready = synchronized {
        result match {
          case None => waiters ::= cb; None
          case some => some
        }
      }
      ready.foreach(cb)
    }
    def complete(value: Either[Throwable, A]): Unit = {
      val callbacks = synchronized {
        if (result.nonEmpty) Nil
        else { result = Some(value); val xs = waiters; waiters = Nil; xs }
      }
      callbacks.reverse.foreach(_(value))
    }
  }

  def bind(address: Id, deleteOnRetire: Boolean): Owner = {
    val (owner, previous) = lock.synchronized {
      if (closed) throw new spoonbill.server.SessionAccessDenied
      nextIncarnation = Math.addExact(nextIncarnation, 1L)
      val previous = tails.get(address)
      val owner    = new Owner(address, nextIncarnation, previous.isDefined, deleteOnRetire)
      tails.put(address, owner)
      live += owner
      (owner, previous)
    }
    previous match {
      case None => owner.open(Right(()))
      case Some(old) =>
        old.retire()
        old.done.await.runAsync(owner.open)
    }
    owner
  }

  /**
   * Includes retired components no longer reachable through the rendered tree.
   */
  def closeAndDrain(): F[Unit] = Effect[F].delay {
    val owners = lock.synchronized { closed = true; live.toList }
    owners.foreach(_.retire())
    owners
  }.flatMap { owners =>
    owners
      .map(_.awaitRetired.map[Option[Throwable]](_ => None).recover { case error => Some(error) })
      .sequence
      .flatMap { errors =>
        errors.flatten.headOption.fold(Effect[F].unit)(Effect[F].fail[Unit])
      }
  }

  final class Owner private[ComponentOwnership] (
    val address: Id,
    val incarnation: Long,
    val reused: Boolean,
    deleteOnRetire: Boolean
  ) {
    private val ready                    = new Signal[Boolean]
    private[ComponentOwnership] val done = new Signal[Unit]
    private var active                   = true
    private var opened                   = false
    private var predecessorSettled       = false
    private var admitted                 = 0
    private var cleanupStarted           = false
    private var predecessorError         = Option.empty[Throwable]
    private var baselineRevision         = 0L

    private[ComponentOwnership] def baseline: Baseline = Baseline(incarnation, baselineRevision)

    /**
     * Validate every traversal, including snapshots suspended across handoff.
     */
    def snapshotIsCurrent(snapshot: StateManager.Snapshot): Boolean = lock.synchronized {
      snapshot match {
        case value: Snapshot if (value.source eq ComponentOwnership.this) && !closed && active && opened =>
          value.baselines.get(address) match {
            case Some(captured) => captured == baseline
            // An address not yet owned at capture can use its pre-rendered
            // state only if no intervening deletion made that baseline unknown.
            case None => !reused && baselineRevision == 0L && (value.version eq baselineVersion)
          }
        case _ => false
      }
    }

    /**
     * Mark a settled loader write or fresh baseline read before notification.
     */
    def establishBaseline(): Unit = lock.synchronized {
      try baselineRevision = Math.addExact(baselineRevision, 1L)
      catch {
        case error: ArithmeticException =>
          active = false
          opened = false
          throw error
      }
    }

    def isOpen: Boolean       = lock.synchronized(!closed && active && opened)
    def isActive: Boolean     = lock.synchronized(!closed && active)
    def awaitOpen: F[Boolean] = ready.await
    def awaitRetired: F[Unit] = done.await

    /**
     * A late read may finish, but cannot return a successor's state to this
     * owner.
     */
    def read[A](operation: => F[A]): F[Option[A]] = awaitOpen.flatMap {
      case true if isOpen => Effect[F].delayAsync(operation).map(value => Option.when(isOpen)(value))
      case _              => Effect[F].pure(None)
    }

    private[ComponentOwnership] def open(result: Either[Throwable, Unit]): Unit = {
      val available = lock.synchronized {
        predecessorSettled = true
        predecessorError = result.left.toOption
        if (result.isRight && reused) baselineRevision = 1L
        opened = result.isRight && active
        opened
      }
      ready.complete(result.map(_ => available))
      cleanIfDrained()
    }

    /**
     * Reserve through write AND notification; no application code under lock.
     */
    def admit(): Option[Lease] = lock.synchronized {
      if (closed || !active || !opened) None
      else { admitted += 1; Some(new Lease) }
    }

    final class Lease private[Owner] () {
      private var settled = false
      def release(): Unit = {
        lock.synchronized {
          if (!settled) { settled = true; admitted -= 1 }
        }
        cleanIfDrained()
      }
      def run[A](operation: => F[A]): F[A] = Effect[F].uncancelable {
        Effect[F]
          .delayAsync(operation)
          .flatMap { value =>
            Effect[F].delay(release()).as(value)
          }
          .recoverF { case error =>
            release()
            Effect[F].fail(error)
          }
      }
    }

    /** Immediate retirement; drain/deletion never awaits its own render. */
    def retire(): Unit = {
      lock.synchronized { active = false; opened = false }
      ready.complete(Right(false))
      cleanIfDrained()
    }

    private def cleanIfDrained(): Unit = {
      val start = lock.synchronized {
        if (!active && predecessorSettled && admitted == 0 && !cleanupStarted) {
          cleanupStarted = true
          true
        } else false
      }
      if (start) {
        def completed(result: Either[Throwable, Unit]): Unit = {
          lock.synchronized {
            if (result.isRight && deleteOnRetire) baselineVersion = new Object
            if (result.isRight && tails.get(address).contains(this)) tails.remove(address)
            if (result.isRight) live -= this
          }
          result.left.foreach(error => reporter.error("Component address cleanup failed", error))
          done.complete(result)
        }
        try
          Effect[F]
            .fork(Effect[F].delayAsync {
              predecessorError match {
                case Some(error) => Effect[F].fail[Unit](error)
                // Whole-view storage removal follows the drain. A closed
                // guarded manager rejects per-node deletes, and no successor
                // can bind here once application admission is closed.
                case None if deleteOnRetire && lock.synchronized(!closed) => storage.delete(address)
                case None                                                 => Effect[F].unit
              }
            })(executionContext)
            .runAsync(completed)
        catch { case NonFatal(error) => completed(Left(error)) }
      }
    }
  }
}
