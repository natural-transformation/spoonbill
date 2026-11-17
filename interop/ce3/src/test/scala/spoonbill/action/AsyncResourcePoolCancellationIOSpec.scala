package spoonbill.action

import _root_.cats.effect.{IO, IOLocal}
import _root_.cats.effect.unsafe.{implicits, IORuntime}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Success
import spoonbill.cats.IO3Effect
import spoonbill.effect.{AsyncResourcePool, Close, Effect}
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolCancellationIOSpec extends AnyFlatSpec with Matchers {
  private class Worker extends ExecutionContext {
    private val tasks                         = new ConcurrentLinkedQueue[Runnable]()
    def execute(task: Runnable): Unit         = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def drain(): Unit = {
      var task  = tasks.poll()
      var count = 0
      while (task != null) {
        task.run(); count += 1; assert(count < 10000)
        task = tasks.poll()
      }
    }
  }
  private final class Resource { val closed = Promise[Unit]() }

  private class Fixture {
    val worker              = new Worker
    private val global      = implicits.global
    val runtime             = IORuntime(worker, ExecutionContext.global, global.scheduler, () => (), global.config)
    val holdNextPublication = new AtomicBoolean(false)
    val publication         = new AtomicReference(Option.empty[() => Unit])
    given Effect[IO] = new IO3Effect(runtime) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register { result =>
            if (holdNextPublication.compareAndSet(true, false)) publication.set(Some(() => callback(result)))
            else callback(result)
          }
        }
    }
    given Close[IO, Resource] with {
      def onClose(value: Resource): IO[Unit] = IO.async_ { callback =>
        value.closed.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
      }
      def close(value: Resource): IO[Unit] = IO { value.closed.trySuccess(()); () }
    }
    def run[A](value: IO[A]): A = {
      val result   = value.unsafeToFuture()(runtime)
      val deadline = System.nanoTime() + 3.seconds.toNanos
      while (!result.isCompleted && System.nanoTime() < deadline) { worker.drain(); Thread.`yield`() }
      Await.result(result, 3.seconds)
    }
    def pool(factory: => IO[Resource]) =
      new AsyncResourcePool[IO, Resource]("canceled-creation", factory, () => IO.pure(0L), 1, 1.minute)
    def close(resource: Resource): Unit = run(summon[Close[IO, Resource]].close(resource))
  }

  "A canceled pool use" should "return a loan assigned after its waiting acquisition was canceled" in {
    val worker       = new Worker
    val global       = implicits.global
    val runtime      = IORuntime(worker, ExecutionContext.global, global.scheduler, () => (), global.config)
    given Effect[IO] = new IO3Effect(runtime)
    given Close[IO, Resource] with {
      def onClose(value: Resource): IO[Unit] = IO.async_ { callback =>
        value.closed.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
      }
      def close(value: Resource): IO[Unit] = IO { value.closed.trySuccess(()); () }
    }
    def run[A](value: IO[A]): A = {
      val result   = value.unsafeToFuture()(runtime)
      val deadline = System.nanoTime() + 3.seconds.toNanos
      while (!result.isCompleted && System.nanoTime() < deadline) { worker.drain(); Thread.`yield`() }
      Await.result(result, 3.seconds)
    }
    val pool    = new AsyncResourcePool[IO, Resource]("canceled-use", IO(new Resource), () => IO.pure(0L), 1, 1.minute)
    val first   = run(pool.borrow())
    val waiting = run(pool.use(_ => IO.unit).start)
    pool.unsafeGetState.cbs.size shouldBe 1
    val canceling = waiting.cancel.unsafeToFuture()(runtime)
    worker.drain()
    info(s"Cancellation completed before capacity returned: ${canceling.isCompleted}")
    var probe = Option.empty[Future[AsyncResourcePool.Borrow[IO, Resource]]]
    try {
      canceling.isCompleted shouldBe true
      pool.unsafeGetState.cbs shouldBe empty
      run(first.give())
      worker.drain()
      Await.result(canceling, 3.seconds)
      info(s"Consumer outcome after cancellation: ${run(waiting.join)}")
      val next = pool.borrow().unsafeToFuture()(runtime)
      probe = Some(next)
      worker.drain()
      next.isCompleted shouldBe true
      run(Await.result(next, 3.seconds).give())
    } finally {
      // Closing the physical resource also recovers an old implementation's lost loan.
      run(first.give())
      run(summon[Close[IO, Resource]].close(first.value))
      worker.drain()
      probe.flatMap(_.value).collect { case Success(borrowed) => run(borrowed.discard()) }
    }
  }

  it should "return a late factory result without running a canceled callback" in {
    val f        = new Fixture
    val factory  = Promise[Resource]()
    val resource = new Resource
    val pool = f.pool(IO.async_[Resource] { callback =>
      factory.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
    })
    var entered  = false
    val useFiber = f.run(pool.use(_ => IO { entered = true }).start)
    f.run(useFiber.cancel)
    var probe = Option.empty[Future[AsyncResourcePool.Borrow[IO, Resource]]]
    try {
      factory.success(resource)
      f.worker.drain()
      entered shouldBe false
      val next = pool.borrow().unsafeToFuture()(f.runtime)
      probe = Some(next)
      f.worker.drain()
      next.isCompleted shouldBe true
      val borrowed = Await.result(next, 3.seconds)
      borrowed.value should be theSameInstanceAs resource
      f.run(borrowed.give())
    } finally {
      factory.trySuccess(resource)
      f.worker.drain()
      f.close(resource)
      f.worker.drain()
      probe.flatMap(_.value).collect { case Success(borrowed) => f.run(borrowed.discard()) }
    }
  }

  it should "keep reusable use effects and callbacks in the caller's fiber context" in {
    val f         = new Fixture
    val pool      = f.pool(IO(new Resource))
    val local     = f.run(IOLocal("default"))
    val operation = pool.use(_ => local.get)
    f.run(local.set("first").flatMap(_ => operation)) shouldBe "first"
    f.run(local.set("second").flatMap(_ => operation)) shouldBe "second"
    f.run(pool.dispose())
  }

  it should "own an assigned loan before its continuation is published to the caller" in {
    val f     = new Fixture
    val pool  = f.pool(IO(new Resource))
    val first = f.run(pool.borrow())
    f.run(first.give())
    var entered = false
    f.holdNextPublication.set(true)
    val useFiber = f.run(pool.use(_ => IO { entered = true }).start)
    var probe    = Option.empty[Future[AsyncResourcePool.Borrow[IO, Resource]]]
    try {
      f.publication.get() should not be empty
      f.run(useFiber.cancel)
      f.publication.getAndSet(None).get.apply()
      f.worker.drain()
      entered shouldBe false
      val next = pool.borrow().unsafeToFuture()(f.runtime)
      probe = Some(next)
      f.worker.drain()
      next.isCompleted shouldBe true
      f.run(Await.result(next, 3.seconds).give())
    } finally {
      f.run(useFiber.cancel)
      f.publication.getAndSet(None).foreach(_.apply())
      f.worker.drain()
      f.close(first.value)
      f.worker.drain()
      probe.flatMap(_.value).collect { case Success(borrowed) => f.run(borrowed.discard()) }
    }
  }
}
