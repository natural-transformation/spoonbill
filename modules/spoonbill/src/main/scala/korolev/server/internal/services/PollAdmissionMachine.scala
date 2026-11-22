package spoonbill.server.internal.services

import java.util.UUID

/**
 * Which long-polling request may build a view, and who may have the output of
 * a view.
 *
 * A page names itself with a page id, a UUID that it makes when it starts, and
 * every request says which frame the page has applied: its cursor. A session
 * has one topic entry while it has a long-polling view, and the entry names its
 * page. A poll builds a view only for a page that was just rendered and has
 * applied nothing. Everything else that does not fit ends in a reload.
 *
 * Pure: the interpreters are the messaging service and the session service.
 */
private[spoonbill] object PollAdmissionMachine {

  /** The largest cursor that a page can count to: the client counts in JavaScript numbers. */
  val maxCursor: Long = 9007199254740991L

  /** What a request says about its page. */
  final case class Cursor(page: UUID, seen: Long)

  private val PagePattern   = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r
  private val CursorPattern = "0|[1-9][0-9]{0,15}".r

  /** A page id in its canonical form, with 32 hex digits in five groups. */
  def page(value: String): Option[UUID] =
    if (PagePattern.matches(value)) Some(UUID.fromString(value)) else None

  /** A cursor without sign, leading zeros or anything else, at most [[maxCursor]]. */
  def seen(value: String): Option[Long] =
    if (CursorPattern.matches(value)) Some(value.toLong).filter(_ <= maxCursor) else None

  /**
   * The cursor of a request. A request whose page id or cursor is missing or
   * not well formed has no cursor.
   */
  def cursor(page: Option[String], seen: Option[String]): Option[Cursor] =
    for {
      id <- page.flatMap(this.page)
      n  <- seen.flatMap(this.seen)
    } yield Cursor(id, n)

  /** What a poll does, by its cursor and the topic entry of its session. */
  enum Route {

    /** The page cannot go on. It gets the reload frame. */
    case Reload

    /** The entry is this page's. The delivery answers by the cursor. */
    case Deliver

    /** No entry, and a page that has applied nothing: try a first attach. */
    case FirstAttach
  }

  /**
   * The table "Which poll builds a view" of the design.
   *
   * @param entry
   *   the page of the session's topic entry, if there is one
   */
  def route(cursor: Option[Cursor], entry: Option[UUID]): Route = (cursor, entry) match {
    case (None, _)                               => Route.Reload
    case (Some(request), Some(page))             => if (request.page == page) Route.Deliver else Route.Reload
    case (Some(request), None) if request.seen == 0L => Route.FirstAttach
    case (Some(_), None)                         => Route.Reload
  }

  /**
   * Whether a message may go to the topic entry of its session: the entry must
   * be this page's. A message never makes an entry, so without one it is gone.
   */
  def accepts(cursor: Option[Cursor], entry: Option[UUID]): Boolean = (cursor, entry) match {
    case (Some(request), Some(page)) => request.page == page
    case _                           => false
  }

  /** The transport of an attach. */
  enum Transport { case Poll, WebSocket }

  /**
   * Whether an attach takes the fresh-page record of its session when the
   * record is older than its lifetime. A poll leaves it: it goes on only with
   * a fresh record. A WebSocket needs only the stored state, so it takes the
   * record at any age.
   */
  def takesExpired(transport: Transport): Boolean = transport == Transport.WebSocket

  /**
   * Whether an attach goes on after it claimed the fresh-page record of its
   * session, by what the claim answered. A record that is being released
   * refuses every attach: its state is being removed.
   */
  /**
   * How long a poll waits for a frame, in milliseconds: the setting, but never
   * more than half the idle timeout, so that a quiet page makes progress at
   * least twice in every idle period, and within 100 ms and 10 minutes, so
   * that the client's deadline is a sound timer. The page configuration names
   * the same number (`lpw`).
   */
  def pollWaitMillis(settingMillis: Long, idleTimeoutMillis: Long): Long =
    math.max(100L, math.min(600000L, math.min(settingMillis, idleTimeoutMillis / 2)))

  def goesOn(transport: Transport, claim: SessionRegistryMachine.FreshClaim): Boolean = transport match {
    case Transport.Poll      => claim == SessionRegistryMachine.FreshClaim.Fresh
    case Transport.WebSocket => claim != SessionRegistryMachine.FreshClaim.Releasing
  }

  /** What an attach may do with the view of its session. */
  enum Output {

    /** This attach built the view. Its output is this attach's, and nobody else's. */
    case Owns

    /** The page must reload: a poll never joins a view that it did not build. */
    case Reload

    /** A WebSocket that comes after a poll built the view. It is closed and gets nothing. */
    case Refused

    /** A WebSocket that comes while another WebSocket owns the view: as before, they share it. */
    case Shared
  }

  /**
   * Who may have the output of a view.
   *
   * @param owner
   *   the transport of the attach that built the view
   * @param built
   *   whether this attach built it
   * @param requester
   *   the transport of this attach
   */
  def output(owner: Transport, built: Boolean, requester: Transport): Output =
    if (built) Output.Owns
    else
      (requester, owner) match {
        case (Transport.Poll, _)                   => Output.Reload
        case (Transport.WebSocket, Transport.Poll) => Output.Refused
        case (Transport.WebSocket, Transport.WebSocket) => Output.Shared
      }
}
