package spoonbill.effect

/**
 * A partition cannot retain another element for this rack. No payload is
 * retained in the error.
 */
final case class StreamSortOverflowException(rack: Int, capacity: Int)
    extends RuntimeException(s"Stream partition rack $rack exceeded capacity $capacity")
