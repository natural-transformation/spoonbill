package spoonbill.effect

import java.util.concurrent.ExecutionException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

class FutureInstanceSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private final class QueuedContext extends ExecutionContext {
    val tasks                                 = ArrayBuffer.empty[Runnable]
    def execute(task: Runnable): Unit         = { tasks += task; () }
    def reportFailure(error: Throwable): Unit = throw error
    def runNext(): Unit                       = tasks.remove(0).run()
  }

  "The default Future instance" should "dispatch once and expose a failed child through its join" in {
    given ec: QueuedContext = new QueuedContext
    val failure             = new IllegalStateException("child construction")
    var entered             = false
    val fiber               = await(Effect.futureEffect.start[Unit] { entered = true; throw failure })
    ec.tasks.size shouldBe 1
    entered shouldBe false
    fiber.join().isCompleted shouldBe false
    ec.runNext()
    entered shouldBe true
    await(fiber.join().failed) should be theSameInstanceAs failure
    ec.tasks shouldBe empty
  }

  "Future instrumentation" should "observe dispatch and child completion without joining the child" in {
    given ec: QueuedContext      = new QueuedContext
    val events                   = ArrayBuffer.empty[String]
    val child                    = Promise[Int]()
    var completion: Future[Unit] = null
    val effect = Effect.future(
      Effect.FutureInstrumentation(
        beforeStart = () => { events += "before"; () },
        afterStart = result => { events += "after"; completion = result }
      )
    )
    val fiber = await(effect.start { events += "child"; child.future })
    events.toList shouldBe List("before", "after")
    completion.isCompleted shouldBe false
    fiber.join().isCompleted shouldBe false
    ec.runNext()
    events.toList shouldBe List("before", "after", "child")
    child.success(7)
    await(fiber.join()) shouldBe 7
    await(completion) shouldBe (())
  }

  it should "reject before dispatch when beforeStart throws" in {
    given ec: QueuedContext = new QueuedContext
    val failure             = new IllegalStateException("before dispatch")
    val effect              = Effect.future(Effect.FutureInstrumentation(beforeStart = () => throw failure))
    var entered             = false
    intercept[IllegalStateException](effect.start { entered = true; Future.unit }) should be theSameInstanceAs failure
    ec.tasks shouldBe empty
    entered shouldBe false
  }

  it should "leave an already-dispatched child alive when afterStart throws" in {
    given ec: QueuedContext      = new QueuedContext
    val failure                  = new IllegalStateException("after dispatch")
    var completion: Future[Unit] = null
    var entered                  = false
    val effect = Effect.future(Effect.FutureInstrumentation(afterStart = result => {
      completion = result
      throw failure
    }))
    intercept[IllegalStateException](effect.start { entered = true; Future.unit }) should be theSameInstanceAs failure
    ec.tasks.size shouldBe 1
    entered shouldBe false
    ec.runNext()
    entered shouldBe true
    await(completion) shouldBe (())
  }

  it should "run continuation checks on the thread executing each body" in {
    val owner  = Thread.currentThread()
    val events = ArrayBuffer.empty[String]
    val effect = Effect.future(Effect.FutureInstrumentation(continuation = Some { () =>
      Thread.currentThread() should be theSameInstanceAs owner
      events += "check"
      ()
    }))
    await(effect.delay { events += "delay"; 1 }) shouldBe 1
    val pending = Promise[Int]()
    val mapped = effect.map(pending.future) { value =>
      events += "map"; value + 1
    }
    events.toList shouldBe List("check", "delay")
    pending.success(1)
    await(effect.flatMap(mapped) { value =>
      events += "flatMap"; Future.successful(value + 1)
    }) shouldBe 3
    events.toList shouldBe List("check", "delay", "check", "map", "check", "flatMap")
  }

  it should "capture continuation hook failures without entering the user body" in {
    val failure = new IllegalStateException("continuation")
    val effect  = Effect.future(Effect.FutureInstrumentation(continuation = Some(() => throw failure)))
    var entered = false
    await(effect.delay { entered = true }.failed) should be theSameInstanceAs failure
    await(effect.map(Future.unit)(_ => entered = true).failed) should be theSameInstanceAs failure
    await(
      effect
        .flatMap(Future.unit) { _ =>
          entered = true; Future.unit
        }
        .failed
    ) should be theSameInstanceAs failure
    entered shouldBe false
  }

  "Built-in Future primitives" should "retain Future failure normalization for both factory choices" in {
    for (
      effect <- List(
                  Effect.futureEffect,
                  Effect.future(Effect.FutureInstrumentation()),
                  Effect.future(Effect.FutureInstrumentation(continuation = Some(() => ())))
                )
    ) {
      val fatal    = new InterruptedException("boxed by Future")
      val failed   = effect.fail[Int](fatal)
      val promised = effect.promise[Int](complete => complete(Left(fatal)))
      for (result <- List(failed, promised)) {
        val error = await(result.failed)
        error shouldBe a[ExecutionException]
        error.getCause should be theSameInstanceAs fatal
        val observed = Promise[Either[Throwable, Int]]()
        effect.runAsync(result) { value =>
          observed.success(value); ()
        }
        await(observed.future).left.toOption.get should be theSameInstanceAs error
      }
    }
  }

  it should "expose factories rather than an extensible implementation class" in {
    assertDoesNotCompile("new spoonbill.effect.Effect.FutureEffect")
    assertDoesNotCompile(
      "new spoonbill.effect.Effect.BuiltinFutureEffect(spoonbill.effect.Effect.FutureInstrumentation()) {}"
    )
  }
}
