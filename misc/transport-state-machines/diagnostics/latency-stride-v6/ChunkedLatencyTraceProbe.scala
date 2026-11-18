package spoonbill.http.protocol

import java.io.{BufferedOutputStream, DataOutputStream, FileOutputStream}
import java.util.Arrays
import java.util.zip.GZIPOutputStream
import scala.concurrent.Future
import spoonbill.data.Bytes
import spoonbill.effect.{Decoder, Effect, Stream}

/** Bounded sampling diagnostic: every operation and all 64 offsets from one trace. */
object ChunkedLatencyTraceProbe {
  private given Effect[Future] = Effect.futureEffect
  @volatile private var sink = 0L

  def main(args: Array[String]): Unit = {
    val Array(label, tracePath) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val payload = "x" * 8192
    val chunks = Vector(Bytes.wrap(("2000\r\n" + payload + "\r\n0\r\n\r\n").getBytes("US-ASCII")))
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
    val count = 131072
    val trace = new Array[Long](count)
    var index = 0
    while (index < 1000000) { sink = decodeBody(); index += 1 }
    index = 0
    while (index < count) {
      val before = System.nanoTime()
      sink = decodeBody()
      trace(index) = System.nanoTime() - before
      index += 1
    }
    require(sink == payload.length && trace.forall(_ >= 0L))
    val output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tracePath))))
    try {
      output.writeInt(1)
      output.writeInt(count)
      trace.foreach(output.writeLong)
    } finally output.close()
    def quantile(values: Array[Long], fraction: Double): Long = {
      Arrays.sort(values)
      values(math.ceil(values.length * fraction).toInt - 1)
    }
    val offsets = Array.tabulate(64) { offset =>
      val values = new Array[Long]((count - offset + 63) / 64)
      var at = offset
      var slot = 0
      while (at < count) { values(slot) = trace(at); slot += 1; at += 64 }
      quantile(values, .99)
    }
    val aggregateP99 = quantile(trace.clone(), .99)
    val aggregateP50 = quantile(trace.clone(), .50)
    println(s"""{"label":"$label","scenario":"large","operations":$count,"stride":64,"samplesPerOffset":${count / 64},"aggregateP50Nanos":$aggregateP50,"aggregateP99Nanos":$aggregateP99,"p99ByOffset":${offsets.mkString("[", ",", "]")},"minimumOffsetP99":${offsets.min},"maximumOffsetP99":${offsets.max},"correct":true,"acceptance":false,"traceFormat":"gzip: big-endian int version, int count, count long nanoseconds"}""")
  }
}
