package spoonbill.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.server.SessionAccessDenied

final class FrontendCloseSpec extends AsyncFlatSpec with Matchers {
  private given Reporter = Reporter.PrintReporter

  "Frontend close" should "finish later cleanup when a user-action callback throws" in {
    val expected = new IllegalStateException("Completion callback failed")
    val failCallback = new AtomicBoolean(true)
    given Effect[Future] = new Effect[Future] {
      export Effect.futureEffect.{
        pure, delay, uncancelable, fail, unit, never, fromTry,
        promiseF, flatMap, map, recover, recoverF, start, blocking,
        fork, sequence, runAsync, run, toFuture
      }
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] =
        Effect.futureEffect.promise { complete =>
          register { result =>
            complete(result)
            if (result.left.exists(_.isInstanceOf[SessionAccessDenied]) && failCallback.compareAndSet(true, false))
              throw expected
          }
        }
    }
    val canceled = new AtomicInteger()
    val incoming = new Stream[Future, String] {
      def pull(): Future[Option[String]] = Future.never
      def cancel(): Future[Unit] = { canceled.incrementAndGet(); Future.unit }
    }
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val browser = new Frontend[Future](incoming, None, authorize = Some(() => Future.unit),
      beforeUserCallback = Some(() => { entered.trySuccess(()); release.future }))
    val action = browser.runUserAction(Future.unit)
    val queued = browser.runUserAction(Future.unit)
    for {
      _ <- entered.future
      error <- browser.close().failed
      _ = canceled.get() shouldBe 1
      actionError <- action.failed
      queuedError <- queued.failed
      output <- browser.outgoingMessages.pull()
      _ <- browser.close()
    } yield {
      release.trySuccess(())
      error should be theSameInstanceAs expected
      actionError shouldBe a[SessionAccessDenied]
      queuedError shouldBe a[SessionAccessDenied]
      output shouldBe None
      canceled.get() shouldBe 1
    }
  }

  it should "claim cleanup once while the first close is still waiting for input cancellation" in {
    given Effect[Future] = Effect.futureEffect
    val canceled = new AtomicInteger()
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val incoming = new Stream[Future, String] {
      def pull(): Future[Option[String]] = Future.never
      def cancel(): Future[Unit] = { canceled.incrementAndGet(); entered.trySuccess(()); release.future }
    }
    val browser = new Frontend[Future](incoming, None)
    val closing = browser.close()
    for {
      _ <- entered.future
      repeated = browser.close()
      _ = canceled.get() shouldBe 1
      _ = release.success(())
      _ <- closing
      _ <- repeated
    } yield canceled.get() shouldBe 1
  }

  it should "allow recursive input cancellation without repeating or waiting on its own close" in {
    given Effect[Future] = Effect.futureEffect
    val canceled = new AtomicInteger()
    lazy val incoming: Stream[Future, String] = new Stream[Future, String] {
      def pull(): Future[Option[String]] = Future.never
      def cancel(): Future[Unit] = { canceled.incrementAndGet(); browser.close() }
    }
    lazy val browser: Frontend[Future] = new Frontend[Future](incoming, None)
    browser.close().map(_ => canceled.get() shouldBe 1)
  }

  it should "fence output before a competing close can return while publication holds its monitor" in {
    given Effect[Future] = Effect.futureEffect
    val browser = new Frontend[Future](new Stream[Future, String] {
      def pull(): Future[Option[String]] = Future.never
      def cancel(): Future[Unit] = Future.unit
    }, None, authorize = Some(() => Future.unit))
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val publisher = new Thread(() => {
      browser.acceptInitialRender(() => { entered.countDown(); release.await(5, TimeUnit.SECONDS); () })
      ()
    })
    def closing(): (Thread, Future[Unit]) = {
      val done = Promise[Unit]()
      val thread = new Thread(() => browser.close().onComplete(done.tryComplete)(ExecutionContext.parasitic))
      thread.start()
      thread -> done.future
    }
    def settledOrBlocked(thread: Thread, done: Future[Unit]): Unit = {
      val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!done.isCompleted && thread.getState != Thread.State.BLOCKED && System.nanoTime() < until) Thread.`yield`()
      assert(done.isCompleted || thread.getState == Thread.State.BLOCKED)
    }
    publisher.start()
    assert(entered.await(5, TimeUnit.SECONDS))
    val (firstThread, first) = closing()
    settledOrBlocked(firstThread, first)
    val (secondThread, second) = closing()
    try {
      settledOrBlocked(secondThread, second)
      second.isCompleted shouldBe false
    } finally release.countDown()
    for {
      _ <- first
      _ <- second
    } yield browser.interactionIsLive shouldBe false
  }
}
