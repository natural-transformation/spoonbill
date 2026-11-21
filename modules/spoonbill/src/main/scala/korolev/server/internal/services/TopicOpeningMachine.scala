package spoonbill.server.internal.services

/**
 * Which long-polling topic a request may use.
 *
 * A page builds a new bridge every time its connection opens, and it numbers
 * these openings. The server has no connection that closes with long polling,
 * so the number is how it learns that the page started over. Each topic serves
 * one opening. A request of an older client names no opening and uses the
 * topic that the session has.
 */
private[services] object TopicOpeningMachine {
  enum Decision {

    /** The session has no topic. Make one for this request. */
    case Create

    /** The request belongs to the opening that the topic serves. */
    case Use

    /** The page opened its connection anew. The topic and its view are over. */
    case Replace

    /** The request belongs to an opening that the page has left behind. */
    case Stale
  }

  /** The opening of a topic that a request without a number made. Pages count from one. */
  val Unnumbered: Long = 0L

  /**
   * @param serving
   *   the opening of the session's topic, if it has a topic
   * @param requested
   *   the opening that the request names, if it names one
   */
  def decide(serving: Option[Long], requested: Option[Long]): Decision = (serving, requested) match {
    case (None, _)                                     => Decision.Create
    case (Some(_), None)                               => Decision.Use
    case (Some(topic), Some(opening)) if opening > topic => Decision.Replace
    case (Some(topic), Some(opening)) if opening < topic => Decision.Stale
    case _                                             => Decision.Use
  }

  /**
   * The opening that a request names. Only a plain positive number counts; with
   * anything else the request is served like one of an older client.
   */
  def parse(raw: Option[String]): Option[Long] =
    raw
      .filter(text => text.nonEmpty && text.length <= 18 && text.forall(digit => digit >= '0' && digit <= '9'))
      .map(_.toLong)
      .filter(_ > Unnumbered)
}
