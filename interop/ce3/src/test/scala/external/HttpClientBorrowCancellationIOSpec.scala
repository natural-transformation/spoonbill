package external

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.{implicits, IORuntime}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.LockSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.data.Bytes
import spoonbill.effect.{AsyncResourcePool, Effect, Stream}
import spoonbill.effect.Reporter.PrintReporter.Implicit
import spoonbill.http.HttpClient
import spoonbill.web.{PathAndQuery, Request}

class HttpClientBorrowCancellationIOSpec extends AnyFlatSpec with Matchers {
  private class Worker extends ExecutionContext {
    private val tasks = new ConcurrentLinkedQueue[Runnable]()
    def execute(task: Runnable): Unit = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def drain(): Unit = {
      var next = tasks.poll()
      var count = 0
      while (next != null) {
        next.run()
        count += 1
        assert(count < 10000)
        next = tasks.poll()
      }
    }
  }

  for (assignedBeforeCancel <- List(false, true)) {
  "HttpClient IO acquisition" should s"return capacity after cancellation (loan already assigned=$assignedBeforeCancel)" in {
    val worker = new Worker
    val global = implicits.global
    val runtime = IORuntime(worker, ExecutionContext.global, global.scheduler, () => (), global.config)
    val offers = new AtomicInteger()
    val heldOffer = new AtomicReference[Runnable]()
    given Effect[IO] = new IO3Effect(runtime) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise { callback =>
          register { outcome => outcome match {
            case Right(_: AsyncResourcePool.Borrow[IO, ?] @unchecked)
                if assignedBeforeCancel && offers.incrementAndGet() == 2 =>
              heldOffer.set(() => callback(outcome))
            case _ => callback(outcome)
          } }
        }
    }
    given ExecutionContext = worker
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    listener.setSoTimeout(5000)
    val peers = new ConcurrentLinkedQueue[Socket]()
    val releaseBody = new CountDownLatch(1)
    val requests = new AtomicInteger()

    def completed[A](future: Future[A], limit: FiniteDuration = 5.seconds): Boolean = {
      val until = System.nanoTime() + limit.toNanos
      while (!future.isCompleted && System.nanoTime() < until) {
        worker.drain()
        LockSupport.parkNanos(100000L)
      }
      worker.drain()
      future.isCompleted
    }
    def run[A](io: IO[A]): A = {
      val value = io.unsafeToFuture()(runtime)
      assert(completed(value), "Controlled IO did not complete")
      Await.result(value, 1.second)
    }
    def readHead(peer: Socket): Boolean = {
      val text = new StringBuilder
      while (!text.toString.endsWith("\r\n\r\n")) {
        val next = peer.getInputStream.read()
        if (next < 0) return false
        text.append(next.toChar)
      }
      requests.incrementAndGet()
      true
    }
    val serving = new Thread(() => {
      try {
        val peer = listener.accept()
        peers.add(peer)
        peer.setSoTimeout(5000)
        assert(readHead(peer))
        peer.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n".getBytes(US_ASCII))
        peer.getOutputStream.flush()
        assert(releaseBody.await(5, TimeUnit.SECONDS))
        peer.getOutputStream.write('x')
        peer.getOutputStream.flush()
        val next = if (readHead(peer)) peer else {
          val replacement = listener.accept()
          peers.add(replacement)
          replacement.setSoTimeout(5000)
          assert(readHead(replacement))
          replacement
        }
        next.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII))
        next.getOutputStream.flush()
      } catch { case _: java.io.IOException => () }
    })
    serving.setDaemon(true)
    serving.start()
    try {
      val client = run(HttpClient.create[IO, Bytes](
        name = "canceled-acquisition", group = group, maxConnectionsPerAddress = 1,
        maxTotalConnections = 1, maxIdleTime = 1.hour, poolCleanupInterval = 1.hour))
      val address = new InetSocketAddress(InetAddress.getLoopbackAddress, listener.getLocalPort)
      def request = client.http(address,
        Request(Request.Method.Get, PathAndQuery.Root, Nil, Some(0L), Stream.empty[IO, Bytes]))
      val first = run(request)
      val queued = run(request.start)
      worker.drain() // The second fiber has no runnable work: it is waiting for the occupied pool.
      def finishFirst(): Unit = {
        releaseBody.countDown()
        run(first.body.fold(Bytes.empty)(_ ++ _)).as[Array[Byte]].toVector shouldBe Vector('x'.toByte)
      }
      if (assignedBeforeCancel) {
        finishFirst()
        worker.drain()
        withClue("Expected the second loan before native callback publication: ") {
          heldOffer.get() should not be null
        }
      }
      val canceled = queued.cancel.unsafeToFuture()(runtime)
      completed(canceled, 1.second) shouldBe true
      Await.result(canceled, 1.second)
      requests.get() shouldBe 1
      if (!assignedBeforeCancel) finishFirst()
      else { heldOffer.get().run(); worker.drain() }
      val third = request.unsafeToFuture()(runtime)
      withClue("A canceled queued acquisition consumed the returned connection: ") {
        completed(third, 2.seconds) shouldBe true
      }
      val response = Await.result(third, 1.second)
      run(response.body.fold(Bytes.empty)(_ ++ _)).length shouldBe 0L
      requests.get() shouldBe 2
    } finally {
      releaseBody.countDown()
      listener.close()
      val iterator = peers.iterator()
      while (iterator.hasNext) iterator.next().close()
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
      serving.join(1000)
      worker.drain()
    }
  }
  }

  it should "abort an active header wait without joining blocked request cleanup" in {
    val worker = new Worker
    val global = implicits.global
    val runtime = IORuntime(worker, ExecutionContext.global, global.scheduler, () => (), global.config)
    given Effect[IO] = new IO3Effect(runtime)
    given ExecutionContext = worker
    val group = AsynchronousChannelGroup.withFixedThreadPool(2, Executors.defaultThreadFactory())
    val listener = new ServerSocket(0, 2, InetAddress.getLoopbackAddress)
    listener.setSoTimeout(5000)
    val peers = new ConcurrentLinkedQueue[Socket]()
    val received = new CountDownLatch(1)
    val closed = new CountDownLatch(1)
    val cleanupStarted = new CountDownLatch(1)
    val cleanup = Promise[Unit]()
    def until(ready: => Boolean, limit: FiniteDuration = 5.seconds): Boolean = {
      val end = System.nanoTime() + limit.toNanos
      while (!ready && System.nanoTime() < end) { worker.drain(); LockSupport.parkNanos(100000L) }
      worker.drain()
      ready
    }
    def run[A](io: IO[A]): A = {
      val result = io.unsafeToFuture()(runtime)
      assert(until(result.isCompleted))
      Await.result(result, 1.second)
    }
    def accept(): Socket = {
      val peer = listener.accept()
      peers.add(peer)
      peer.setSoTimeout(5000)
      val head = new StringBuilder
      while (!head.toString.endsWith("\r\n\r\n")) {
        val next = peer.getInputStream.read()
        if (next < 0) throw new java.io.EOFException("No request headers")
        head.append(next.toChar)
      }
      peer
    }
    val serving = new Thread(() => {
      try {
        val first = accept()
        received.countDown()
        if (first.getInputStream.read() == -1) closed.countDown()
        val next = accept()
        next.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII))
        next.getOutputStream.flush()
      } catch { case _: java.io.IOException => () }
    })
    serving.setDaemon(true)
    serving.start()
    try {
      val client = run(HttpClient.create[IO, Bytes](
        name = "active-cancellation", group = group, maxConnectionsPerAddress = 1,
        maxTotalConnections = 1, maxIdleTime = 1.hour, poolCleanupInterval = 1.hour))
      val address = new InetSocketAddress(InetAddress.getLoopbackAddress, listener.getLocalPort)
      val upload = new Stream[IO, Bytes] {
        def pull(): IO[Option[Bytes]] = IO.pure(None)
        def cancel(): IO[Unit] = IO { cleanupStarted.countDown(); () }
          .flatMap(_ => IO.fromFuture(IO.pure(cleanup.future)))
      }
      val first = run(client.http(address,
        Request(Request.Method.Get, PathAndQuery.Root, Nil, Some(0L), upload)).start)
      until(received.getCount == 0) shouldBe true
      val canceled = first.cancel.unsafeToFuture()(runtime)
      until(canceled.isCompleted, 1.second) shouldBe true
      Await.result(canceled, 1.second)
      until(closed.getCount == 0 && cleanupStarted.getCount == 0) shouldBe true
      cleanup.isCompleted shouldBe false
      val next = client.http(address,
        Request(Request.Method.Get, PathAndQuery.Root, Nil, Some(0L), Stream.empty[IO, Bytes]))
        .unsafeToFuture()(runtime)
      worker.drain()
      next.isCompleted shouldBe false // Cleanup still owns the single reservation.
      cleanup.success(())
      until(next.isCompleted) shouldBe true
      run(Await.result(next, 1.second).body.fold(Bytes.empty)(_ ++ _)).length shouldBe 0L
    } finally {
      cleanup.trySuccess(())
      listener.close()
      val iterator = peers.iterator()
      while (iterator.hasNext) iterator.next().close()
      group.shutdownNow()
      group.awaitTermination(3, TimeUnit.SECONDS)
      serving.join(1000)
      worker.drain()
    }
  }
}
