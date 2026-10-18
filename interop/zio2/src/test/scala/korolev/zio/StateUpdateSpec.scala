package spoonbill.zio

import _root_.zio.{FiberRef, Promise, Task, ZEnvironment, ZIO}
import _root_.zio.test.{assertTrue, suite, test, TestAspect, ZIOSpecDefault}
import java.util.concurrent.atomic.AtomicInteger
import spoonbill.action.{StateUpdate, StateUpdateInterpreter, UiOutcome}
import spoonbill.effect.Effect

object StateUpdateSpec extends ZIOSpecDefault:
  private given Effect[Task] = taskEffectInstance(runtime)

  override def spec = suite("StateUpdate with ZIO 2")(
    test("no-update callbacks are lazy and follow the acknowledgement dependency") {
      val callbacks   = new AtomicInteger()
      val transitions = new AtomicInteger()
      val completion = StateUpdateInterpreter.callback[Task, Int] {
        callbacks.incrementAndGet()
        StateUpdate.noUpdate[Int]
      }(_ => ZIO.succeed { transitions.incrementAndGet(); () })
      val constructedLazily = callbacks.get() == 0 && transitions.get() == 0
      for
        entered         <- Promise.make[Nothing, Unit]
        acknowledgement <- Promise.make[Nothing, Unit]
        result <- ZIO.scoped {
                    for
                      worker               <- (entered.succeed(()) *> acknowledgement.await *> completion).forkScoped
                      _                    <- entered.await
                      beforeAcknowledgement = callbacks.get()
                      _                    <- acknowledgement.succeed(())
                      _                    <- worker.join
                    yield assertTrue(
                      constructedLazily,
                      beforeAcknowledgement == 0,
                      callbacks.get() == 1,
                      transitions.get() == 0
                    )
                  }
      yield result
    },
    test("a modification preserves caller environment and fiber locals without evaluating the reducer") {
      val callbacks   = new AtomicInteger()
      val reducers    = new AtomicInteger()
      val transitions = new AtomicInteger()
      type RequestTask[A] = ZIO[String, Throwable, A]
      val checked = ZIO.scoped {
        for
          requestRuntime <- ZIO.runtime[String]
          local          <- FiberRef.make("default")
          captured       <- Promise.make[Nothing, (String, String, Int => Int)]
          completion = {
            given Effect[RequestTask] = zioEffectInstance(requestRuntime)(identity[Throwable])(identity[Throwable])
            StateUpdateInterpreter.callback[RequestTask, Int] {
              callbacks.incrementAndGet()
              StateUpdate.Modify { state =>
                reducers.incrementAndGet(); state + 1
              }
            } { reduce =>
              ZIO.succeed(transitions.incrementAndGet()) *> ZIO.service[String].zip(local.get).flatMap {
                case (environment, value) =>
                  captured.succeed((environment, value, reduce)).unit
              }
            }
          }
          constructedLazily = callbacks.get() == 0
          _                <- local.set("request-local")
          _                <- completion
          result           <- captured.await
          reducerDeferred   = reducers.get() == 0
          updated           = result._3(41)
        yield assertTrue(
          constructedLazily,
          callbacks.get() == 1,
          transitions.get() == 1,
          reducerDeferred,
          reducers.get() == 1,
          result._1 == "request-environment",
          result._2 == "request-local",
          updated == 42
        )
      }
      checked.provideEnvironment(ZEnvironment("request-environment"))
    },
    test("ordinary thrown callback errors are typed failures rather than defects") {
      val error       = new IllegalStateException("synthetic callback failure")
      val callbacks   = new AtomicInteger()
      val transitions = new AtomicInteger()
      val completion = StateUpdateInterpreter.callback[Task, Int] {
        callbacks.incrementAndGet()
        throw error
      }(_ => ZIO.succeed { transitions.incrementAndGet(); () })
      val constructedLazily = callbacks.get() == 0
      completion.exit.map { exit =>
        val cause = exit.causeOption.get
        assertTrue(
          constructedLazily,
          callbacks.get() == 1,
          transitions.get() == 0,
          cause.failureOption.contains(error),
          cause.defects.isEmpty
        )
      }
    },
    test("direct, nested and helper returns widen to the expected outcome context") {
      val F                            = Effect[Task]
      val direct: Task[UiOutcome[Int]] = F.pure(StateUpdate.Modify[Int](_ + 1))
      def nested(modify: Boolean): Task[UiOutcome[Int]] =
        if modify then F.pure(StateUpdate.Modify[Int](_ + 1))
        else F.pure(StateUpdate.noUpdate[Int])
      val helper                        = F.pure(UiOutcome.modify[Int](_ + 1))
      val widened: Task[UiOutcome[Int]] = helper
      direct.zip(nested(false)).zip(widened).map {
        case (StateUpdate.Modify(first), StateUpdate.NoUpdate(), StateUpdate.Modify(last)) =>
          assertTrue(first(1) == 2, last(2) == 3)
        case _ => assertTrue(false)
      }
    }
  ) @@ TestAspect.timeout(_root_.zio.Duration.fromSeconds(5))
