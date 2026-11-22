package spoonbill

import java.util.concurrent.atomic.AtomicLong

object Metrics {

  sealed trait Metric[T]

  final class LongMetric(name: String) extends Metric[Long] {
    private val ref                   = new AtomicLong(0)
    def update(f: Long => Long): Unit = ref.updateAndGet(x => f(x))
    def get: Long                     = ref.get()
  }

  // Diff time includes changes inference and preparation of the message for a browser.
  final val MinDiffNanos = new LongMetric("min-diff-nanos")
  final val MaxDiffNanos = new LongMetric("max-diff-nanos")

  // Browser events discarded because a newer render was published before they ran: in a
  // guarded view that recovers, and in a view of a long-polling entry.
  final val ObsoleteBrowserEvents = new LongMetric("obsolete-browser-events")

  // Unguarded attaches whose stored state was missing. The page was told to reload, and
  // no state was built from the bridge request. See ADR 0006, which says when to revisit it.
  final val MissingStateReloads = new LongMetric("missing-state-reloads")
}
