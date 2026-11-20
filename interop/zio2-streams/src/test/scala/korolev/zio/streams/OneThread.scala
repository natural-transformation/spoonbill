package spoonbill.zio.streams

import _root_.zio._
import java.util.concurrent.Executors

/**
 * Runs a case on one thread, so that the order of its fibers is fixed. A fiber
 * runs until it waits or yields. The fibers that can go on take turns in the
 * order in which they became ready, and a fiber reads what was sent to it in
 * the order in which it was sent.
 *
 * Some cases need a moment that no pause can hit: a pull that has come back
 * while its consumer has not taken the result yet. On one thread that moment is
 * a matter of order.
 */
private[streams] object OneThread {

  def apply[R, E, A](io: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(Executors.newSingleThreadExecutor()))(single =>
      // The fiber is still on that thread when the case ends. It leaves the thread before the thread is stopped.
      ZIO.yieldNow *> ZIO.succeed(single.shutdown())
    )(single => ZIO.onExecutor(Executor.fromJavaExecutor(single))(io))
}
