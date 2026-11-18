package spoonbill.effect.io

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SocketCloseReservationMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import SocketCloseReservationMachine.*

  "Reservation decisions" should "name every ownership transition and ignore repeated or misplaced signals" in {
    val transitions = Map(
      (State.Reserved, Event.Dispatch) -> (State.Dispatched, Command.Submit),
      (State.ReservedAfterConnection, Event.Dispatch) -> (State.AwaitingNotification, Command.Submit),
      (State.Reserved, Event.Cancel) -> (State.Released, Command.Release),
      (State.ReservedAfterConnection, Event.Cancel) -> (State.Released, Command.Release),
      (State.Reserved, Event.ConnectionFinished) -> (State.ReservedAfterConnection, Command.None),
      (State.Dispatched, Event.ConnectionFinished) -> (State.AwaitingNotification, Command.None),
      (State.Dispatched, Event.NotificationFinished) -> (State.AwaitingConnection, Command.None),
      (State.AwaitingConnection, Event.ConnectionFinished) -> (State.Released, Command.Release),
      (State.AwaitingNotification, Event.NotificationFinished) -> (State.Released, Command.Release)
    )
    for (state <- State.values; event <- Event.values)
      step(state, event) shouldBe transitions.getOrElse((state, event), (state, Command.None))
  }

  it should "submit and release at most once and make release terminal over generated histories" in {
    forAll(Gen.listOf(Gen.oneOf(Event.values.toSeq))) { events =>
      val (_, submitted, released) = events.foldLeft((initial, 0, 0)) { case ((before, submitted, released), event) =>
        val (after, command) = step(before, event)
        val submits = submitted + (if (command == Command.Submit) 1 else 0)
        val releases = released + (if (command == Command.Release) 1 else 0)
        submits should be <= 1
        releases should be <= 1
        if (before == State.Released) after shouldBe State.Released
        if (command == Command.Release) after shouldBe State.Released
        (after, submits, releases)
      }
      submitted should be <= 1
      released should be <= 1
    }
  }
}
