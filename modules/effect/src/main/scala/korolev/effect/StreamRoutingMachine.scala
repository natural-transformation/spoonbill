package spoonbill.effect

import scala.collection.immutable.Queue

/**
 * Routing and read credit. Callback, source and cleanup handles belong to the
 * interpreter.
 */
private[effect] object StreamRoutingMachine {
  final class Lane
  final class Pull
  enum Mode { case Fanout, Partition, Buffered }
  enum Phase {
    case Open, Ended, Canceled
    case Failed(error: Throwable)
  }
  enum Reader { case Waiting, Reading }
  final case class Endpoint[A](values: Queue[A] = Queue.empty[A], pulls: Vector[Pull] = Vector.empty)
  final case class State[A](
    mode: Mode,
    capacity: Int,
    phase: Phase = Phase.Open,
    reader: Reader = Reader.Waiting,
    lanes: Map[Lane, Endpoint[A]] = Map.empty[Lane, Endpoint[A]],
    cleanupClaimed: Boolean = false
  )
  final case class Delivery[+A](pull: Pull, result: Either[Throwable, Option[A]])
  final case class Step[A](
    state: State[A],
    deliveries: Vector[Delivery[A]] = Vector.empty[Delivery[A]],
    cleanup: Boolean = false
  )

  def add[A](state: State[A], lane: Lane): State[A] =
    state.copy(lanes = state.lanes.updated(lane, Endpoint[A]()))

  def canRead[A](state: State[A]): Boolean =
    state.phase == Phase.Open && state.reader == Reader.Waiting && state.lanes.nonEmpty &&
      (state.mode == Mode.Buffered || state.lanes.valuesIterator.exists(_.pulls.nonEmpty)) &&
      (state.mode == Mode.Partition || state.lanes.valuesIterator.forall(_.values.size < state.capacity))

  def beginRead[A](state: State[A]): State[A]   = state.copy(reader = Reader.Reading)
  def readSettled[A](state: State[A]): State[A] = state.copy(reader = Reader.Waiting)

  def pull[A](state: State[A], lane: Lane, ticket: Pull): Step[A] = state.lanes.get(lane) match {
    case None => Step(state, Vector(Delivery(ticket, Right(None))))
    case Some(endpoint) if endpoint.values.nonEmpty =>
      val (value, rest) = endpoint.values.dequeue
      Step(
        state.copy(lanes = state.lanes.updated(lane, endpoint.copy(values = rest))),
        Vector(Delivery(ticket, Right(Some(value))))
      )
    case Some(endpoint) =>
      state.phase match {
        case Phase.Open =>
          Step(state.copy(lanes = state.lanes.updated(lane, endpoint.copy(pulls = endpoint.pulls :+ ticket))))
        case Phase.Failed(error) => Step(state, Vector(Delivery(ticket, Left(error))))
        case _                   => Step(state, Vector(Delivery(ticket, Right(None))))
      }
  }

  /**
   * The interpreter constructs the public error only if a target lacks credit.
   */
  def overflow[A](state: State[A], lane: Lane): Boolean =
    state.lanes.get(lane).exists(endpoint => endpoint.pulls.isEmpty && endpoint.values.size >= state.capacity)

  def received[A](state: State[A], value: A, target: Option[Lane]): Step[A] =
    if (state.phase != Phase.Open) Step(state.copy(reader = Reader.Waiting))
    else {
      val destinations = target.fold(state.lanes.keySet)(Set(_))
      val delivered = state.lanes.iterator.foldLeft((state.lanes, Vector.empty[Delivery[A]])) {
        case ((lanes, notices), (lane, endpoint)) if destinations(lane) =>
          endpoint.pulls.headOption match {
            case Some(ticket) =>
              (
                lanes.updated(lane, endpoint.copy(pulls = endpoint.pulls.tail)),
                notices :+ Delivery(ticket, Right(Some(value)))
              )
            case None => (lanes.updated(lane, endpoint.copy(values = endpoint.values.enqueue(value))), notices)
          }
        case (result, _) => result
      }
      Step(state.copy(reader = Reader.Waiting, lanes = delivered._1), delivered._2)
    }

  def ended[A](state: State[A]): Step[A] =
    if (state.phase != Phase.Open) Step(state.copy(reader = Reader.Waiting))
    else
      Step(
        state.copy(
          phase = Phase.Ended,
          reader = Reader.Waiting,
          lanes = state.lanes.view.mapValues(_.copy(pulls = Vector.empty)).toMap
        ),
        state.lanes.valuesIterator.flatMap(_.pulls).map(Delivery(_, Right(None))).toVector
      )

  def failed[A](state: State[A], error: Throwable): Step[A] =
    if (state.phase != Phase.Open) Step(state.copy(reader = Reader.Waiting))
    else {
      val cleanup = state.mode == Mode.Partition && !state.cleanupClaimed
      Step(
        state.copy(
          phase = Phase.Failed(error),
          reader = Reader.Waiting,
          cleanupClaimed = state.cleanupClaimed || cleanup,
          lanes = state.lanes.view
            .mapValues(endpoint =>
              endpoint.copy(
                values = if (state.mode == Mode.Buffered) endpoint.values else Queue.empty[A],
                pulls = Vector.empty
              )
            )
            .toMap
        ),
        state.lanes.valuesIterator.flatMap(_.pulls).map(Delivery(_, Left(error))).toVector,
        cleanup
      )
    }

  def cancel[A](state: State[A], lane: Option[Lane]): Step[A] =
    if (state.mode == Mode.Fanout && lane.nonEmpty) {
      val pending = state.lanes.get(lane.get).toVector.flatMap(_.pulls)
      Step(state.copy(lanes = state.lanes - lane.get), pending.map(Delivery(_, Right(None))))
    } else {
      val cleanup = !state.cleanupClaimed
      val phase = state.phase match {
        case failed: Phase.Failed if state.mode == Mode.Partition => failed
        case _                                                    => Phase.Canceled
      }
      val result = phase match {
        case Phase.Failed(error) => Left(error)
        case _                   => Right(None)
      }
      Step(
        state.copy(phase = phase, lanes = state.lanes.view.mapValues(_ => Endpoint[A]()).toMap, cleanupClaimed = true),
        state.lanes.valuesIterator.flatMap(_.pulls).map(Delivery(_, result)).toVector,
        cleanup
      )
    }

  def cleanupRejected[A](state: State[A]): State[A] = state.copy(cleanupClaimed = false)

  def drain[A](state: State[A], lane: Lane): (State[A], Vector[A]) = state.lanes.get(lane) match {
    case None => (state, Vector.empty)
    case Some(endpoint) =>
      (state.copy(lanes = state.lanes.updated(lane, endpoint.copy(values = Queue.empty[A]))), endpoint.values.toVector)
  }
}
