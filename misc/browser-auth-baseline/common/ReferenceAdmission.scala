package spoonbill.browserauthbaseline

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import spoonbill.server.SessionAccessDenied

/**
 * Bounded ownership of reference-app work before executor or SQL-gate dispatch.
 */
final class ReferenceAdmission(capacity: Int) {
  require(capacity > 0)
  private var pending     = 0
  private var peak        = 0
  private var stopped     = false
  private var maintenance = Option.empty[Promise[Unit]]
  private val drained     = Promise[Unit]()
  def counts: (Int, Int)  = synchronized((pending + maintenance.size) -> peak)
  def isClosed: Boolean   = synchronized(stopped)
  def submit[A](operation: => Future[A]): Future[A] = {
    val accepted = synchronized {
      if (stopped || pending >= capacity) false
      else { pending += 1; peak = math.max(peak, pending + maintenance.size); true }
    }
    if (!accepted) Future.failed(new SessionAccessDenied)
    else {
      val result = Promise[A]()
      val work =
        try operation
        catch { case NonFatal(error) => Future.failed(error) }
      work.onComplete { outcome =>
        val finished = synchronized { pending -= 1; stopped && pending == 0 && maintenance.isEmpty }
        result.tryComplete(outcome)
        if (finished) drained.trySuccess(())
      }(ExecutionContext.parasitic)
      result.future
    }
  }
  def submitMaintenance(operation: => Future[Unit]): Future[Unit] = {
    val admission = synchronized {
      maintenance match {
        case Some(existing)  => Left(existing.future)
        case None if stopped => Left(Future.unit)
        case None =>
          val proposed = Promise[Unit]()
          maintenance = Some(proposed)
          peak = math.max(peak, pending + 1)
          Right(proposed)
      }
    }
    admission match {
      case Left(existing) => existing
      case Right(proposed) =>
        val work =
          try operation
          catch { case NonFatal(error) => Future.failed(error) }
        work.onComplete { outcome =>
          val finished = synchronized { maintenance = None; stopped && pending == 0 }
          proposed.tryComplete(outcome)
          if (finished) drained.trySuccess(())
        }(ExecutionContext.parasitic)
        proposed.future
    }
  }
  def close(): Future[Unit] = {
    val finished = synchronized { stopped = true; pending == 0 && maintenance.isEmpty }
    if (finished) drained.trySuccess(())
    drained.future
  }
}
