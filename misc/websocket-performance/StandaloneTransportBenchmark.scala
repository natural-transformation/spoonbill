package spoonbill.performance

import java.io.DataInputStream
import java.lang.management.ManagementFactory
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.server.{HttpRequest, HttpResponse, SpoonbillService, WebSocketRequest, WebSocketResponse, standalone}

/** Identical connected echo workload for both source versions. The selected
  * WebSocketBenchmarkResponse factory is the only API-dependent source.
  * One process emits one measured block after per-connection warmup.
  */
object StandaloneTransportBenchmark {
  def main(args: Array[String]): Unit = {
    require(args.length == 7, "variant identity block connections payloadBytes warmupPerConnection measuredPerConnection")
    val Array(variant, identity, block, connectionsArg, payloadArg, warmupArg, measuredArg) = args
    val connections = connectionsArg.toInt
    val payloadSize = payloadArg.toInt
    val warmup = warmupArg.toInt
    val measured = measuredArg.toInt
    require(connections > 0 && connections <= 64 && payloadSize > 0 && payloadSize <= 65535)
    require(warmup > 0 && measured > 0)
    implicit val effect: Effect[Future] = Effect.futureEffect
    implicit val ec: ExecutionContext = ExecutionContext.global
    val service = new SpoonbillService[Future] {
      def http(request: HttpRequest[Future]): Future[HttpResponse[Future]] =
        Future.failed(new IllegalStateException("Unexpected ordinary HTTP request"))
      def ws(request: WebSocketRequest[Future]): Future[WebSocketResponse[Future]] =
        Future.successful(WebSocketBenchmarkResponse(request.httpRequest.body))
    }
    // Baseline has no localAddress accessor. Reserve an ephemeral address using
    // the same procedure for both variants; bind failure fails the block.
    val reservation = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val address = new InetSocketAddress("127.0.0.1", reservation.getLocalPort)
    reservation.close()
    val group = AsynchronousChannelGroup.withFixedThreadPool(math.max(2, connections), Executors.defaultThreadFactory())
    val workers = Executors.newFixedThreadPool(connections)
    val sockets = Array.fill(connections)(new Socket())
    val ready = new CountDownLatch(connections)
    val go = new CountDownLatch(1)
    val done = new CountDownLatch(connections)
    val samples = Array.fill(connections)(new Array[Long](measured))
    val setupSamples = new Array[Long](connections)
    val cpuBean = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val allocationBean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    if (allocationBean.isThreadAllocatedMemorySupported && !allocationBean.isThreadAllocatedMemoryEnabled)
      allocationBean.setThreadAllocatedMemoryEnabled(true)
    // Resolve once, outside the hot path. Older JVMs report null instead of
    // silently substituting allocations of only surviving/current threads.
    val totalAllocated = classOf[com.sun.management.ThreadMXBean].getMethods.find(_.getName == "getTotalThreadAllocatedBytes")
    def allocated(): Option[Long] = totalAllocated.flatMap { method =>
      val value = method.invoke(allocationBean).asInstanceOf[java.lang.Long].longValue
      Option.when(value >= 0)(value)
    }
    val server = try Await.result(standalone.buildServer[Future, Array[Byte]](service, address, group, false), 10.seconds)
    catch {
      case error: Throwable =>
        group.shutdownNow()
        workers.shutdownNow()
        throw error
    }
    try {
      val results = (0 until connections).map { index =>
        workers.submit(new Callable[Unit] {
          def call(): Unit = {
            try {
              val socket = sockets(index)
              socket.setTcpNoDelay(true)
              socket.setSoTimeout(10000)
              val setupStarted = System.nanoTime()
              socket.connect(address, 5000)
              val out = socket.getOutputStream
              val in = new DataInputStream(socket.getInputStream)
              out.write((
                "GET /echo HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
                  "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                  "Sec-WebSocket-Protocol: json\r\n\r\n"
              ).getBytes(StandardCharsets.US_ASCII))
              val header = new StringBuilder
              while (!header.toString.endsWith("\r\n\r\n") && header.length < 8192) header.append(in.readUnsignedByte().toChar)
              require(header.toString.startsWith("HTTP/1.1 101"), s"Upgrade rejected: $header")
              setupSamples(index) = System.nanoTime() - setupStarted
              val payload = Array.tabulate[Byte](payloadSize)(i => ((i + index) & 255).toByte)
              val offset = if (payloadSize < 126) 2 else 4
              val frame = new Array[Byte](offset + 4 + payloadSize)
              frame(0) = 0x82.toByte
              frame(1) = (0x80 | (if (payloadSize < 126) payloadSize else 126)).toByte
              if (payloadSize >= 126) {
                frame(2) = (payloadSize >>> 8).toByte
                frame(3) = payloadSize.toByte
              }
              // Fixed zero mask is valid; server still performs normal unmasking.
              System.arraycopy(payload, 0, frame, offset + 4, payloadSize)
              val received = new Array[Byte](payloadSize)
              def exchange(): Unit = {
                out.write(frame)
                require(in.readUnsignedByte() == 0x82, "Expected one complete binary echo")
                val marker = in.readUnsignedByte()
                require((marker & 0x80) == 0, "Unexpected masked server frame")
                val length = if (marker == 126) in.readUnsignedShort() else marker
                require(length == payloadSize, s"Incorrect echo length $length")
                in.readFully(received)
                require(Arrays.equals(payload, received), "Echo payload mismatch")
              }
              var iteration = 0
              while (iteration < warmup) { exchange(); iteration += 1 }
              ready.countDown()
              require(go.await(120, TimeUnit.SECONDS), "Measurement did not start")
              iteration = 0
              while (iteration < measured) {
                val started = System.nanoTime()
                exchange()
                samples(index)(iteration) = System.nanoTime() - started
                iteration += 1
              }
            } finally { ready.countDown(); done.countDown() }
          }
        })
      }
      require(ready.await(120, TimeUnit.SECONDS), "Warmup timed out")
      results.filter(_.isDone).foreach(_.get()) // Surface setup/warmup errors.
      val allocationsBefore = allocated()
      val cpuBefore = cpuBean.getProcessCpuTime
      val started = System.nanoTime()
      go.countDown()
      require(done.await(120, TimeUnit.SECONDS), "Measured block timed out")
      val elapsed = System.nanoTime() - started
      val cpu = cpuBean.getProcessCpuTime - cpuBefore
      val allocations = for { before <- allocationsBefore; after <- allocated() } yield after - before
      results.foreach(_.get(1, TimeUnit.SECONDS))
      val sorted = samples.flatten.sorted
      def percentile(p: Double): Long = sorted(math.min(sorted.length - 1, math.ceil(p * sorted.length).toInt - 1))
      def quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      println(s"""{"schema":"spoonbill-websocket-transport-v1","variant":${quoted(variant)},"identity":${quoted(identity)},"block":${quoted(block)},"connections":$connections,"payloadBytes":$payloadSize,"warmupPerConnection":$warmup,"messages":${sorted.length},"elapsedNs":$elapsed,"messagesPerSecond":${sorted.length.toDouble * 1e9 / elapsed},"roundTripLatencyNs":{"p50":${percentile(.50)},"p95":${percentile(.95)},"p99":${percentile(.99)}},"processCpuNs":$cpu,"jvmTotalAllocatedBytes":${allocations.fold("null")(_.toString)},"setupLatencyNs":${setupSamples.mkString("[", ",", "]")},"teardownMeasured":false,"javaVersion":${quoted(System.getProperty("java.version"))}}""")
    } finally {
      go.countDown()
      sockets.foreach(socket => try socket.close() catch { case _: Throwable => () })
      Await.result(server.stopServingRequests(), 10.seconds)
      group.shutdownNow()
      workers.shutdownNow()
      group.awaitTermination(10, TimeUnit.SECONDS)
      workers.awaitTermination(10, TimeUnit.SECONDS)
    }
  }
}
