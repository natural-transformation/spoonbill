package zio.http.netty

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel
import java.lang.management.ManagementFactory
import java.util.Arrays

/** The actual event-loop handler, with timer registration/cancellation and no decoder payload copy. */
object WebSocketFrameDeadlineBenchmark {
  @volatile private var sink = 0

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    def frame(length: Int): Array[Byte] = {
      val header = if (length < 126) Array(0x82.toByte, (0x80 | length).toByte)
      else Array[Byte](0x82.toByte, 0xfe.toByte, (length >> 8).toByte, length.toByte)
      header ++ Array[Byte](0, 0, 0, 0) ++ Array.fill[Byte](length)(7)
    }
    val chunks = scenario match {
      case "small" => Vector(frame(1))
      case "large" => Vector(frame(8192))
      case "fragmented" =>
        val wire = frame(128)
        wire.take(8).map(value => Array(value)).toVector :+ wire.drop(8)
      case "coalesced" => Vector(Array.fill(32)(frame(1)).flatten)
    }
    val expected = chunks.map(_.length).sum
    val channel = new EmbeddedChannel(new WebSocketFrameDeadline(1000000L,
      error => throw new AssertionError("Unexpected frame abort", error), () => 0L))
    def observe(): Int = {
      var bytes = 0
      var index = 0
      while (index < chunks.length) {
        val input = Unpooled.wrappedBuffer(chunks(index))
        require(channel.writeInbound(input))
        val forwarded = channel.readInbound[ByteBuf]()
        require((forwarded eq input) && forwarded.readerIndex() == 0)
        bytes += forwarded.readableBytes()
        forwarded.release()
        index += 1
      }
      bytes
    }
    require(observe() == expected)
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId
    val warmUntil = System.nanoTime() + seconds.toLong * 1000000000L
    while (System.nanoTime() < warmUntil) sink = observe()
    val samples = new Array[Long](1 << 20)
    var sampled = 0
    var count = 0L
    val allocatedBefore = allocation.getThreadAllocatedBytes(thread)
    val begun = System.nanoTime()
    val until = begun + seconds.toLong * 1000000000L
    while (System.nanoTime() < until) {
      var batch = 0
      while (batch < 256) {
        if (batch == 0) {
          val before = System.nanoTime()
          sink = observe()
          require(sampled < samples.length)
          samples(sampled) = System.nanoTime() - before
          sampled += 1
        } else sink = observe()
        batch += 1
      }
      count += 256
    }
    val elapsed = System.nanoTime() - begun
    val allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore
    require(sink == expected && allocated > 0 && sampled > 0)
    Arrays.sort(samples, 0, sampled)
    val p99 = samples(math.ceil(sampled * .99).toInt - 1)
    val wall = elapsed / 1e9
    channel.finishAndReleaseAll()
    println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"chunks":${chunks.size},"payloadBytes":$expected,"messages":$count,"elapsedSeconds":$wall,"messagesPerSecond":${count / wall},"bytesPerMessage":${allocated.toDouble / count},"p99Nanos":$p99,"samples":$sampled,"sampleStride":256,"sampleCapacity":${samples.length},"correct":true}""")
  }
}
