package spoonbill.http.protocol

import java.lang.management.ManagementFactory
import spoonbill.data.Bytes
import spoonbill.effect.Decoder.Action

/** Allocation attribution only: no latency or acceptance measurement. */
object ChunkedEmptyInputProbe {
  @volatile private var sink: Action[Bytes, Bytes] = Action.TakeNext
  def main(args: Array[String]): Unit = {
    val decoder = new ChunkedBodyDecoder.Cell[Bytes](1024, 16384)
    decoder.decode(Bytes.wrap("7fffffffffffffff\r\nx".getBytes("US-ASCII")))
    val empty = Bytes.empty
    val iterations = 1000000
    var index = 0
    while (index < iterations) { sink = decoder.decode(empty); index += 1 }
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId
    val before = allocation.getThreadAllocatedBytes(thread)
    index = 0
    while (index < iterations) { sink = decoder.decode(empty); index += 1 }
    val allocated = allocation.getThreadAllocatedBytes(thread) - before
    require(sink == Action.TakeNext && !decoder.completed && allocated >= 0L)
    println(s"""{"label":"${args(0)}","operation":"empty input while awaiting payload","iterations":$iterations,"allocatedBytes":$allocated,"bytesPerEmpty":${allocated.toDouble / iterations},"correct":true,"acceptance":false}""")
  }
}
