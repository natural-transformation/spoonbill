package spoonbill.security.jdbc

import java.time.{Duration, Instant}

/**
 * Pure rules for the observation and durable-checkpoint frontiers. An observed
 * candidate can advance before a checkpoint fails, so only an acknowledged
 * checkpoint can authorize reuse. The interpreter retains each frontier's
 * existing publication boundary and supplies all time samples explicitly.
 */
private[jdbc] object JdbcClockMachine {
  def observe(previous: Instant, sampled: Instant): Instant =
    if (sampled.isAfter(previous)) sampled else previous

  def acknowledge(previous: Option[Instant], persisted: Instant): Option[Instant] =
    Some(previous.filter(_.isAfter(persisted)).getOrElse(persisted))

  def covered(acknowledged: Option[Instant], candidate: Instant): Option[Instant] =
    acknowledged.filter(checkpoint => !candidate.isAfter(checkpoint)).map(_ => candidate)

  def renewAhead(checkpoint: Instant, wall: Instant, window: Duration): Boolean =
    !wall.isBefore(checkpoint.minus(window))

  def externallyAdvanced(target: Instant, persisted: Instant): Boolean =
    persisted.isAfter(target)

  /**
   * A shared writer's checkpoint, decided under the clock row lock: never below
   * the stored floor or this process's floor, which already holds the wall
   * sample taken after the lock.
   */
  def sharedCheckpoint(stored: Instant, process: Instant): Instant =
    observe(stored, process)

  /** Signed subtraction preserves the interpreter's monotonic-counter wrap rule. */
  def failedWhileWaiting(failedAt: Option[Long], waiting: Long): Boolean =
    failedAt.exists(time => time - waiting > 0)
}
