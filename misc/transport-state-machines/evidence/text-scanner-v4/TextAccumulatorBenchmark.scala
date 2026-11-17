package spoonbill.web

import java.lang.management.ManagementFactory
import java.util.Arrays

/** Standalone before/after fixture; compile through the repository's Nix shell. */
object TextAccumulatorBenchmark {
  @volatile private var sink = ""

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    val chunks = scenario match {
      case "short" => Array("ping")
      case "ascii" => Array("x" * 1024)
      case "unicode" => Array("\u03bb\ud83d\ude00" * 128)
      case "fragmented" => Array.fill(16)("x" * 64)
    }
    val expected = chunks.mkString
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId

    def accumulate(): String = {
      val text = new WebSocketLimits.TextAccumulator(16384)
      var index = 0
      while (index < chunks.length) { text.append(chunks(index)); index += 1 }
      text.result()
    }
    require(accumulate() == expected)
    val warmUntil = System.nanoTime() + seconds.toLong * 1000000000L
    while (System.nanoTime() < warmUntil) sink = accumulate()

    val samples = new Array[Long](1 << 22)
    var sampleCount = 0
    var count = 0L
    val bytesBefore = allocation.getThreadAllocatedBytes(thread)
    val begun = System.nanoTime()
    val until = begun + seconds.toLong * 1000000000L
    while (System.nanoTime() < until) {
      var batch = 0
      while (batch < 256) {
        if (batch == 0) {
          val before = System.nanoTime()
          sink = accumulate()
          if (sampleCount < samples.length) samples(sampleCount) = System.nanoTime() - before
          sampleCount += 1
        } else sink = accumulate()
        batch += 1
      }
      count += 256
    }
    val elapsed = System.nanoTime() - begun
    val allocated = allocation.getThreadAllocatedBytes(thread) - bytesBefore
    require(sampleCount > 0 && sampleCount <= samples.length)
    require(sink == expected && allocated >= 0)
    Arrays.sort(samples, 0, sampleCount)
    val p99 = samples(math.ceil(sampleCount * .99).toInt - 1)
    val wall = elapsed / 1e9
    println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"chunks":${chunks.length},"characters":${expected.length},"messages":$count,"elapsedSeconds":$wall,"messagesPerSecond":${count / wall},"bytesPerMessage":${allocated.toDouble / count},"p99Nanos":$p99,"samples":$sampleCount,"sampleStride":256,"sampleCapacity":${samples.length},"correct":true}""")
  }
}
