package bench

import java.math.{BigDecimal, RoundingMode}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import spoonbill.auth.BrowserView
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.snapshot.ViewSnapshotSession

/** A bounded, thread-safe set of durations with percentiles in milliseconds. */
final class Samples(capacity: Int) {
  private val values  = new ArrayBuffer[Long]()
  private var dropped = 0L

  def add(nanos: Long): Unit = synchronized { if (values.size < capacity) values += nanos else dropped += 1 }

  private def ms(nanos: Long): String = new BigDecimal(nanos / 1000000.0).setScale(2, RoundingMode.HALF_UP).toPlainString

  def json: String = synchronized {
    if (values.isEmpty) "null"
    else {
      val sorted         = values.sorted
      def pct(q: Double) = sorted(math.min(sorted.size - 1, math.max(0, math.ceil(q * sorted.size).toInt - 1)))
      s"""{"count":${sorted.size},"dropped":$dropped,"p50":${ms(pct(.5))},"p95":${ms(pct(.95))},""" +
        s""""p99":${ms(pct(.99))},"max":${ms(sorted.last)},"mean":${ms(sorted.sum / sorted.size)}}"""
    }
  }
}

/**
 * Counts and times the coordinator's calls into the snapshot session, so the
 * run can relate provider transactions to logical operations: one
 * initialization per attached view (an empty view publishes its initial
 * projection, a reconnect restores) and one commit per state transition.
 */
final class SnapshotCounters[S](using ExecutionContext) {
  val initializations = new AtomicLong()
  val commits         = new AtomicLong()
  val failures        = new AtomicLong()
  private val initializeNanos = new Samples(200000)
  private val commitNanos     = new Samples(200000)

  def wrap(
    inner: BrowserView[String] => Option[ViewSnapshotSession[Future, S]]
  ): BrowserView[String] => Option[ViewSnapshotSession[Future, S]] = view =>
    inner(view).map { session =>
      new ViewSnapshotSession[Future, S] {
        def ownerEpoch: ViewOwnershipEpoch    = session.ownerEpoch
        def initialize(fresh: S): Future[S]   = timed(initializeNanos, initializations)(session.initialize(fresh))
        def commit(candidate: S): Future[Unit] = timed(commitNanos, commits)(session.commit(candidate))
      }
    }

  private def timed[A](samples: Samples, counter: AtomicLong)(body: => Future[A]): Future[A] = {
    counter.incrementAndGet()
    val started = System.nanoTime()
    val result =
      try body
      catch { case NonFatal(error) => Future.failed(error) }
    result.transform { outcome =>
      samples.add(System.nanoTime() - started)
      if (outcome.isFailure) failures.incrementAndGet()
      outcome
    }(ExecutionContext.parasitic)
  }

  def json: String =
    s"""{"initializations":${initializations.get},"commits":${commits.get},"failures":${failures.get},""" +
      s""""initializeMs":${initializeNanos.json},"commitMs":${commitNanos.json}}"""
}
