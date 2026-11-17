package spoonbill.effect

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Future's operations without the FutureEffect type. Stream operators serve it
 * by a reader fiber and its own `promise`, as they serve a native effect.
 */
class ReaderFiberFutureEffect(future: Effect.FutureEffect = new Effect.FutureEffect) extends Effect[Future] {
  def pure[A](value: A): Future[A]                                                     = future.pure(value)
  def delay[A](value: => A): Future[A]                                                 = future.delay(value)
  def uncancelable[A](task: => Future[A]): Future[A]                                   = future.uncancelable(task)
  def fail[A](error: Throwable): Future[A]                                             = future.fail(error)
  def unit: Future[Unit]                                                               = future.unit
  def never[T]: Future[T]                                                              = future.never
  def fromTry[A](value: => Try[A]): Future[A]                                          = future.fromTry(value)
  def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A]          = future.promise(register)
  def promiseF[A](register: (Either[Throwable, A] => Unit) => Future[Unit]): Future[A] = future.promiseF(register)
  def flatMap[A, B](value: Future[A])(f: A => Future[B]): Future[B]                    = future.flatMap(value)(f)
  def map[A, B](value: Future[A])(f: A => B): Future[B]                                = future.map(value)(f)
  def recover[A, AA >: A](value: Future[A])(f: PartialFunction[Throwable, AA]): Future[AA] =
    future.recover[A, AA](value)(f)
  def recoverF[A, AA >: A](value: Future[A])(f: PartialFunction[Throwable, Future[AA]]): Future[AA] =
    future.recoverF[A, AA](value)(f)
  def start[A](task: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] =
    future.start(task)
  def blocking[T](body: => T)(implicit ec: ExecutionContext): Future[T]           = future.blocking(body)
  def fork[A](task: => Future[A])(implicit ec: ExecutionContext): Future[A]       = future.fork(task)
  def sequence[A](values: List[Future[A]]): Future[List[A]]                       = future.sequence(values)
  def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit = future.runAsync(value)(callback)
  def run[A](value: Future[A]): Either[Throwable, A]                              = future.run(value)
  def toFuture[A](value: Future[A]): Future[A]                                    = value
}
