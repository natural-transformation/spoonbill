package spoonbill.recovery.testkit

import spoonbill.effect.Effect
import spoonbill.recovery.{FaultDecision, RecoveryFault, RecoveryProbe}

/**
 * The probe a harness installs in its bindings and stores. arm(point) returns a
 * gate; the next operation reaching that point waits for the gate's decision,
 * and the gate then disarms itself.
 */
final class RecoveryGates[F[_]](using F: Effect[F]) extends RecoveryProbe[F] {
  private val monitor = new Object
  private var armed   = Map.empty[RecoveryFault, Gate]

  private final class Gate(point: RecoveryFault) extends RecoveryGate[F] {
    private var state            = RecoveryGateMachine.State.Waiting
    private var reachedCallbacks = List.empty[Effect.Promise[Unit]]
    private var waiting          = List.empty[Effect.Promise[FaultDecision]]

    def reached: F[Unit] = F.promise { callback =>
      val done = monitor.synchronized {
        if (RecoveryGateMachine.reached(state)) true
        else { reachedCallbacks ::= callback; false }
      }
      if (done) callback(Right(()))
    }

    def resume(value: FaultDecision): F[Unit] = F.delay {
      val waiters = monitor.synchronized {
        state = RecoveryGateMachine.resume(state, value)
        // A resumed older gate must not disarm a replacement at this point.
        if (armed.get(point).contains(this)) armed -= point
        val result = waiting; waiting = Nil; result
      }
      waiters.foreach(_(Right(value)))
    }

    /**
     * Only the first operation to arrive is held; later arrivals proceed at
     * once.
     */
    def arrive(): F[FaultDecision] = F.promise { callback =>
      val (notify, decided) = monitor.synchronized {
        val arrival = RecoveryGateMachine.arrive(state)
        state = arrival.state
        val observers = if (arrival.first) reachedCallbacks else Nil
        if (arrival.first) reachedCallbacks = Nil
        if (arrival.decision.isEmpty) waiting ::= callback
        (observers, arrival.decision)
      }
      decided.foreach(value => callback(Right(value)))
      notify.foreach(_(Right(())))
    }
  }

  def arm(point: RecoveryFault): RecoveryGate[F] = monitor.synchronized {
    val gate = new Gate(point)
    armed += point -> gate
    gate
  }

  def at(point: RecoveryFault): F[FaultDecision] = F.delayAsync {
    monitor.synchronized(armed.get(point)) match {
      case Some(gate) => gate.arrive()
      case None       => F.pure(FaultDecision.Proceed)
    }
  }
}
