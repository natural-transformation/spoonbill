package spoonbill.recovery.testkit

import spoonbill.recovery.{PayloadDelete, PayloadRead, PayloadWrite, SnapshotBytes}

/** One reference has one slot, including a retirement fence after deletion. */
private[testkit] object PayloadStoreMachine {
  enum Slot {
    case Stored(value: SnapshotBytes)
    // Diagnostics may deliberately install corrupt bytes behind a fence.
    case Retired(value: Option[SnapshotBytes])
  }

  def bytes(slot: Slot): Option[SnapshotBytes] = slot match {
    case Slot.Stored(value)  => Some(value)
    case Slot.Retired(value) => value
  }

  def read(slot: Option[Slot]): PayloadRead =
    slot.flatMap(bytes).fold[PayloadRead](PayloadRead.Missing)(PayloadRead.Found.apply)

  def put(slot: Option[Slot], value: SnapshotBytes): (Slot, PayloadWrite) = slot match {
    case Some(retired: Slot.Retired) => (retired, PayloadWrite.Retired)
    case Some(stored @ Slot.Stored(existing)) =>
      (stored, if (existing == value) PayloadWrite.Durable else PayloadWrite.Conflict)
    case None => (Slot.Stored(value), PayloadWrite.Durable)
  }

  def delete(fenced: Boolean): (Option[Slot], PayloadDelete) =
    if (fenced) (Some(Slot.Retired(None)), PayloadDelete.FinalDeleted)
    else (None, PayloadDelete.Uncertain)

  def corrupt(slot: Option[Slot], value: SnapshotBytes): Slot = slot match {
    case Some(_: Slot.Retired) => Slot.Retired(Some(value))
    case _                     => Slot.Stored(value)
  }
}
