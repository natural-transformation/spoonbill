package spoonbill.snapshot

import avocet.Id
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.effect.Effect
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.state.{GuardedStateManager, StateManager}
import spoonbill.state.javaSerialization.*

class ViewSnapshotSessionSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private case class State(principal: String, counter: Int) extends Serializable
  private val fresh = State("fresh-authority", 0)
  private val initial = ViewRevision.initial

  private class Store(loaded: Either[ViewSnapshotError, SnapshotLoad[Int]]) extends ViewSnapshotStore[Future, Int] {
    val ownerEpoch = ViewOwnershipEpoch.initial
    var saves = Vector.empty[(ViewRevision, Int)]
    var resets = Vector.empty[(ViewRevision, Int)]
    var rejection = Option.empty[ViewSnapshotError]
    def load() = Future.successful(loaded)
    def save(expected: ViewRevision, value: Int) = {
      saves :+= expected -> value
      Future.successful(rejection.toLeft(expected.next.toOption.getOrElse(fail("Revision exhausted"))))
    }
    def reset(expected: ViewRevision, value: Int) = {
      resets :+= expected -> value
      Future.successful(Right(expected.next.toOption.getOrElse(fail("Revision exhausted"))))
    }
  }

  private def session(store: Store) =
    ViewSnapshotSession.projected[Future, State, Int](store, _.counter, (state, count) => state.copy(counter = count))

  "ViewSnapshotSession" should "restore only the selected presentation onto fresh authority" in {
    val store = new Store(Right(SnapshotLoad.Restored(initial, 7)))
    session(store).initialize(fresh).map { state =>
      state shouldBe State("fresh-authority", 7)
      store.saves shouldBe empty
      store.resets shouldBe empty
    }
  }

  it should "persist the initial projection once and continue its expected revision" in {
    val store = new Store(Right(SnapshotLoad.Empty(initial)))
    val bound = session(store)
    for {
      state <- bound.initialize(fresh)
      _ <- bound.commit(state.copy(counter = 2))
    } yield {
      store.saves.map { case (revision, value) => revision.toLong -> value } shouldBe Vector(0L -> 0, 1L -> 2)
      store.resets shouldBe empty
    }
  }

  it should "reset an incompatible identity without applying its presentation" in {
    val store = new Store(Right(SnapshotLoad.ResetRequired(initial, SnapshotResetReason.IdentityChanged)))
    session(store).initialize(fresh).map { state =>
      state shouldBe fresh
      store.resets shouldBe Vector(initial -> 0)
      store.saves shouldBe empty
    }
  }

  it should "fail closed on malformed compatible data without resetting it" in {
    val store = new Store(Left(ViewSnapshotError.MalformedSnapshot))
    session(store).initialize(fresh).failed.map { error =>
      error.asInstanceOf[ViewSnapshotException].error shouldBe ViewSnapshotError.MalformedSnapshot
      store.resets shouldBe empty
      store.saves shouldBe empty
    }
  }

  it should "keep the visible state unchanged when snapshot CAS rejects a mutation" in {
    val store = new Store(Right(SnapshotLoad.Restored(initial, 0)))
    val bound = session(store)
    val raw = StateManager.cached[Future](Map(Id.TopLevel -> fresh))
    val guarded = new GuardedStateManager[Future, State](raw, _ => Future.unit, Some(bound.commit))
    for {
      _ <- bound.initialize(fresh)
      _ = store.rejection = Some(ViewSnapshotError.StaleOwner)
      rejected <- guarded.write(Id.TopLevel, fresh.copy(counter = 99)).failed
      current <- raw.read[State](Id.TopLevel)
      _ <- guarded.closeAndDrain()
    } yield {
      rejected.asInstanceOf[ViewSnapshotException].error shouldBe ViewSnapshotError.StaleOwner
      current shouldBe Some(fresh)
      store.saves.size shouldBe 1 // No blind retry or overwriting a newer owner.
    }
  }

  it should "drain an admitted snapshot commit before closing the local manager" in {
    val admitted = Promise[Unit]()
    val finish = Promise[Unit]()
    val raw = StateManager.cached[Future](Map(Id.TopLevel -> fresh))
    val guarded = new GuardedStateManager[Future, State](raw, _ => Future.unit, Some { _ =>
      admitted.trySuccess(())
      finish.future
    })
    val writing = guarded.write(Id.TopLevel, fresh.copy(counter = 1))
    for {
      _ <- admitted.future
      draining = guarded.closeAndDrain()
      _ = draining.isCompleted shouldBe false
      before <- raw.read[State](Id.TopLevel)
      _ = before shouldBe Some(fresh)
      _ = finish.success(())
      _ <- writing
      _ <- draining
      after <- raw.read[State](Id.TopLevel)
    } yield after shouldBe Some(fresh.copy(counter = 1))
  }

  it should "serialize persistence through the matching cache update before the next mutation" in {
    val cacheStarted = Promise[Unit]()
    val finishCache = Promise[Unit]()
    val secondPersist = Promise[State]()
    val cached = StateManager.cached[Future](Map(Id.TopLevel -> fresh))
    val raw = new StateManager[Future] {
      def snapshot = cached.snapshot
      def read[T: spoonbill.state.StateDeserializer](id: Id) = cached.read[T](id)
      def delete(id: Id) = cached.delete(id)
      def write[T: spoonbill.state.StateSerializer](id: Id, value: T) = {
        val state = value.asInstanceOf[State]
        if (state.counter == 1) {
          cacheStarted.trySuccess(())
          finishCache.future.flatMap(_ => cached.write(id, value))
        } else cached.write(id, value)
      }
    }
    val guarded = new GuardedStateManager[Future, State](raw, _ => Future.unit, Some { state =>
      if (state.counter == 2) raw.read[State](Id.TopLevel).map { current =>
        secondPersist.trySuccess(current.getOrElse(fail("Missing cached state"))); ()
      } else Future.unit
    })
    val first = guarded.write(Id.TopLevel, fresh.copy(counter = 1))
    for {
      _ <- cacheStarted.future
      second = guarded.write(Id.TopLevel, fresh.copy(counter = 2))
      _ = secondPersist.isCompleted shouldBe false
      _ = finishCache.success(())
      _ <- first
      _ <- second
      observed <- secondPersist.future
      current <- raw.read[State](Id.TopLevel)
      _ <- guarded.closeAndDrain()
    } yield {
      observed.counter shouldBe 1
      current shouldBe Some(fresh.copy(counter = 2))
    }
  }
}
