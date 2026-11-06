package spoonbill.timer

import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * Declare anonymous IDs once; named IDs may be reconstructed while rendering.
 */
sealed trait TimerId[S]

object TimerId {
  private final class Anonymous[S]                extends TimerId[S]
  private final case class Named[S](name: String) extends TimerId[S]
  private[spoonbill] def fresh[S](): TimerId[S]             = new Anonymous[S]
  private[spoonbill] def named[S](name: String): TimerId[S] = Named(name)
  private[spoonbill] def valid(id: TimerId[?]): Boolean = id match {
    case Named(name)     => name != null && name.nonEmpty && name.length <= 128
    case _: Anonymous[?] => true
    case null            => false
  }
  private[spoonbill] def diagnostic(id: TimerId[?]): String = id match {
    case Named(name) if valid(id) =>
      name.iterator.map { c =>
        if (c.isLetterOrDigit && c < 128 || "-_.:".contains(c)) c.toString
        else f"\\u${c.toInt}%04x"
      }.mkString.take(96)
    case _: Anonymous[?] => s"anonymous-${Integer.toHexString(System.identityHashCode(id))}"
    case _               => "invalid-id"
  }
}

enum RestartKey {
  case Stable
  case Text(value: String)
  case Number(value: Long)
  case Uuid(value: UUID)
  case Parts(values: Vector[RestartKey])
}

object RestartKey {
  private[spoonbill] def valid(key: RestartKey): Boolean = {
    var remaining  = 64
    var characters = 1024
    def visit(value: RestartKey, depth: Int): Boolean = {
      remaining -= 1
      if (remaining < 0 || depth > 8 || value == null) false
      else
        value match {
          case RestartKey.Text(text) =>
            if (text == null) false else { characters -= text.length; characters >= 0 }
          case RestartKey.Uuid(uuid)   => uuid != null
          case RestartKey.Parts(parts) => parts != null && parts.size <= remaining && parts.forall(visit(_, depth + 1))
          case _                       => true
        }
    }
    visit(key, 0)
  }
}

enum TimerConfigurationError {
  case NotFinite, NotWholeMilliseconds, OutOfRange
}

final class DelayDuration private (val milliseconds: Long) {
  private[spoonbill] def nanos: Long = milliseconds * 1000000L
}
object DelayDuration {
  val MaxMilliseconds: Long = Long.MaxValue / 1000000L
  def from(value: Duration): Either[TimerConfigurationError, DelayDuration] =
    milliseconds(value, 0).map(new DelayDuration(_))

  private[timer] def milliseconds(value: Duration, minimum: Int): Either[TimerConfigurationError, Long] = value match {
    case duration: FiniteDuration =>
      val nanos = BigInt(duration.length) * duration.unit.toNanos(1L)
      if (nanos % 1000000 != 0) Left(TimerConfigurationError.NotWholeMilliseconds)
      else {
        val millis = nanos / 1000000
        if (millis < minimum || millis > MaxMilliseconds) Left(TimerConfigurationError.OutOfRange)
        else Right(millis.toLong)
      }
    case _ => Left(TimerConfigurationError.NotFinite)
  }
}

final class Period private (val milliseconds: Long) {
  private[spoonbill] def nanos: Long = milliseconds * 1000000L
}
object Period {
  def from(value: Duration): Either[TimerConfigurationError, Period] =
    DelayDuration.milliseconds(value, 1).map(new Period(_))
}

/**
 * View-wide retained identities, including completed and unsettled retirements.
 */
final case class TimerSettings(maxRetained: Int = 1024) {
  require(maxRetained > 0 && maxRetained <= 65536, "Timer capacity must be between 1 and 65536")
}
