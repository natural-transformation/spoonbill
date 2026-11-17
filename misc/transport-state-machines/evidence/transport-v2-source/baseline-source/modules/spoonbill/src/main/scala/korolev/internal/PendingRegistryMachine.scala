package spoonbill.internal

/**
 * Bounded pending ownership; callback, payload and cancellation handles stay
 * outside. Tokens are supplied by the interpreter and distinguish successive
 * owners of a key.
 */
private[spoonbill] object PendingRegistryMachine {
  final class Token
  enum State[K] {
    case Open(entries: Map[K, Token])
    case Sealed(entries: Map[K, Token])
  }
  enum DuplicatePolicy { case Reject, Replace                         }
  enum Admission       { case Accepted, Closed, Duplicate, AtCapacity }

  def initial[K]: State[K] = State.Open(Map.empty[K, Token])
  def closed[K]: State[K]  = State.Sealed(Map.empty[K, Token])

  private def entries[K](state: State[K]): Map[K, Token] = state match {
    case State.Open(values)   => values
    case State.Sealed(values) => values
  }
  private def updated[K](state: State[K], values: Map[K, Token]): State[K] = state match {
    case State.Open(_)   => State.Open(values)
    case State.Sealed(_) => State.Sealed(values)
  }

  def size[K](state: State[K]): Int                               = entries(state).size
  def contains[K](state: State[K], key: K, token: Token): Boolean = entries(state).get(key).exists(_ eq token)

  def register[K](
    state: State[K],
    key: K,
    token: Token,
    capacity: Int,
    duplicates: DuplicatePolicy = DuplicatePolicy.Reject
  ): (State[K], Admission) = state match {
    case State.Sealed(_) => (state, Admission.Closed)
    case State.Open(values) =>
      if (duplicates == DuplicatePolicy.Reject && values.contains(key)) (state, Admission.Duplicate)
      else if (values.size >= capacity) (state, Admission.AtCapacity)
      else (State.Open(values.updated(key, token)), Admission.Accepted)
  }

  def take[K](state: State[K], key: K, expected: Option[Token]): (State[K], Option[Token]) = {
    val values = entries(state)
    values.get(key) match {
      case None                                       => (state, None)
      case Some(token) if expected.exists(_ ne token) => (state, None)
      case Some(token)                                => (updated(state, values - key), Some(token))
    }
  }

  /**
   * Seal admission without retiring handles: sensitive disposal removes each
   * key later.
   */
  def seal[K](state: State[K]): State[K] = state match {
    case State.Open(values) => State.Sealed(values)
    case _                  => state
  }
}
