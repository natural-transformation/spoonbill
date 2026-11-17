package spoonbill.auth

/**
 * Pure admission, key ownership and queue-scan decisions. Callback envelopes,
 * queue storage/cursor, metrics and close results belong to the interpreter.
 * These decisions are evaluated under the same monitor as those resources.
 */
private[auth] object BrowserCoordinatorGateMachine {
  enum Admission {
    case Ordinary, Cleanup, Close
    case Read(key: Any)
  }
  enum Turn {
    case Write
    case Read(key: Any)
  }
  enum WritePhase { case Idle, Writing }
  // The read-key set is itself the read occupancy: no per-turn wrapper.
  type Occupancy = WritePhase | Set[Any]
  object Occupancy {
    val Idle: Occupancy    = WritePhase.Idle
    val Writing: Occupancy = WritePhase.Writing
  }
  enum Closure        { case Open, Closing, Closed                   }
  enum Decision       { case Reject, ReplayClose, JoinClose, Enqueue }
  enum ReaderDecision { case Stop, Blocked, Grant                    }
  enum WriterDecision { case Stop, GrantAndStop                      }

  def isClosing(closure: Closure): Boolean = closure != Closure.Open
  def runningReads(occupancy: Occupancy): Int = occupancy match {
    case keys: Set[Any] @unchecked => keys.size
    case _                         => 0
  }
  def admission(closure: Closure, queued: Int, kind: Admission, capacity: Int): Decision = {
    val rejectable = kind match {
      case Admission.Ordinary | Admission.Read(_) => true
      case _                                      => false
    }
    if (rejectable && (isClosing(closure) || queued >= capacity)) Decision.Reject
    else if (!rejectable && closure == Closure.Closed) Decision.ReplayClose
    else if (!rejectable && closure == Closure.Closing) Decision.JoinClose
    else Decision.Enqueue
  }
  def requested(closure: Closure, kind: Admission): Closure =
    if (kind == Admission.Close && closure == Closure.Open) Closure.Closing else closure
  def completed(closure: Closure): Closure = closure match {
    case Closure.Closing => Closure.Closed
    case other           => other
  }
  def writerDecision(occupancy: Occupancy, noBlockedReads: Boolean): WriterDecision =
    if (noBlockedReads && occupancy != Occupancy.Writing && runningReads(occupancy) == 0) WriterDecision.GrantAndStop
    else WriterDecision.Stop
  def readerDecision(occupancy: Occupancy, key: Any, capacity: Int): ReaderDecision = occupancy match {
    case WritePhase.Writing => ReaderDecision.Stop
    case keys: Set[Any] @unchecked =>
      if (keys.size >= capacity) ReaderDecision.Stop
      else if (keys(key)) ReaderDecision.Blocked
      else ReaderDecision.Grant
    case _ => ReaderDecision.Grant
  }
  def writerStarted: Occupancy = WritePhase.Writing

  /** Called only after Grant in the same interpreter critical section. */
  def readerStarted(occupancy: Occupancy, key: Any): Occupancy = occupancy match {
    case keys: Set[Any] @unchecked => keys + key
    case _                         => Set.empty[Any] + key
  }
  def finished(occupancy: Occupancy, turn: Turn): Occupancy = turn match {
    case Turn.Write => if (occupancy == WritePhase.Writing) WritePhase.Idle else occupancy
    case Turn.Read(key) =>
      occupancy match {
        case keys: Set[Any] @unchecked if keys(key) =>
          val remaining = keys - key
          if (remaining.isEmpty) WritePhase.Idle else remaining
        case _ => occupancy
      }
  }
}
