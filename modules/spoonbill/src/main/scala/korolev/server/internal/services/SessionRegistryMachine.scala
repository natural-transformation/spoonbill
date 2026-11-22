package spoonbill.server.internal.services

import scala.collection.immutable.VectorMap

/**
 * Metadata only. Application handles and their asynchronous publication stay in
 * the application table. Storage retirement executes outside the metadata
 * monitor, while the exact attachment owner retains the local fence.
 *
 * An unguarded page has a fresh-page record: made when the page is rendered,
 * before its state is stored, and taken once by the attach of its session.
 * It is positive evidence that the stored state is the one that the page
 * shows. A record ends in one of three steps of this one state, so only one
 * can win: a claim takes it, or an expiry takes it at a later render, or the
 * removal of its state ends it. A claimed record cannot expire, and a record
 * whose state is being removed refuses every claim.
 */
private[spoonbill] object SessionRegistryMachine {

  /** The fresh-page record of an unguarded page, with the time when the page was rendered. */
  enum FreshPage {

    /** Rendered, and not claimed. */
    case Waiting(rendered: Long)

    /** Expired, and its state is being removed from a storage that is local to this process. */
    case Releasing(rendered: Long)
  }

  /** What a claim of the fresh-page record finds. */
  enum FreshClaim {

    /** A record within its lifetime. The claim has taken it. */
    case Fresh

    /** A record older than its lifetime. The claim took it only when it was asked to. */
    case Expired

    /** No record: the page was not rendered by this server, or an attach took the record. */
    case Absent

    /** The state of this page is being removed. */
    case Releasing
  }

  final case class Slot[C, P](
    owner: Option[C] = None,
    retryRemoval: Boolean = false,
    bootstrap: Option[(Long, P)] = None,
    fresh: Option[FreshPage] = None
  )
  final case class State[K, C, P](
    slots: Map[K, Slot[C, P]] = Map.empty[K, Slot[C, P]],
    bootstrapOrder: VectorMap[K, Unit] = VectorMap.empty[K, Unit],
    freshOrder: VectorMap[K, Unit] = VectorMap.empty[K, Unit]
  )
  private def slot[K, C, P](state: State[K, C, P], key: K): Slot[C, P] =
    state.slots.getOrElse(key, Slot[C, P]())
  private def put[K, C, P](state: State[K, C, P], key: K, value: Slot[C, P]): State[K, C, P] =
    state.copy(slots =
      if (value.owner.isEmpty && !value.retryRemoval && value.bootstrap.isEmpty && value.fresh.isEmpty) state.slots - key
      else state.slots.updated(key, value)
    )

  def claim[K, C, P](state: State[K, C, P], key: K, owner: C): (State[K, C, P], Boolean) = {
    val previous = slot(state, key)
    if (previous.owner.nonEmpty) (state, false)
    else (put(state, key, previous.copy(owner = Some(owner))), true)
  }
  def release[K, C, P](state: State[K, C, P], key: K, owner: C): State[K, C, P] = {
    val previous = slot(state, key)
    if (previous.owner.contains(owner)) put(state, key, previous.copy(owner = None)) else state
  }
  def needsRemoval[K, C, P](state: State[K, C, P], key: K): Boolean =
    state.slots.get(key).exists(_.retryRemoval)
  def removalFailed[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    put(state, key, slot(state, key).copy(retryRemoval = true))
  def removalSucceeded[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    put(state, key, slot(state, key).copy(retryRemoval = false))

  def remember[K, C, P](state: State[K, C, P], key: K, created: Long, path: P, capacity: Int): State[K, C, P] = {
    require(capacity > 0)
    val previous = slot(state, key)
    val ordered  = if (previous.bootstrap.nonEmpty) state.bootstrapOrder else state.bootstrapOrder.updated(key, ())
    val next     = put(state.copy(bootstrapOrder = ordered), key, previous.copy(bootstrap = Some(created -> path)))
    if (ordered.size <= capacity) next else take(next, ordered.head._1)._1
  }
  def take[K, C, P](state: State[K, C, P], key: K): (State[K, C, P], Option[(Long, P)]) = {
    val previous = slot(state, key)
    if (previous.bootstrap.isEmpty) (state, None)
    else
      (
        put(state.copy(bootstrapOrder = state.bootstrapOrder - key), key, previous.copy(bootstrap = None)),
        previous.bootstrap
      )
  }
  // The original monotonic-duration test is inclusive, and retains Long wrap semantics.
  def fresh(created: Long, now: Long, retentionNanos: Long): Boolean = now - created <= retentionNanos

  /** The record of the fresh page itself, without the order of the renders. */
  private def withFresh[K, C, P](state: State[K, C, P], key: K, value: Option[FreshPage]): State[K, C, P] =
    put(state, key, slot(state, key).copy(fresh = value))
  private def withoutFresh[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    withFresh(state.copy(freshOrder = state.freshOrder - key), key, None)

  /**
   * A page of the session was rendered at `now`. It is made before the state is
   * stored, so that no stored state is without a record. A record whose state is
   * being removed stays as it is.
   */
  def rendered[K, C, P](state: State[K, C, P], key: K, now: Long): State[K, C, P] =
    slot(state, key).fresh match {
      case Some(FreshPage.Releasing(_)) => state
      case _ =>
        // A new render moves the record to the end of the order.
        withFresh(state.copy(freshOrder = (state.freshOrder - key).updated(key, ())), key, Some(FreshPage.Waiting(now)))
    }

  /**
   * An unguarded attach claims the record of its session. A record within its
   * lifetime is taken. An expired one is taken only with `takeExpired`: a poll
   * leaves it where it is, so that the expiry can end it. The age is checked
   * here, so the answer does not depend on other renders.
   */
  def claimFresh[K, C, P](
    state: State[K, C, P],
    key: K,
    now: Long,
    lifetimeNanos: Long,
    takeExpired: Boolean
  ): (State[K, C, P], FreshClaim) =
    slot(state, key).fresh match {
      case None                         => (state, FreshClaim.Absent)
      case Some(FreshPage.Releasing(_)) => (state, FreshClaim.Releasing)
      case Some(FreshPage.Waiting(rendered)) =>
        if (fresh(rendered, now, lifetimeNanos)) (withoutFresh(state, key), FreshClaim.Fresh)
        else if (takeExpired) (withoutFresh(state, key), FreshClaim.Expired)
        else (state, FreshClaim.Expired)
    }

  /**
   * At most `limit` records that are older than their lifetime, the oldest
   * first. With `removes` they become [[FreshPage.Releasing]], and their keys
   * are given out for the removal of their states; each key is given out once.
   * Without `removes` the records just end, and nothing is given out. No count
   * pushes a record out: only its age does.
   */
  def expireFresh[K, C, P](
    state: State[K, C, P],
    now: Long,
    lifetimeNanos: Long,
    limit: Int,
    removes: Boolean
  ): (State[K, C, P], Vector[K]) = {
    require(limit > 0)
    // The order is the order of the renders. The first record within its
    // lifetime ends the search: the ones after it are younger.
    val expired = state.freshOrder.keysIterator
      .map(key => key -> slot(state, key).fresh)
      .filterNot { case (_, record) => record.exists(_.isInstanceOf[FreshPage.Releasing]) }
      .takeWhile {
        case (_, Some(FreshPage.Waiting(rendered))) => !fresh(rendered, now, lifetimeNanos)
        case _                                      => false
      }
      .take(limit)
      .collect { case (key, Some(FreshPage.Waiting(rendered))) => key -> rendered }
      .toVector
    if (removes)
      (
        expired.foldLeft(state) { case (next, (key, rendered)) =>
          withFresh(next, key, Some(FreshPage.Releasing(rendered)))
        },
        expired.map(_._1)
      )
    else (expired.foldLeft(state) { case (next, (key, _)) => withoutFresh(next, key) }, Vector.empty[K])
  }

  /** The removal of the state of a releasing record has returned. The record is gone. */
  def freshReleased[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    slot(state, key).fresh match {
      case Some(FreshPage.Releasing(_)) => withoutFresh(state, key)
      case _                            => state
    }

  /** The removal failed. The record is back, and a later expiry gives it out again. */
  def freshRemovalFailed[K, C, P](state: State[K, C, P], key: K): State[K, C, P] =
    slot(state, key).fresh match {
      case Some(FreshPage.Releasing(rendered)) => withFresh(state, key, Some(FreshPage.Waiting(rendered)))
      case _                                   => state
    }
}
