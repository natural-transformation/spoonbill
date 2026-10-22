package spoonbill.effect

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import spoonbill.effect.syntax._
import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

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

  private class BorrowImpl(val value: T, retire: () => F[Unit]) extends Borrow[F, T] {
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
        if (ref.itemIsClosed(value)) {
          // Item is closed.
          // Remove it from closed list and forget.
          val id       = System.identityHashCode(value)
          val newState = ref.copy(closedItems = ref.closedItems - id)
          if (pool.compareAndSet(ref, newState)) {
            //reporter.debug("%s - Closed item finally removed from pool", name)
          } else {
            loop(nanos)
          }
        } else {
          if (ref.cbs.isEmpty) {
            // There is no callbacks. Add item back to pool
            val item     = PoolItem(nanos, value)
            item.retire = retire
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
                  cb(Right(new BorrowImpl(value, retire)))
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

  def use[R](f: T => F[R]): F[R] =
    for {
      borrow <- borrow()
      result <- f(borrow.value)
      _      <- borrow.give()
    } yield {
      result
    }

  /**
   * Borrow item from the pool.
   *   1. Borrow one of idle items if it exists 2. Otherwise try to creates one
   *      using [[factory]] 3. If [[maxCount]] is reached, waits until one of
   *      borrowed items would given back
   * @return
   */
  def borrow(): F[Borrow[F, T]] = {
    @tailrec def onCloseLoop(value: T): Unit = {
      val ref = pool.get()
      // Check value not in closed list
      if (!ref.itemIsClosed(value)) {
        val closedItems = ref.closedItems + System.identityHashCode(value)
        val items       = ref.items.filter(_.value != value)
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
          onCloseLoop(value)
        }
      }
    }

    @tailrec def creationFailed(): Unit = {
      val ref = pool.get()
      val waiting = ref.cbs.headOption
      val next = ref.copy(total = if (waiting.isDefined) ref.total else ref.total - 1, cbs = ref.cbs.drop(1))
      if (pool.compareAndSet(ref, next)) waiting.foreach(startCreation)
      else creationFailed()
    }

    def createNew(): F[Borrow[F, T]] =
      Effect[F].delayAsync(factory).recoverF { case scala.util.control.NonFatal(error) =>
        Effect[F].delay(creationFailed()).flatMap(_ => Effect[F].fail(error))
      }.map { value =>
        val observed = new AtomicBoolean(false)
        val settled = AsyncTable.unsafeCreateEmpty[F, Unit, Unit]
        def recordClose(): Unit =
          if (observed.compareAndSet(false, true)) {
            onCloseLoop(value)
            settled.put((), ()).runAsyncForget
          }
        def retire(): F[Unit] =
          Close[F, T].close(value).flatMap { _ =>
            Effect[F].delay(recordClose()).flatMap(_ => settled.get(())).unit
          }
        //reporter.debug("%s - Resource created", name)
        Close[F, T].onClose(value).runAsync(_ => recordClose())
        new BorrowImpl(value, () => retire())
      }

    // Future's Effect can complete inline. Trampoline all launches so a failed
    // factory cannot recursively walk the waiter backlog on the current stack.
    // Every queued launch already owns one of maxCount creation reservations;
    // waiters remain in cbs, with no additional pool-owned work queue or executor.
    def startCreation(callback: Promise): Unit =
      ExecutionContext.parasitic.execute(() => createNew().runAsync(callback))

    Effect[F].promise { cb =>
      @tailrec def loop(): Unit = pool.get() match {
        case ref @ PoolState(_, x :: xs, _, _, _, _) =>
          // Return item from pool
          if (pool.compareAndSet(ref, ref.copy(items = xs))) {
            //reporter.debug("%s - Return item from pool", name)
            cb(Right(new BorrowImpl(x.value, x.retire)))
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

    Effect[F].delay(loop())
  }

  def dispose(): F[Unit] = {
    @tailrec def loop(): F[Unit] = {
      val ref = pool.get
      if (!ref.disposed) {
        if (pool.compareAndSet(ref, ref.copy(disposed = true))) {
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

  def cleanup(): F[Int] = {
    @tailrec
    def loop(nanos: Long): F[Int] = {
      val ref         = pool.get()
      val itemsFilter = (item: PoolItem) => nanos - item.idle < maxIdleTimeNanos
      val items       = ref.items.filter(itemsFilter)

      if (!items.eq(ref.items)) {
        val obsolete      = ref.items.filterNot(itemsFilter)
        val obsoleteCount = obsolete.length
        val obsoleteIds   = obsolete.map(x => System.identityHashCode(x.value))
        val closedSet     = ref.closedItems ++ obsoleteIds
        val total         = ref.total - obsoleteCount
        val newValue      = ref.copy(total = total, items = items, closedItems = closedSet)
        if (pool.compareAndSet(ref, newValue)) {
          val xs = obsolete.map(item => implicitly[Close[F, T]].close(item.value))
          Effect[F].sequence(xs).map { _ =>
            @tailrec def removeClosedSedLoop(): Unit = {
              val ref = pool.get
              if (obsoleteIds.forall(ref.closedItems.contains)) {
                val closedItems = ref.closedItems -- obsoleteIds
                if (!pool.compareAndSet(ref, ref.copy(closedItems = closedItems))) {
                  removeClosedSedLoop()
                }
              }
            }
            removeClosedSedLoop()
            obsoleteCount
          }
        } else {
          loop(nanos)
        }
      } else {
        Effect[F].pure(0)
      }
    }
    currentNanos().flatMap(loop)
  }

  /**
   * For debug purposes only
   */
  def unsafeGetState: PoolState =
    pool.get

  type Promise = Effect.Promise[Borrow[F, T]]

  type DisposePromise = Effect.Promise[Unit]

  case class PoolItem(idle: Long = 0L, value: T) {
    private[AsyncResourcePool] var retire: () => F[Unit] = () => Close[F, T].close(value)
  }

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

  private val pool             = new AtomicReference[PoolState](PoolState())
  private val maxIdleTimeNanos = maxIdleTime.toNanos
}

object AsyncResourcePool {

  sealed trait Borrow[F[_], +T] {
    def value: T
    def give(): F[Unit]
    /** Permanently retire this resource without returning a closed value to a waiter. */
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
