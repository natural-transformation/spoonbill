package spoonbill.security.transaction

/** A transaction program with no effect allocation or scheduling. */
type Direct[A] = A

/** Composition inside one transaction, which may differ from the application's
  * outer effect (for example DBIO inside Future). Implementations are trusted
  * runtime adapters. defer must suspend construction as well as execution when
  * G is lazy, and capture construction failures in G when G represents failures.
  * map and flatMap must likewise capture nonfatal callback exceptions as G
  * failures, including OperationProtocolException, so the executor can classify
  * rejection and roll back. They must not silently turn rejections into an
  * unhandled native defect. Direct propagates these exceptions to its executor.
  *
  * guarantee installs its finalizer BEFORE evaluating its by-name program and
  * runs it exactly once when that evaluation succeeds, fails, or is cancelled.
  * Cancellation must use the native runtime's finalization mechanism. A runtime
  * without cancellation, such as Future, finalizes when its program terminates;
  * abandoning or cancelling an outer F observer is not cancellation of G. Direct
  * finalizes when its worker actually returns or throws, even if its F observer
  * was cancelled earlier. Finalizers supplied by this
  * protocol are synchronous and nonthrowing. Detached work is not permitted.
  */
trait TransactionProgram[G[_]] {
  def pure[A](value: A): G[A]
  def map[A, B](value: G[A])(f: A => B): G[B]
  def flatMap[A, B](value: G[A])(f: A => G[B]): G[B]
  def defer[A](value: => G[A]): G[A]
  def guarantee[A](value: => G[A])(finalizer: => Unit): G[A]

  final def delay[A](value: => A): G[A] = defer(pure(value))
}

object TransactionProgram {
  val direct: TransactionProgram[Direct] = new TransactionProgram[Direct] {
    def pure[A](value: A): A = value
    def map[A, B](value: A)(f: A => B): B = f(value)
    def flatMap[A, B](value: A)(f: A => B): B = f(value)
    def defer[A](value: => A): A = value
    def guarantee[A](value: => A)(finalizer: => Unit): A =
      try value finally finalizer
  }
}

enum TransactionScopePolicy {
  /** Access must stay on the thread that enters the transaction program. */
  case ThreadConfined
  /** Thread switches are allowed, but the adapter must serialize all operations
    * on this transaction and join them before completing the program. This flag
    * does not serialize raw Tx access or make a native client thread-safe.
    */
  case Serialized
}
