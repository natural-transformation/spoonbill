package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Random

/**
 * The retained immutable routing model is an oracle for the compact data store.
 */
class StreamRoutingDifferentialSpec extends StreamRoutingDifferentialCases(Effect.futureEffect)

/** The same histories, served by a reader fiber as a native effect is. */
class StreamRoutingReaderFiberSpec extends StreamRoutingDifferentialCases(new ReaderFiberFutureEffect)

abstract class StreamRoutingDifferentialCases(effect: Effect[Future]) extends AnyFlatSpec with Matchers {
  import StreamRoutingMachine.*
  private given Effect[Future]              = effect
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  for (mode <- List(Mode.Fanout, Mode.Partition, Mode.Buffered)) {
    "The compact routing interpreter" should s"match the immutable model across generated $mode histories" in {
      for (seed <- 0 until 12) {
        val random   = new Random(seed)
        val capacity = 1 + seed % 4
        val physical = mutable.ArrayDeque.empty[Promise[Option[Int]]]
        val source = new Stream[Future, Int] {
          def pull(): Future[Option[Int]] = {
            val result = Promise[Option[Int]]()
            physical.append(result)
            result.future
          }
          def cancel(): Future[Unit] = Future.unit
        }
        val fixed =
          if (mode == Mode.Fanout) Vector.empty else Vector.fill(if (mode == Mode.Buffered) 1 else 2)(new Lane)
        val route    = Option.when(mode == Mode.Partition)((value: Int) => value % 2)
        val runtime  = StreamRoutingRuntime(source, mode, capacity, fixed, route)
        var model    = fixed.foldLeft(State[Int](mode, capacity))((state, lane) => add(state, lane))
        val streams  = mutable.LinkedHashMap.from(fixed.map(lane => lane -> runtime.stream(lane)))
        val actual   = mutable.Map.empty[Pull, Future[Option[Int]]]
        val expected = mutable.Map.empty[Pull, Either[Throwable, Option[Int]]]
        def applyStep(step: Step[Int]): Unit = {
          model = step.state
          step.deliveries.foreach(delivery => expected.update(delivery.pull, delivery.result))
        }
        def verify(): Unit = {
          if (canRead(model)) model = beginRead(model)
          physical.size shouldBe (if (model.reader == Reader.Reading) 1 else 0)
          actual.foreach { case (ticket, result) =>
            result.value.map(_.toEither) shouldBe expected.get(ticket)
          }
        }
        def append(): Unit = {
          val lane = runtime.newLane()
          model = add(model, lane)
          streams.update(lane, runtime.stream(lane))
        }
        if (mode == Mode.Fanout) append()
        verify()
        for (turn <- 0 until 300) {
          random.nextInt(6) match {
            case 0 if mode == Mode.Fanout && model.phase == Phase.Open && streams.size < 8 => append()
            case 1 if streams.nonEmpty =>
              val lane   = streams.keysIterator.toVector(random.nextInt(streams.size))
              val ticket = new Pull
              applyStep(StreamRoutingMachine.pull(model, lane, ticket))
              actual.update(ticket, streams(lane).pull())
            case 2 if streams.nonEmpty && (mode == Mode.Fanout || turn > 220) =>
              val lane = streams.keysIterator.toVector(random.nextInt(streams.size))
              applyStep(StreamRoutingMachine.cancel(model, Some(lane)))
              await(streams(lane).cancel())
            case _ if physical.nonEmpty =>
              val pending = physical.removeHead()
              if (turn > 250 && random.nextBoolean()) {
                applyStep(ended(model))
                pending.success(None)
              } else {
                val waitingRacks = fixed.indices.filter(index => model.lanes.get(fixed(index)).exists(_.pulls.nonEmpty))
                val value =
                  if (mode == Mode.Partition && seed % 3 != 0 && waitingRacks.nonEmpty)
                    random.nextInt(10) * 2 + waitingRacks(random.nextInt(waitingRacks.size))
                  else random.nextInt(20)
                val target = route.map(f => fixed(f(value)))
                if (model.phase == Phase.Open && target.exists(overflow(model, _)))
                  applyStep(failed(model, StreamSortOverflowException(value % 2, capacity)))
                else applyStep(received(model, value, target))
                pending.success(Some(value))
              }
            case _ => ()
          }
          verify()
        }
        effect match {
          case _: Effect.FutureEffect => runtime shouldBe a[StreamRoutingRuntime.CallbackPump[?]]
          case _                      => runtime shouldBe a[StreamRoutingRuntime.ReaderFiber[?, ?]]
        }
        applyStep(StreamRoutingMachine.cancel(model, None))
        await(runtime.cancel())
        if (physical.nonEmpty) {
          applyStep(ended(model))
          physical.removeHead().success(None)
        }
        verify()
      }
    }
  }

  it should "keep ready Future completions stack bounded under reentrant demand" in {
    val first = Promise[Option[Int]]()
    val source = new Stream[Future, Int] {
      var value = 0
      def pull(): Future[Option[Int]] = {
        value += 1
        if (value == 1) first.future else Future.successful(if (value <= 100000) Some(value) else None)
      }
      def cancel(): Future[Unit] = Future.unit
    }
    val stream = Hub(source, 1).newStreamUnsafe()
    import spoonbill.effect.syntax.*
    var received = 0
    // Attach before publication so subsequent pulls reenter the owned drainer.
    // Fully synchronous foreach recursion is a separate pre-existing helper issue.
    val consumed = stream.foreach(value => Effect[Future].delay { received += 1; value shouldBe received; () })
    first.success(Some(1))
    await(consumed)
    received shouldBe 100000
  }

  for (inRoute <- List(false, true)) {
    it should s"preserve Future failure normalization for thrown interruption (route: $inRoute)" in {
      val error = new InterruptedException("synthetic synchronous interruption")
      val source = new Stream[Future, Int] {
        def pull(): Future[Option[Int]] = if (inRoute) Future.successful(Some(0)) else throw error
        def cancel(): Future[Unit]      = Future.unit
      }
      val racks = source.sort(2)(value => if (inRoute) throw error else value)
      val first = await(racks.head.pull().failed)
      first shouldBe a[java.util.concurrent.ExecutionException]
      first.getCause should be theSameInstanceAs error
      await(racks(1).pull().failed) should be theSameInstanceAs first
    }
  }
}
