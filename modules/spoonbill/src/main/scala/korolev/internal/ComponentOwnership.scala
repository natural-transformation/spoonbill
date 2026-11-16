package spoonbill.internal

import avocet.Id
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import spoonbill.effect.{CompletionLatchMachine, Effect, Reporter}
import spoonbill.effect.syntax.*
import spoonbill.state.{StateDeserializer, StateManager}

/** Local address handoff. Distributed authorization remains in StateManager. */
private[spoonbill] final class ComponentOwnership[F[_]: Effect](
  storage: StateManager[F],
  reporter: Reporter,
  executionContext: ExecutionContext
) {
  private val Model = ComponentOwnershipMachine
  import ComponentOwnershipMachine.Baseline
  private val lock            = new Object
  private val tails           = mutable.Map.empty[Id, Owner]
  private val live            = mutable.Set.empty[Owner]
  private var registry = Model.Registry(new Model.Version)
  private def closed: Boolean = Model.closed(registry)
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
      (registry.version, tails.iterator.map { case (address, owner) => address -> owner.baseline }.toMap)
    })
    .flatMap { case (version, baselines) =>
      storage.snapshot.map(value => new Snapshot(version, baselines, value))
    }

  def closeAdmission(): Unit = lock.synchronized { registry = Model.close(registry) }

  private final class Signal[A] {
    private final case class Held(state: CompletionLatchMachine.State, result: Option[Either[Throwable, A]],
      waiters: List[Effect.Promise[A]])
    private var held = Held(CompletionLatchMachine.State.Pending, None, Nil)
    def await: F[A] = Effect[F].promise { cb =>
      val ready = synchronized {
        val (next, command) = CompletionLatchMachine.step(held.state, CompletionLatchMachine.Event.Observe)
        command match {
          case CompletionLatchMachine.Command.Register => held = held.copy(state = next, waiters = cb :: held.waiters); None
          case _ => held.result
        }
      }
      ready.foreach(cb)
    }
    def complete(value: Either[Throwable, A]): Unit = {
      val callbacks = synchronized {
        val (next, command) = CompletionLatchMachine.step(held.state, CompletionLatchMachine.Event.Complete)
        if (command != CompletionLatchMachine.Command.Publish) Nil
        else { val xs = held.waiters; held = Held(next, Some(value), Nil); xs }
      }
      callbacks.reverse.foreach(_(value))
    }
  }

  def bind(address: Id, deleteOnRetire: Boolean): Owner = {
    val (owner, previous) = lock.synchronized {
      val (next, binding) = Model.bind(registry, address)
      binding match {
        case Model.Binding.Closed => throw new spoonbill.server.SessionAccessDenied
        case Model.Binding.Exhausted => throw new ArithmeticException("long overflow")
        case Model.Binding.Assigned(incarnation, predecessor) =>
          val previous = tails.get(address)
          val owner = new Owner(address, incarnation, predecessor.isDefined, deleteOnRetire)
          registry = next
          tails.put(address, owner)
          live += owner
          (owner, previous)
      }
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
    val owners = lock.synchronized { registry = Model.close(registry); live.toList }
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
    private var lifecycle                = Model.OwnerState()
    private var predecessorError         = Option.empty[Throwable]
    private[ComponentOwnership] def baseline: Baseline = Baseline(incarnation, lifecycle.baseline.revision)

    /**
     * Validate every traversal, including snapshots suspended across handoff.
     */
    def snapshotIsCurrent(snapshot: StateManager.Snapshot): Boolean = lock.synchronized {
      snapshot match {
        case value: Snapshot if value.source eq ComponentOwnership.this =>
          Model.acceptsSnapshot(lifecycle, incarnation, closed, reused, value.baselines.get(address),
            value.version eq registry.version)
        case _ => false
      }
    }

    /**
     * Mark the first stored baseline before notification. Reloads and repeated
     * refreshes of this same owner do not invalidate its established snapshots.
     */
    def establishBaseline(): Unit = lock.synchronized {
      val (next, overflow) = Model.establishBaseline(lifecycle)
      lifecycle = next
      if (overflow) throw new ArithmeticException("long overflow")
    }

    def isOpen: Boolean       = lock.synchronized(Model.canAdmit(lifecycle, closed))
    def isActive: Boolean     = lock.synchronized(!closed && Model.active(lifecycle))
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
        predecessorError = result.left.toOption
        lifecycle = Model.predecessorCompleted(lifecycle, result.isRight, reused)
        Model.open(lifecycle)
      }
      ready.complete(result.map(_ => available))
      cleanIfDrained()
    }

    /**
     * Reserve through write AND notification; no application code under lock.
     */
    def admit(): Option[Lease] = lock.synchronized {
      if (!Model.canAdmit(lifecycle, closed)) None
      else { lifecycle = Model.admitted(lifecycle); Some(new Lease) }
    }

    final class Lease private[Owner] () {
      private var settled = false
      def release(): Unit = {
        lock.synchronized {
          if (!settled) { settled = true; lifecycle = Model.released(lifecycle) }
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
      lock.synchronized { lifecycle = Model.retire(lifecycle) }
      ready.complete(Right(false))
      cleanIfDrained()
    }

    private def cleanIfDrained(): Unit = {
      val start = lock.synchronized {
        val (next, start) = Model.beginCleanup(lifecycle)
        lifecycle = next
        start
      }
      if (start) {
        def completed(result: Either[Throwable, Unit]): Unit = {
          lock.synchronized {
            lifecycle = Model.completed(lifecycle, result.isRight)
            val version = if (result.isRight && deleteOnRetire) new Model.Version else registry.version
            registry = Model.retired(registry, address, incarnation, result.isRight, deleteOnRetire, version)
            if (result.isRight && tails.get(address).contains(this)) tails.remove(address)
            if (result.isRight) live -= this
          }
          result.left.foreach(error => reporter.error("Component address cleanup failed", error))
          done.complete(result)
        }
        try
          Effect[F]
            .fork(Effect[F].delayAsync {
              lock.synchronized(Model.cleanup(lifecycle, closed, deleteOnRetire)) match {
                case Model.Cleanup.PropagateFailure => Effect[F].fail[Unit](predecessorError.get)
                // Whole-view storage removal follows the drain. A closed
                // guarded manager rejects per-node deletes, and no successor
                // can bind here once application admission is closed.
                case Model.Cleanup.DeleteNode => storage.delete(address)
                case Model.Cleanup.SkipDelete => Effect[F].unit
              }
            })(executionContext)
            .runAsync(completed)
        catch { case NonFatal(error) => completed(Left(error)) }
      }
    }
  }
}
