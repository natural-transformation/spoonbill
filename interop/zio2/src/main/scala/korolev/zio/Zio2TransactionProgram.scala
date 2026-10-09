package spoonbill.zio

import _root_.zio.{Task, ZIO}
import spoonbill.security.transaction.TransactionProgram

/** Native transaction composition. Database acquisition, commit, rollback and
  * serialized connection access remain the TransactionExecutor's responsibility.
  * ensuring installs an interruption-safe finalizer before deferred evaluation;
  * no blocking bridge or unsafe runtime execution is involved.
  */
object Zio2TransactionProgram extends TransactionProgram[Task] {
  def pure[A](value: A): Task[A] = ZIO.succeed(value)
  def map[A, B](value: Task[A])(f: A => B): Task[B] = value.flatMap(a => ZIO.attempt(f(a)))
  def flatMap[A, B](value: Task[A])(f: A => Task[B]): Task[B] =
    value.flatMap(a => defer(f(a)))
  def defer[A](value: => Task[A]): Task[A] = ZIO.attempt(value).flatten
  def guarantee[A](value: => Task[A])(finalizer: => Unit): Task[A] =
    defer(value).ensuring(ZIO.succeed(finalizer))
}
