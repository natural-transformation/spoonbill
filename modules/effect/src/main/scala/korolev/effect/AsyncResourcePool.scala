package spoonbill.effect

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import spoonbill.effect.syntax._

/**
 * Asynchronous non-blocking resource pool with lifetime control.
 */
class AsyncResourcePool[F[_]: Effect, T: Close[F, *]](
  name: String,
  factory: => F[T],
  currentNanos: () => F[Long],
  maxCount: Int,
  maxIdleTime: FiniteDuration
)(implicit reporter: Reporter) {

  import AsyncResourcePool.Borrow

  private class BorrowImpl(val value: T, id: BigInt, retire: () => F[Unit]) extends Borrow[F, T] {
    private val returned = new AtomicBoolean(false)
    def give(): F[Unit] = Effect[F].delayAsync {
      if (returned.compareAndSet(false, true)) returnToPool() else Effect[F].unit
    }
    def discard(): F[Unit] = Effect[F].uncancelable {
      Effect[F].delayAsync {
        if (returned.compareAndSet(false, true)) retire().flatMap(_ => returnToPool()) else Effect[F].unit
      }
    }
    private def returnToPool(): F[Unit] = {
      @tailrec def loop(nanos: Long): Unit = {
        val ref = pool.get()
        if (ref.itemIsClosed(id)) {
          // Item is closed.
          // Remove it from closed list and forget.
          val newState = ref.copy(closedItems = ref.closedItems - id)
          if (pool.compareAndSet(ref, newState)) {
            //reporter.debug("%s - Closed item finally removed from pool", name)
          } else {
            loop(nanos)
          }
        } else {
          if (ref.cbs.isEmpty) {
            // There is no callbacks. Add item back to pool
            val item     = PoolEntry(id, nanos, value, retire)
            val newState = ref.copy(items = item :: ref.items)
            if (pool.compareAndSet(ref, newState)) {
              //reporter.debug("%s - Item has gave back to pool", name)
            } else {
              loop(nanos)
            }
          } else {
            // Callback found. Transfer item to next taker.
            ref.cbs match {
              case cb :: restOfCbs =>
                val newState = ref.copy(cbs = restOfCbs)
                if (pool.compareAndSet(ref, newState)) {
                  //reporter.debug("%s - Returned item transferred to next taker", name)
                  cb(Right(new BorrowImpl(value, id, retire)))
                } else {
                  loop(nanos)
                }
              case Nil =>
                loop(nanos)
            }
          }
        }
      }
      currentNanos().map(loop)
    }
  }

  private enum UseState {
    case Waiting(callback: Option[Promise])
    case Acquired(value: Borrow[F, T])
    case Transferred, Released
  }

  /** Own the loan before publishing it to the possibly canceled caller. */
  private final class UseRequest(cleanup: Borrow[F, T] => F[Unit]) {
    private val ownership = new AtomicReference[UseState](UseState.Waiting(None))
    val deliver: Promise  = outcome => complete(outcome)

    @tailrec def register(callback: Promise): Boolean = {
      val before = ownership.get()
      before match {
        case UseState.Waiting(None) =>
          if (ownership.compareAndSet(before, UseState.Waiting(Some(callback)))) true else register(callback)
        case _ => false
      }
    }

    def released: Boolean = ownership.get() == UseState.Released

    @tailrec def transfer(): Unit = {
      val before = ownership.get()
      before match {
        case UseState.Acquired(_) =>
          if (!ownership.compareAndSet(before, UseState.Transferred)) transfer()
        case _ => ()
      }
    }

    @tailrec private def relinquish(): UseState = {
      val before = ownership.get()
      before match {
        case UseState.Transferred | UseState.Released => before
        case _                                        => if (ownership.compareAndSet(before, UseState.Released)) before else relinquish()
      }
    }

    @tailrec private def complete(outcome: Either[Throwable, Borrow[F, T]]): Unit = {
      val before = ownership.get()
      before match {
        case UseState.Waiting(Some(callback)) =>
          val next = outcome.fold(_ => UseState.Released, UseState.Acquired.apply)
          if (ownership.compareAndSet(before, next)) callback(outcome) else complete(outcome)
        case UseState.Released =>
          // Factory completion can arrive after cancellation already returned.
          outcome.foreach(value => Effect[F].uncancelable(cleanup(value)).runAsyncForget)
        case _ => ()
      }
    }

    def release(): F[Unit] = Effect[F].delayAsync {
      relinquish() match {
        case UseState.Acquired(value)                 => cleanup(value)
        case UseState.Waiting(_)                      => forgetWaiter(deliver); Effect[F].unit
        case UseState.Transferred | UseState.Released => Effect[F].unit
      }
    }
  }

  @tailrec private def forgetWaiter(callback: Promise): Unit = {
    val before = pool.get()
    if (before.cbs.exists(_ eq callback)) {
      val after = before.copy(cbs = before.cbs.filterNot(_ eq callback))
      if (!pool.compareAndSet(before, after)) forgetWaiter(callback)
    }
  }

  def use[R](f: T => F[R]): F[R] = scopedBorrow(value => f(value.value), _.give(), transferOnSuccess = false)

  /**
   * Internal acquisition ownership until the supplied task succeeds. On success
   * its returned resource owns the loan; callers must bracket/adopt that
   * result. This is a task-success boundary, not irrevocable delivery to an
   * outer caller: some runtimes can honor late cancellation after a success
   * finalizer. Abort must respect the loan's ownership claim, e.g. discard
   * rather than an unconditional mutation of a possibly returned/reused
   * resource.
   */
  private[spoonbill] def borrowAndTransfer[R](f: Borrow[F, T] => F[R])(abort: Borrow[F, T] => F[Unit]): F[R] =
    scopedBorrow(f, abort, transferOnSuccess = true)

  private def scopedBorrow[R](
    f: Borrow[F, T] => F[R],
    cleanup: Borrow[F, T] => F[Unit],
    transferOnSuccess: Boolean
  ): F[R] = Effect[F].delayAsync {
    val request = new UseRequest(cleanup)
    val acquire = Effect[F].promise[Borrow[F, T]] { callback =>
      if (request.register(callback)) {
        borrowUnsafe(request.deliver)
        // Cancellation can race the registration's admission CAS.
        if (request.released) forgetWaiter(request.deliver)
      }
    }
    // User work stays in the caller's effect, including its fiber-local context.
    val task = acquire.flatMap(value => Effect[F].delayAsync(f(value)))
    val result = if (transferOnSuccess) task.map { value =>
      request.transfer(); value
    }
    else task
    Effect[F].guarantee(result)(request.release())
  }

  /**
   * Borrow item from the pool.
   *   1. Borrow one of idle items if it exists 2. Otherwise try to creates one
   *      using [[factory]] 3. If [[maxCount]] is reached, waits until one of
   *      borrowed items would given back
   * @return
   */
  def borrow(): F[Borrow[F, T]] = Effect[F].promise(borrowUnsafe)

  private def borrowUnsafe(cb: Promise): Unit = {
    @tailrec def onCloseLoop(value: T, id: BigInt): Unit = {
      val ref = pool.get()
      if (ref.retirements.contains(id)) {
        // Cleanup already released this allocation's capacity. Closure, not
        // cancellation of a waiter, releases its retry handle and tombstone.
        val next = ref.copy(retirements = ref.retirements - id, closedItems = ref.closedItems - id)
        if (!pool.compareAndSet(ref, next)) onCloseLoop(value, id)
      } else if (!ref.itemIsClosed(id)) {
        // An idle entry has no outstanding loan that can return later.
        val wasIdle     = ref.items.exists(_.id == id)
        val closedItems = if (wasIdle) ref.closedItems else ref.closedItems.updated(id, System.identityHashCode(value))
        val items       = ref.items.filter(_.id != id)
        val (total, cbs) =
          if (ref.cbs.nonEmpty) {
            // Keep allocated resource count same
            // because new resource will be allocated
            // and given away with callback
            (ref.total, ref.cbs.tail)
          } else {
            (ref.total - 1, Nil)
          }
        val newState = ref.copy(
          closedItems = closedItems,
          items = items,
          total = total,
          cbs = cbs
        )
        if (pool.compareAndSet(ref, newState)) {
          ref.cbs.headOption.foreach { cb =>
            startCreation(cb)
          }
        } else {
          onCloseLoop(value, id)
        }
      }
    }

    @tailrec def creationFailed(): Unit = {
      val ref     = pool.get()
      val waiting = ref.cbs.headOption
      val next    = ref.copy(total = if (waiting.isDefined) ref.total else ref.total - 1, cbs = ref.cbs.drop(1))
      if (pool.compareAndSet(ref, next)) waiting.foreach(startCreation)
      else creationFailed()
    }

    def createNew(): F[Borrow[F, T]] =
      Effect[F]
        .delayAsync(factory)
        .recoverF { case scala.util.control.NonFatal(error) =>
          Effect[F].delay(creationFailed()).flatMap(_ => Effect[F].fail(error))
        }
        .map { value =>
          val id       = allocateId()
          val observed = new AtomicBoolean(false)
          val settled  = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
          def recordClose(): Unit =
            if (observed.compareAndSet(false, true)) {
              onCloseLoop(value, id)
              settled.put((), ()).runAsyncForget
            }
          def retire(): F[Unit] =
            Close[F, T].close(value).flatMap { _ =>
              Effect[F].delay(recordClose()).flatMap(_ => settled.get(())).unit
            }
          //reporter.debug("%s - Resource created", name)
          Close[F, T].onClose(value).runAsync(_ => recordClose())
          new BorrowImpl(value, id, () => retire())
        }

    // Future's Effect can complete inline. Trampoline all launches so a failed
    // factory cannot recursively walk the waiter backlog on the current stack.
    // Every queued launch already owns one of maxCount creation reservations;
    // waiters remain in cbs, with no additional pool-owned work queue or executor.
    def startCreation(callback: Promise): Unit =
      ExecutionContext.parasitic.execute(() => createNew().runAsync(callback))

    @tailrec def loop(): Unit = pool.get() match {
      case ref @ PoolData(_, x :: xs, _, _, _, _, _, _) =>
        // Return item from pool
        if (pool.compareAndSet(ref, ref.copy(items = xs))) {
          //reporter.debug("%s - Return item from pool", name)
          cb(Right(new BorrowImpl(x.value, x.id, x.retire)))
        } else {
          loop()
        }
      case ref if ref.total >= maxCount =>
        // Save callback
        val newState = ref.copy(cbs = cb :: ref.cbs)
        if (pool.compareAndSet(ref, newState)) {
          //reporter.debug("%s - maxCount excited. save callback", name)
        } else {
          loop()
        }
      case ref if ref.items.isEmpty =>
        val newState = ref.copy(total = ref.total + 1)
        if (pool.compareAndSet(ref, newState)) {
          //reporter.debug("%s - Pool is empty. Try to create new item, increment total", name)
          startCreation(cb)
        } else {
          loop()
        }
      case _ =>
        loop()
    }
    loop()
  }

  def onDispose(): F[Unit] = Effect[F].promise[Unit] { cb =>
    @tailrec def loop(): Unit = {
      val ref = pool.get
      if (!ref.disposed) {
        if (!pool.compareAndSet(ref, ref.copy(disposeCbs = cb :: ref.disposeCbs))) {
          loop()
        }
      } else {
        cb(Right(()))
      }
    }

    loop()
  }

  def dispose(): F[Unit] = {
    @tailrec def loop(): F[Unit] = {
      val ref = pool.get
      if (!ref.disposed) {
        if (pool.compareAndSet(ref, ref.copy(disposal = Disposal.Claimed))) {
          val token   = Right(())
          val effects = ref.items.map(item => Close[F, T].close(item.value))
          Effect[F].sequence(effects).flatMap { _ =>
            Effect[F].delay(ref.disposeCbs.foreach(_(token)))
          }
        } else {
          loop()
        }
      } else {
        Effect[F].unit
      }
    }

    Effect[F].delayAsync(loop())
  }

  /**
   * Expire idle resources and retry previously failed retirements. Returns the
   * number of retirement claims successfully settled for this call (including
   * independent closure after a claim). Entries owned by another cleanup or
   * already closed before claiming are skipped. Every selected entry is tried
   * once despite ordinary failures, then the first error is propagated.
   * Cancellation stops waiting, without canceling an entered close attempt;
   * not-yet-started entries remain available to a later cleanup. A close that
   * cancels itself is reported as a retryable CancellationException.
   */
  def cleanup(): F[Int] = {
    @tailrec def detach(nanos: Long): List[BigInt] = {
      val ref               = pool.get()
      val (obsolete, items) = ref.items.partition(item => nanos - item.idle >= maxIdleTimeNanos)
      val retry = ref.retirements.iterator.collect {
        case (id, retirement) if retirement.state == PoolRetirementMachine.State.Ready => id
      }.toList.sorted
      if (obsolete.isEmpty) retry
      else {
        val next = ref.copy(
          total = ref.total - obsolete.size,
          items = items,
          closedItems = ref.closedItems ++ obsolete.map(item => item.id -> System.identityHashCode(item.value)),
          retirements = ref.retirements ++ obsolete.map(item => item.id -> Retirement(item))
        )
        if (pool.compareAndSet(ref, next)) obsolete.map(_.id) ++ retry else detach(nanos)
      }
    }
    currentNanos().flatMap(nanos => Effect[F].delay(detach(nanos))).flatMap { ids =>
      val attempts =
        ids.map(id => retireIdle(id).map[Either[Throwable, Int]](Right(_)).recover { case error => Left(error) })
      Effect[F].sequence(attempts).flatMap { outcomes =>
        outcomes.collectFirst { case Left(error) => error } match {
          case Some(error) => Effect[F].fail(error)
          case None        => Effect[F].pure(outcomes.collect { case Right(count) => count }.sum)
        }
      }
    }
  }

  /**
   * Change only the matching attempt; a late startup/finalizer cannot take
   * ownership from a retry, or recreate an entry removed by onClose.
   */
  @tailrec private def moveRetirement(
    id: BigInt,
    transition: PoolRetirementMachine.State => PoolRetirementMachine.State
  ): Option[PoolEntry] = {
    val ref = pool.get()
    ref.retirements.get(id) match {
      case Some(retirement) =>
        val next = transition(retirement.state)
        if (next == retirement.state) None
        else if (
          pool.compareAndSet(ref, ref.copy(retirements = ref.retirements.updated(id, retirement.copy(state = next))))
        )
          Some(retirement.item)
        else moveRetirement(id, transition)
      case None => None
    }
  }

  private def retireIdle(id: BigInt): F[Int] = Effect[F].delayAsync {
    val owner = new PoolRetirementMachine.Attempt
    // The scope precedes the claim, including for a lazy/native effect. A
    // canceled or rejected startup may release Starting, never Running.
    Effect[F].guarantee(Effect[F].delayAsync {
      moveRetirement(id, PoolRetirementMachine.claim(_, owner)) match {
        case None => Effect[F].pure(0)
        case Some(item) =>
          val completion = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
          val outcome =
            new AtomicReference[Either[Throwable, Unit]](Left(new CancellationException("Pool retirement canceled")))
          def task: F[Unit] = Effect[F].guarantee(
            Effect[F].delayAsync {
              if (moveRetirement(id, PoolRetirementMachine.enter(_, owner)).isDefined) item.retire()
              else Effect[F].unit
            }.map[Either[Throwable, Unit]](Right(_))
              .recover { case error => Left(error) }
              .flatMap(result => Effect[F].delay(outcome.set(result)))
          )(
            Effect[F].delay {
              moveRetirement(id, PoolRetirementMachine.settled(_, owner))
              outcome.get()
            }.flatMap(result => completion.putEither((), result))
          )
          // Some native Fiber.join implementations never return on child
          // cancellation. The owned task's finalizer publishes that exit too.
          Effect[F].delayAsync(Effect[F].start(task)(ExecutionContext.global)).flatMap(_ => completion.get(())).as(1)
      }
    })(Effect[F].delay { moveRetirement(id, PoolRetirementMachine.abandon(_, owner)); () })
  }

  /**
   * For debug purposes only
   */
  def unsafeGetState: PoolState = {
    val current = pool.get()
    // Keep the public diagnostic shape; value hashes are no longer ownership keys.
    PoolState(
      current.total,
      current.items.map(item => PoolItem(item.idle, item.value)),
      current.cbs,
      TreeSet.empty[Int] ++ current.closedItems.values,
      current.disposeCbs,
      current.disposed
    )
  }

  type Promise = Effect.Promise[Borrow[F, T]]

  type DisposePromise = Effect.Promise[Unit]

  case class PoolItem(idle: Long = 0L, value: T)

  case class PoolState(
    total: Int = 0,
    items: List[PoolItem] = Nil,
    cbs: List[Promise] = Nil,
    closedItems: TreeSet[Int] = TreeSet.empty,
    disposeCbs: List[DisposePromise] = Nil,
    disposed: Boolean = false
  ) {

    def itemIsClosed(value: T): Boolean =
      closedItems.contains(System.identityHashCode(value))
  }

  private case class PoolEntry(id: BigInt, idle: Long, value: T, retire: () => F[Unit])
  private case class Retirement(item: PoolEntry, state: PoolRetirementMachine.State = PoolRetirementMachine.State.Ready)
  private enum Disposal { case Unclaimed, Claimed }
  private case class PoolData(
    total: Int = 0,
    items: List[PoolEntry] = Nil,
    cbs: List[Promise] = Nil,
    closedItems: Map[BigInt, Int] = Map.empty,
    disposeCbs: List[DisposePromise] = Nil,
    disposal: Disposal = Disposal.Unclaimed,
    serial: BigInt = BigInt(0),
    retirements: Map[BigInt, Retirement] = Map.empty
  ) {
    def itemIsClosed(id: BigInt): Boolean = closedItems.contains(id)
    def disposed: Boolean                 = disposal == Disposal.Claimed
  }

  @tailrec private def allocateId(): BigInt = {
    val current = pool.get()
    val id      = current.serial + 1
    if (pool.compareAndSet(current, current.copy(serial = id))) id else allocateId()
  }

  private val pool             = new AtomicReference[PoolData](PoolData())
  private val maxIdleTimeNanos = maxIdleTime.toNanos
}

object AsyncResourcePool {

  sealed trait Borrow[F[_], +T] {
    def value: T
    def give(): F[Unit]

    /**
     * Permanently retire this resource without returning a closed value to a
     * waiter.
     */
    def discard(): F[Unit]
  }

  implicit def asyncPoolMapClose[F[_]: Effect, K, T]: Close[F, AsyncResourcePool[F, T]] =
    new Close[F, AsyncResourcePool[F, T]] {
      def onClose(that: AsyncResourcePool[F, T]): F[Unit] =
        that.onDispose()

      def close(that: AsyncResourcePool[F, T]): F[Unit] =
        that.dispose()
    }
}
