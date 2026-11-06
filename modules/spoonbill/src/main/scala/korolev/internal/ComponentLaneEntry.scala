package spoonbill.internal

import spoonbill.effect.Effect
import spoonbill.effect.syntax.*

/** A pulled entry is active even while its effect waits for authorization. */
private[spoonbill] final class ComponentLaneEntry[F[_]: Effect](
  operation: () => F[Unit],
  onDiscard: () => Unit = () => ()
) {
  private var claimed = false
  def run(): F[Unit] = {
    val start = synchronized(if (claimed) false else { claimed = true; true })
    if (start) Effect[F].delayAsync(operation()) else Effect[F].unit
  }
  def discard(): Unit = {
    val discard = synchronized(if (claimed) false else { claimed = true; true })
    if (discard) onDiscard()
  }
}
