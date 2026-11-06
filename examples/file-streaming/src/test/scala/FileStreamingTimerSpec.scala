import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.internal.TimerAdapterChecks
import spoonbill.monix.*
import spoonbill.state.javaSerialization.*

class FileStreamingTimerSpec extends AnyFlatSpec with Matchers {
  "Monix ownership" should "close without joining application computation and reject its late result" in {
    val initial = FileStreamingExample.State(false, Map.empty, false)
    TimerAdapterChecks.periodic(
      initial,
      FileStreamingExample.aliveIndicatorTick,
      initial.copy(aliveIndicator = true),
      initial,
      holdComputationAtClose = true
    )
  }
  "Monix render timers" should "defer legacy constructors and observe their failures once" in {
    TimerAdapterChecks.legacy[Task]()
  }
  it should "toggle the migrated streaming indicator twice through the actual runtime" in {
    val initial = FileStreamingExample.State(false, Map.empty, false)
    TimerAdapterChecks.periodic(
      initial,
      FileStreamingExample.aliveIndicatorTick,
      initial.copy(aliveIndicator = true),
      initial
    )
  }
  it should "toggle the one-by-one streaming indicator twice through the actual runtime" in {
    val initial = OneByOneFileStreamingExample.State(false, Map.empty, false)
    TimerAdapterChecks.periodic(
      initial,
      OneByOneFileStreamingExample.aliveIndicatorTick,
      initial.copy(aliveIndicator = true),
      initial
    )
  }
}
