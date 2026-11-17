package spoonbill.action

import _root_.cats.effect.{Deferred, IO, IOLocal}
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Hub, Reporter, Scheduler, Stream}
import spoonbill.effect.StreamSortOverflowException

class StreamOperatorCancellationIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO]            = new IO3Effect(global)
  private def run[A](value: IO[A]): A = Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  for (kind <- List("hub", "sort", "buffer")) {
    "A self-canceling shared source" should s"settle its $kind consumers with a terminal error" in {
      given ExecutionContext = global.compute
      given Reporter         = Reporter.PrintReporter
      given Scheduler[IO]    = Scheduler.schedulerF[IO]
      val source = new Stream[IO, Int] {
        def pull(): IO[Option[Int]] = IO.canceled.flatMap(_ => IO.never)
        def cancel(): IO[Unit]      = IO.unit
      }
      val read: IO[Unit] = kind match {
        case "hub" =>
          val hub    = Hub(source)
          val first  = hub.newStreamUnsafe()
          val second = hub.newStreamUnsafe()
          first.pull().attempt.flatMap { result =>
            IO(result.left.toOption.get shouldBe a[java.util.concurrent.CancellationException])
              .flatMap(_ => second.pull().attempt)
              .flatMap(other => IO(other.left.toOption.get should be theSameInstanceAs result.left.toOption.get))
          }
        case "sort" =>
          val racks = source.sort(2)(identity)
          racks.head
            .pull()
            .attempt
            .flatMap(result => IO(result.left.toOption.get shouldBe a[java.util.concurrent.CancellationException]))
        case _ =>
          source
            .buffer(Duration.Zero)
            .pull()
            .attempt
            .flatMap(result => IO(result.left.toOption.get shouldBe a[java.util.concurrent.CancellationException]))
      }
      run(read)
    }
  }

  "A canceled Hub subscriber" should "detach while its independently owned read still serves another subscriber" in {
    val entered       = run(Deferred[IO, Unit])
    val value         = run(Deferred[IO, Option[Int]])
    val reads         = new AtomicInteger()
    val cancellations = new AtomicInteger()
    val source = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] =
        IO(reads.incrementAndGet()).flatMap(_ => entered.complete(()).flatMap(_ => value.get))
      def cancel(): IO[Unit] = IO(cancellations.incrementAndGet()).void
    }
    val hub    = Hub(source, 1)
    val first  = hub.newStreamUnsafe()
    val second = hub.newStreamUnsafe()
    run(for {
      canceled  <- first.pull().start
      _         <- entered.get
      _         <- canceled.cancel
      following <- second.pull().start
      _         <- value.complete(Some(7))
      result    <- following.joinWithNever
      _         <- IO(result shouldBe Some(7))
      detached  <- first.pull()
      _         <- IO(detached shouldBe None)
      _         <- second.cancel()
    } yield ())
    reads.get() shouldBe 1
    cancellations.get() shouldBe 0
  }

  "The shared reader" should "retain its own fiber context across reads rather than borrowing subscriber context" in {
    val local    = run(IOLocal("reader-initial"))
    val observed = new ConcurrentLinkedQueue[String]()
    val reads    = new AtomicInteger()
    val source = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] = local.get.flatMap { value =>
        IO { observed.add(value); reads.incrementAndGet() }.flatMap(n => local.set("reader-next").as(Some(n)))
      }
      def cancel(): IO[Unit] = IO.unit
    }
    val stream = Hub(source).newStreamUnsafe()
    run(local.set("subscriber-one").flatMap(_ => stream.pull())) shouldBe Some(1)
    run(local.set("subscriber-two").flatMap(_ => stream.pull())) shouldBe Some(2)
    observed.asScala.toVector shouldBe Vector("reader-initial", "reader-next")
    run(stream.cancel())
  }

  "Native partition cancellation" should "settle all racks and start cleanup in caller context without joining it" in {
    val local           = run(IOLocal("initial"))
    val readEntered     = run(Deferred[IO, Unit])
    val pendingRead     = run(Deferred[IO, Option[Int]])
    val cleanupEntered  = run(Deferred[IO, String])
    val cleanupFinished = run(Deferred[IO, Unit])
    val reads           = new AtomicInteger()
    val source = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] =
        IO(reads.incrementAndGet()).flatMap(_ => readEntered.complete(()).flatMap(_ => pendingRead.get))
      def cancel(): IO[Unit] = local.get
        .flatMap(cleanupEntered.complete)
        .flatMap(_ => cleanupFinished.get)
        .flatMap(_ => pendingRead.complete(None))
        .void
    }
    val racks = source.sort(2)(_ % 2)
    try
      run(for {
        first     <- local.set("cancel-owner").flatMap(_ => racks(0).pull()).start
        _         <- readEntered.get
        _         <- first.cancel
        context   <- cleanupEntered.get
        _         <- IO(context shouldBe "cancel-owner")
        other     <- racks(1).pull()
        _         <- IO(other shouldBe None)
        _         <- IO(reads.get() shouldBe 1)
        stillHeld <- cleanupFinished.tryGet
        _         <- IO(stillHeld shouldBe None)
      } yield ())
    finally run(cleanupFinished.complete(()).void)
  }

  "Overflow cleanup" should "inherit the persistent reader context and leave all racks failed" in {
    val local    = run(IOLocal("initial"))
    val entered  = run(Deferred[IO, String])
    val finished = run(Deferred[IO, Unit])
    val source = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] = local.set("reader-cleanup").as(Some(1))
      def cancel(): IO[Unit]      = local.get.flatMap(entered.complete).flatMap(_ => finished.get)
    }
    val racks = source.sort(2, 1)(identity)
    try
      run(for {
        result <- local.set("subscriber").flatMap(_ => racks(0).pull()).attempt
        _      <- IO(result.left.toOption.get shouldBe StreamSortOverflowException(1, 1))
        owner  <- entered.get
        _      <- IO(owner shouldBe "reader-cleanup")
        _      <- racks(0).cancel()
        other  <- racks(1).pull().attempt
        _      <- IO(other.left.toOption.get should be theSameInstanceAs result.left.toOption.get)
        held   <- finished.tryGet
        _      <- IO(held shouldBe None)
      } yield ())
    finally run(finished.complete(()).void)
  }

  "Buffered cancellation" should "settle delayed batches and start upstream cleanup without joining finalizers" in {
    val firstValuesPublished = Promise[Unit]()
    val firstCallbacks       = new AtomicInteger()
    given Effect[IO] = new IO3Effect(global) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register { result =>
            callback(result)
            result match {
              case Right(Some(_: Int)) if firstCallbacks.incrementAndGet() == 1 =>
                firstValuesPublished.trySuccess(()); ()
              case _ => ()
            }
          }
        }
    }
    given ExecutionContext = global.compute
    given Reporter         = Reporter.PrintReporter
    given Scheduler[IO]    = Scheduler.schedulerF[IO]
    val cleanupEntered     = run(Deferred[IO, Unit])
    val cleanupFinished    = run(Deferred[IO, Unit])
    val sourceEnded        = run(Deferred[IO, Option[Int]])
    val reads              = new AtomicInteger()
    val source = new Stream[IO, Int] {
      def pull(): IO[Option[Int]] = IO(reads.incrementAndGet()).flatMap {
        case n if n <= 2 => IO.pure(Some(n))
        case _           => sourceEnded.get
      }
      def cancel(): IO[Unit] = cleanupEntered
        .complete(())
        .flatMap(_ => cleanupFinished.get)
        .flatMap(_ => sourceEnded.complete(None))
        .void
    }
    val buffered = source.buffer(1.day, 2)
    val first    = run(buffered.pull().start)
    val second   = run(buffered.pull().start)
    try {
      Await.result(firstValuesPublished.future, 5.seconds)
      run(first.cancel)
      run(cleanupEntered.get)
      run(second.joinWithNever) shouldBe None
      run(cleanupFinished.tryGet) shouldBe None
      run(buffered.pull()) shouldBe None
    } finally {
      run(cleanupFinished.complete(()).void)
      run(buffered.cancel())
    }
  }
}
