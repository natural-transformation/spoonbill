package bench

/**
 * A minimal JSON writer for the harness reports. Values are rendered JSON
 * text; `obj` and `arr` assemble them, `str` escapes text.
 */
object BenchJson {
  def str(value: String): String = {
    val out = new StringBuilder("\"")
    value.foreach {
      case '"'                    => out.append("\\\"")
      case '\\'                   => out.append("\\\\")
      case '\n'                   => out.append("\\n")
      case '\r'                   => out.append("\\r")
      case '\t'                   => out.append("\\t")
      case control if control < ' ' => out.append(f"\\u${control.toInt}%04x")
      case other                  => out.append(other)
    }
    out.append('"').toString
  }

  def obj(fields: (String, String)*): String =
    fields.map { case (name, value) => s"${str(name)}:$value" }.mkString("{", ",", "}")

  def arr(values: Iterable[String]): String = values.mkString("[", ",", "]")

  def bool(value: Boolean): String = if (value) "true" else "false"

  /** A number rounded to three decimals, as plain JSON. */
  def num(value: Double): String = BigDecimal(value).setScale(3, BigDecimal.RoundingMode.HALF_UP).toString
}
