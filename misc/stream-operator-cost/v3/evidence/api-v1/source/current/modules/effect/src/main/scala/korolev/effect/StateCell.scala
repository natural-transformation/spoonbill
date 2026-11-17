package spoonbill.effect

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/** One immutable value, changed by pure functions. */
private[spoonbill] final class StateCell[A](initial: A) {
  private val value = new AtomicReference[A](initial)

  def get: A = value.get()

  /** `change` may run more than once under contention, so it must be pure. */
  @tailrec def modify[B](change: A => (A, B)): B = {
    val before          = value.get()
    val (after, result) = change(before)
    if (value.compareAndSet(before, after)) result else modify(change)
  }
}
