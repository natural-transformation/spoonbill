package spoonbill.internal

import avocet.Id
import java.lang.management.ManagementFactory
import java.util.Arrays
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Versions.ViewOwnershipEpoch

/** Matched public frontend admission-to-pull fixture, with a live measuring thread. */
object FrontendFrameBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.parasitic
  private given Reporter = Reporter.PrintReporter
  @volatile private var sink = ""

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    val recovery = scenario == "property" || scenario == "patch"
    val frontend = new Frontend[Future](Queue[Future, String]().stream, None,
      authorize = Some(() => Future.unit), viewRecoveryEpoch = Option.when(recovery)(ViewOwnershipEpoch.initial))
    def done[A](value: Future[A]): A = value.value.getOrElse(sys.error("Unexpected asynchronous work")).get
    val id = Id("1")
    val producer = () => Future.unit
    if (recovery) {
      done(frontend.resetDomChangesAuthorized(_ => (), producer))
      require(done(frontend.outgoingMessages.pull()).get.startsWith("[19,"))
    }
    def send(): String = {
      val task = scenario match {
        case "control" => frontend.focus(id)
        case "property" => frontend.setProperty(id, "title", "value")
        case "patch" => frontend.performDomChangesAuthorized(_ => (), producer)
        case "action" => frontend.runUserAction(frontend.focus(id))
      }
      done(task)
      done(frontend.outgoingMessages.pull()).get
    }
    val prefix = if (recovery) "[20," else "[5,"
    require(send().startsWith(prefix))
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId
    val warmUntil = System.nanoTime() + seconds.toLong * 1000000000L
    while (System.nanoTime() < warmUntil) sink = send()
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
          sink = send()
          require(sampled < samples.length)
          samples(sampled) = System.nanoTime() - before
          sampled += 1
        } else sink = send()
        batch += 1
      }
      count += 256
    }
    val elapsed = System.nanoTime() - begun
    val allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore
    require(sink.startsWith(prefix) && allocated > 0 && sampled > 0)
    Arrays.sort(samples, 0, sampled)
    val p99 = samples(math.ceil(sampled * .99).toInt - 1)
    val wall = elapsed / 1e9
    done(frontend.close())
    println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"messages":$count,"elapsedSeconds":$wall,"messagesPerSecond":${count / wall},"bytesPerMessage":${allocated.toDouble / count},"p99Nanos":$p99,"samples":$sampled,"sampleStride":256,"sampleCapacity":${samples.length},"correct":true}""")
  }
}
