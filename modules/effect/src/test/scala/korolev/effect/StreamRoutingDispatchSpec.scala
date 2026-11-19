package spoonbill.effect

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.effect.StreamRoutingDispatchProbes.*

/**
 * Notification dispatch has one owner at a time, and that owner releases it
 * without taking the ownership monitor. Work that arrives meanwhile must
 * neither overlap the running notification nor be left behind, and a cleanup
 * pause must hold it back. Every case runs with the callback pump that serves
 * built-in Future instances and with the reader fiber that serves every other
 * effect.
 */
class StreamRoutingDispatchSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)
  private val idle                          = () => ()
  private def effect(fiber: Boolean, before: () => Unit = idle, after: () => Unit = idle): Effect[Future] =
    if (fiber) new ReaderFiberFutureEffect {
      override def start[A](task: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] = {
        before(); val started = super.start(task); after(); started
      }
    }
    else Effect.future(Effect.FutureInstrumentation(beforeStart = before, afterStart = _ => after()))

  /** A notification whose continuation holds dispatch on its own thread. */
  private final class Held(pull: Future[Option[Int]]) {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val result = pull.map { value =>
      entered.countDown()
      require(release.await(10, TimeUnit.SECONDS), "Held notification was not released")
      value
    }(ExecutionContext.parasitic)
  }
  private def onThread(body: => Unit): Thread = {
    val thread = new Thread(() => body)
    thread.setDaemon(true)
    thread.start()
    thread
  }

  /** Spins for a tight handoff, but yields a shared core. */
  private def spinUntil(done: => Boolean): Unit = {
    var spins = 0
    while (!done) { spins += 1; if (spins > 20000) Thread.`yield`() }
  }

  for (fiber <- List(false, true); reader = if (fiber) "a reader fiber" else "the callback pump") {
    s"A running notification with $reader" should "be followed by the read another thread reserved meanwhile" in {
      given Effect[Future] = effect(fiber)
      val source           = new Gated
      val racks            = source.sort(2)(_ % 2)
      val held             = new Held(racks(0).pull())
      val owner            = onThread(source.publish(Some(0)))
      held.entered.await(10, TimeUnit.SECONDS) shouldBe true
      // The first read is settled. The second is reserved here, but its start
      // belongs to the thread that is still inside the held notification.
      val second = racks(1).pull()
      source.entered.get() shouldBe 1
      second.isCompleted shouldBe false
      held.release.countDown()
      source.publish(Some(1))
      await(held.result) shouldBe Some(0)
      await(second) shouldBe Some(1)
      source.entered.get() shouldBe 2
      owner.join(10000)
    }

    it should "precede a notification that queued data makes ready on another thread" in {
      given Effect[Future] = effect(fiber)
      val source           = new Gated
      val racks            = source.sort(2)(_ % 2)
      val first            = racks(0).pull()
      // An odd value is stored for the rack nobody waits on; reading continues.
      source.publish(Some(1))
      val held  = new Held(first)
      val owner = onThread(source.publish(Some(0)))
      held.entered.await(10, TimeUnit.SECONDS) shouldBe true
      val ready = racks(1).pull()
      ready.isCompleted shouldBe false
      held.release.countDown()
      await(ready) shouldBe Some(1)
      await(held.result) shouldBe Some(0)
      owner.join(10000)
    }

    it should "be followed first by a queued notification and then by the read reserved after it" in {
      given Effect[Future] = effect(fiber)
      val source           = new Gated
      val hub              = Hub(source, 1)
      val fast             = hub.newStreamUnsafe()
      val slow             = hub.newStreamUnsafe()
      val held             = new Held(fast.pull())
      val owner            = onThread(source.publish(Some(0)))
      held.entered.await(10, TimeUnit.SECONDS) shouldBe true
      // The slow subscriber's full buffer withholds read credit from this pull.
      val next = fast.pull()
      // Taking that value settles this pull and, after it, grants the read.
      val readsAtNotification = slow.pull().map(value => (value, source.entered.get()))(ExecutionContext.parasitic)
      readsAtNotification.isCompleted shouldBe false
      held.release.countDown()
      await(readsAtNotification) shouldBe (Some(0), 1)
      source.publish(Some(1))
      await(next) shouldBe Some(1)
      await(held.result) shouldBe Some(0)
      owner.join(10000)
    }

    it should "start the read a value leaves credit for only after that value's notifications" in {
      given Effect[Future] = effect(fiber)
      val source           = new Gated
      val hub              = Hub(source, 4)
      val first            = hub.newStreamUnsafe()
      val second           = hub.newStreamUnsafe()
      val readsAtNotification =
        first.pull().map(value => (value, source.entered.get()))(ExecutionContext.parasitic)
      // A second pull of the same subscriber leaves demand behind the first value.
      val later = first.pull()
      val other = second.pull()
      source.publish(Some(0))
      await(readsAtNotification) shouldBe (Some(0), 1)
      await(other) shouldBe Some(0)
      source.publish(Some(1))
      await(later) shouldBe Some(1)
    }

    it should "release dispatch so that a later thread settles its own pull" in {
      given Effect[Future] = effect(fiber)
      val source           = new Gated
      val racks            = source.sort(2)(_ % 2)
      val first            = racks(0).pull()
      source.publish(Some(1))
      onThread(source.publish(Some(0))).join(10000)
      await(first) shouldBe Some(0)
      // No owner remains: this thread claims dispatch for its ready value.
      val ready = racks(1).pull()
      ready.isCompleted shouldBe true
      await(ready) shouldBe Some(1)
    }

    it should "leave notifications to the cleanup pause that began while it ran" in {
      val ownerLeft         = new CountDownLatch(1)
      val holding           = new AtomicReference[Held]()
      @volatile var started = false
      given Effect[Future] = effect(
        fiber,
        before = () => {
          // Cleanup startup is inside the pause. Let the running owner finish
          // and leave dispatch before the cleanup child exists.
          holding.get().release.countDown()
          require(ownerLeft.await(10, TimeUnit.SECONDS), "Dispatch owner did not leave")
        },
        after = () => started = true
      )
      val source = new Gated
      val racks  = source.sort(2)(_ % 2)
      holding.set(new Held(racks(0).pull()))
      val owner = onThread { source.publish(Some(0)); ownerLeft.countDown() }
      holding.get().entered.await(10, TimeUnit.SECONDS) shouldBe true
      val startedAtNotification = racks(1).pull().map(value => (value, started))(ExecutionContext.parasitic)
      await(racks(0).cancel())
      await(startedAtNotification) shouldBe (None, true)
      await(holding.get().result) shouldBe Some(0)
      owner.join(10000)
      source.entered.get() shouldBe 1
    }

    it should "not strand a command that arrives as its owner releases" in {
      given Effect[Future]   = effect(fiber)
      val source             = new Gated
      val racks              = source.sort(2, Int.MaxValue)(_ % 2)
      val rounds             = 30000
      @volatile var go       = -1
      @volatile var finished = -1
      val owner = onThread {
        var round = 0
        while (round < rounds) {
          spinUntil(go == round)
          // Settles the even pull as this thread's own command and releases.
          source.publish(Some(2 * round))
          finished = round
          round += 1
        }
      }
      for (round <- 0 until rounds) {
        val even  = racks(0).pull()
        val ready = round % 2 == 0
        if (ready) source.publish(Some(2 * round + 1))
        go = round
        // Sweep this pull across the moment of the owner's release.
        var delay = (round % 97) * (1 + round % 7)
        while (delay > 0) { delay -= 1; if (finished == round) delay = 0 }
        // Either this thread claims dispatch for its command, or the command
        // is left to the owner that is releasing at this moment: a ready
        // notification from the queue, or else the read this pull reserves.
        val odd = racks(1).pull()
        if (!ready) {
          await(even) shouldBe Some(2 * round)
          source.publish(Some(2 * round + 1))
        }
        await(odd) shouldBe Some(2 * round + 1)
        await(even) shouldBe Some(2 * round)
        spinUntil(finished == round)
      }
      owner.join(10000)
    }

    it should "settle every pull when a cleanup pause races its release" in {
      given Effect[Future]   = effect(fiber)
      val rounds             = 3000
      val current            = new AtomicReference[Gated]()
      @volatile var go       = -1
      @volatile var finished = -1
      val owner = onThread {
        var round = 0
        while (round < rounds) {
          spinUntil(go == round)
          current.get().publish(Some(0))
          finished = round
          round += 1
        }
      }
      for (round <- 0 until rounds) {
        val source = new Gated
        val racks  = source.sort(2)(_ % 2)
        val even   = racks(0).pull()
        current.set(source)
        go = round
        var delay = (round % 97) * (1 + round % 7)
        while (delay > 0) { delay -= 1; if (finished == round) delay = 0 }
        val odd = racks(1).pull()
        await(racks(1).cancel())
        await(odd) shouldBe None
        await(even) should (be(Some(0)) or be(None))
        spinUntil(finished == round)
        await(source.canceled.future)
        source.cancellations.get() shouldBe 1
        // The released owner may have started the read the odd pull reserved.
        source.entered.get() should be <= 2
      }
      owner.join(10000)
    }

    for (kind <- List("sort", "hub", "shared hub", "buffer", "delayed buffer")) {
      s"Concurrent $kind consumers with $reader" should "each receive every value once and in order" in {
        // A reader fiber may read or continue a pull after its command, outside dispatch: `probed` counts the commands.
        val exclusion           = new Exclusion
        val attached            = if (fiber) new Exclusion else exclusion
        val total               = if (kind == "delayed buffer") 3000 else 20000
        val pool                = Executors.newFixedThreadPool(4)
        given Effect[Future]    = if (fiber && kind != "delayed buffer") probed(exclusion) else effect(fiber)
        given ExecutionContext  = ExecutionContext.fromExecutor(pool)
        given Reporter          = Reporter.PrintReporter
        given Scheduler[Future] = Scheduler.schedulerF[Future]
        val source              = new Gated(attached)
        def blocking[B](stream: Stream[Future, B], probe: Boolean)(add: B => Unit): Future[Unit] = Future {
          var more = true
          while (more) await(if (probe) attached.notified(stream.pull())(_.get) else stream.pull()) match {
            case Some(value) => add(value)
            case None        => more = false
          }
        }
        // Pulls again from inside its own notification; ready pulls are looped
        // so that the consumer adds no recursion of its own.
        def chained(stream: Stream[Future, Int])(add: Int => Unit): Future[Unit] = {
          val done = Promise[Unit]()
          def step(): Unit = {
            var again = true
            while (again) {
              val pull = stream.pull()
              pull.value match {
                case Some(Success(Some(value))) => add(value)
                case Some(Success(None))        => done.success(()); again = false
                case Some(Failure(error))       => done.failure(error); again = false
                case None =>
                  again = false
                  attached.notified(pull) {
                    case Success(Some(value)) => add(value); step()
                    case Success(None)        => done.success(())
                    case Failure(error)       => done.failure(error)
                  }
              }
            }
          }
          step()
          done.future
        }
        final class Sink {
          @volatile var values      = Vector.empty[Int]
          def add(value: Int): Unit = values :+= value
        }
        try {
          val sinks = Vector.fill(if (kind.endsWith("buffer")) 1 else if (kind == "shared hub") 2 else 3)(new Sink)
          val consumed: Vector[Future[Unit]] = kind match {
            case "sort" =>
              val racks = source.sort(3, Int.MaxValue)(_ % 3)
              Vector(
                blocking(racks(0), probe = true)(sinks(0).add),
                chained(racks(1))(sinks(1).add),
                blocking(racks(2), probe = true)(sinks(2).add)
              )
            case "hub" =>
              val hub     = Hub(source, 4)
              val streams = Vector.fill(3)(hub.newStreamUnsafe())
              Vector(
                blocking(streams(0), probe = true)(sinks(0).add),
                chained(streams(1))(sinks(1).add),
                blocking(streams(2), probe = true)(sinks(2).add)
              )
            case "shared hub" =>
              // Two threads keep overlapping pulls waiting in one lane.
              val shared = Hub(source, 4).newStreamUnsafe()
              Vector(blocking(shared, probe = true)(sinks(0).add), blocking(shared, probe = true)(sinks(1).add))
            case "buffer" =>
              Vector(blocking(source.buffer(Duration.Zero, 8), probe = true)(_.foreach(sinks(0).add)))
            case _ =>
              // Timers reserve each batch's suffix while reads and pulls go on.
              // Their batches are settled by the buffer, outside dispatch, through the same promise.
              Vector(blocking(source.buffer(1.millisecond, 8), probe = false)(_.foreach(sinks(0).add)))
          }
          val publisher = Future {
            for (value <- 0 until total) source.publish(Some(value))
            source.publish(None)
          }
          await(publisher)
          consumed.foreach(await)
          kind match {
            case "sort" =>
              for (rack <- 0 until 3) sinks(rack).values shouldBe (0 until total).filter(_ % 3 == rack).toVector
            case "shared hub" =>
              sinks.foreach(sink => sink.values shouldBe sink.values.sorted)
              sinks.flatMap(_.values).sorted shouldBe (0 until total).toVector
            case _ => sinks.foreach(_.values shouldBe (0 until total).toVector)
          }
          // A delayed buffer settles its batches outside dispatch, so no probe counts its notifications.
          // With a reader fiber nothing enters `exclusion` at all. With the callback pump only the reads do,
          // and the source refuses concurrent reads by itself.
          if (fiber && kind == "delayed buffer") exclusion.used shouldBe false
          else {
            exclusion.used shouldBe true
            exclusion.overlapped shouldBe false
          }
        } finally pool.shutdownNow()
      }
    }
  }

  // What a probe of the reads reports as an overlap, step by step. `attached` is such a probe.
  "A reader fiber's read" should "run outside dispatch after its own thread took its turn" in {
    val exclusion        = new Exclusion
    val attached         = new Exclusion
    given Effect[Future] = probed(exclusion)
    val reading          = new CountDownLatch(1)
    val proceed          = new CountDownLatch(1)
    // Holds the second read while it enters upstream.
    val source = new Gated(
      attached,
      read =>
        if (read == 2) {
          reading.countDown()
          require(proceed.await(10, TimeUnit.SECONDS), "The held read was not released")
        }
    )
    val hub     = Hub(source, 4)
    val first   = hub.newStreamUnsafe()
    val settled = attached.notified(first.pull())(_.get)
    // A second pull of the same subscriber leaves demand behind the first value.
    val later = first.pull()
    // The new thread settles the first pull, takes the reader's turn itself and
    // releases dispatch. Only then does the reader read upstream, and is held.
    val reader = onThread(source.publish(Some(0)))
    reading.await(10, TimeUnit.SECONDS) shouldBe true
    await(settled) shouldBe Some(0)
    // Dispatch is free while that read is still entering upstream: this
    // thread owns it for the subscriber that it detaches.
    val second  = hub.newStreamUnsafe()
    val waiting = attached.notified(second.pull())(_.get)
    waiting.isCompleted shouldBe false
    await(second.cancel())
    withClue("The read is a command now: the notification waited for it.")(waiting.isCompleted shouldBe true)
    await(waiting) shouldBe None
    proceed.countDown()
    reader.join(10000)
    source.publish(Some(1))
    await(later) shouldBe Some(1)
    // The read met a notification, and no command met another: only the commands may decide.
    attached.overlapped shouldBe true
    exclusion.used shouldBe true
    exclusion.overlapped shouldBe false
  }
}
