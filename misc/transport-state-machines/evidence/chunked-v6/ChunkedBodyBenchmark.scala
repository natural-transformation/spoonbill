package spoonbill.http.protocol

import java.lang.management.ManagementFactory
import java.util.Arrays
import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Effect, Stream}

/** Public streaming decoder workload; consumes payload slices without joining them. */
object ChunkedBodyBenchmark {
  private given Effect[Future] = Effect.futureEffect
  @volatile private var sink = 0L

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    val (payload, wire, fragment) = scenario match {
      case "small" => ("x", "1\r\nx\r\n0\r\n\r\n", 4096)
      case "large" => ("x" * 8192, "2000\r\n" + "x" * 8192 + "\r\n0\r\n\r\n", 16384)
      case "fragmented" => ("x" * 64, "40\r\n" + "x" * 64 + "\r\n0\r\n\r\n", 1)
      case "trailers" => ("x" * 16, "10;name=\"escaped\\\"value\"\r\n" + "x" * 16 + "\r\n0\r\nX-Check: yes\r\nX-Other: value\r\n\r\n", 16)
    }
    val chunks = wire.grouped(fragment).map(value => Bytes.wrap(value.getBytes("ISO-8859-1"))).toVector
    val parser = new Http11[Bytes]
    def done[A](value: Future[A]): A = value.value.getOrElse(sys.error("Unexpected asynchronous work")).get
    def decodeBody(): Long = {
      val input = done(Stream.emits(chunks).mat[Future]())
      val body = parser.decodeChunkedBody(Decoder(input))
      var total = 0L
      var chunk = done(body.pull())
      while (chunk.nonEmpty) {
        val value = chunk.get
        require(value.length > 0 && value(0) == 'x'.toByte && value(value.length - 1) == 'x'.toByte)
        total += value.length
        chunk = done(body.pull())
      }
      total
    }
    require(decodeBody() == payload.length)
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId
    val warmUntil = System.nanoTime() + seconds.toLong * 1000000000L
    while (System.nanoTime() < warmUntil) sink = decodeBody()
    val samples = new Array[Long](1 << 20)
    var sampled = 0
    var count = 0L
    val allocatedBefore = allocation.getThreadAllocatedBytes(thread)
    val begun = System.nanoTime()
    val until = begun + seconds.toLong * 1000000000L
    while (System.nanoTime() < until) {
      var batch = 0
      while (batch < 64) {
        if (batch == 0) {
          val before = System.nanoTime()
          sink = decodeBody()
          require(sampled < samples.length)
          samples(sampled) = System.nanoTime() - before
          sampled += 1
        } else sink = decodeBody()
        batch += 1
      }
      count += 64
    }
    val elapsed = System.nanoTime() - begun
    val allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore
    require(sink == payload.length && allocated > 0 && sampled > 0)
    Arrays.sort(samples, 0, sampled)
    val p99 = samples(math.ceil(sampled * .99).toInt - 1)
    val wall = elapsed / 1e9
    println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"chunks":${chunks.size},"payloadBytes":${payload.length},"messages":$count,"elapsedSeconds":$wall,"messagesPerSecond":${count / wall},"bytesPerMessage":${allocated.toDouble / count},"p99Nanos":$p99,"samples":$sampled,"sampleStride":64,"sampleCapacity":${samples.length},"correct":true}""")
  }
}
