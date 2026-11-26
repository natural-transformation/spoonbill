package bench

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.sql.Connection
import scala.util.Using

/** One statement-log entry: its backend, its application name and its message. */
final case class LogEntry(pid: String, application: String, message: String) {

  /** The executed statement's text for a statement entry, None for any other message. */
  def statement: Option[String] =
    if (message.startsWith("LOG:  execute ")) {
      // "execute <unnamed>: SQL" or "execute S_1: SQL" for a server-prepared statement.
      val rest  = message.stripPrefix("LOG:  execute ")
      val colon = rest.indexOf(':')
      Some(if (colon < 0) "" else rest.substring(colon + 1).trim)
    } else if (message.startsWith("LOG:  statement:")) Some(message.stripPrefix("LOG:  statement:").trim)
    else None

  def failed: Boolean = message.startsWith("ERROR:") || message.startsWith("FATAL:")
}

/**
 * The disposable PostgreSQL instance's statement log, which carries every
 * executed statement as "spoonbill-log|<pid>|<application>|<message>"; a
 * statement's continuation lines have no prefix. A control connection with its
 * own application name writes markers. A backend writes each entry before it
 * runs the statement, and a marker is written only after the bracketed call has
 * settled, so the statements of that call are the entries between its markers.
 */
final class SqlProfile(log: Path, control: Connection) {
  private def literal(label: String): String = s"'bench-marker:$label'"

  def mark(label: String): Unit = {
    require(label.forall(character => character.isLetterOrDigit || character == ':' || character == '-'))
    Using.resource(control.createStatement())(_.execute(s"SELECT ${literal(label)}")); ()
  }

  /** Wait until the marker is in the log file; false when it never arrives. */
  def awaitMarker(label: String, timeoutMillis: Long = 10000): Boolean = {
    val bound = System.nanoTime() + timeoutMillis * 1000000L
    def present = Files.readString(log, UTF_8).contains(literal(label))
    while (!present && System.nanoTime() < bound) Thread.sleep(20)
    present
  }

  def entries(): Vector[LogEntry] = SqlProfile.parse(Files.readString(log, UTF_8))

  /** The work and clock entries strictly between a begin and an end marker; None when either is missing. */
  def window(entries: Vector[LogEntry], begin: String, end: String): Option[Vector[LogEntry]] = {
    def at(label: String) = entries.indexWhere(entry =>
      entry.application == SharedPools.ControlApplication && entry.statement.exists(_.contains(literal(label)))
    )
    val (from, to) = (at(begin), at(end))
    Option.when(from >= 0 && to > from)(
      entries.slice(from + 1, to).filter(entry =>
        entry.application == SharedPools.WorkApplication || entry.application == SharedPools.ClockApplication
      )
    )
  }
}

object SqlProfile {
  private val Prefix = "spoonbill-log|"

  def parse(text: String): Vector[LogEntry] = {
    val result  = Vector.newBuilder[LogEntry]
    var current = Option.empty[(String, String, StringBuilder)]
    def flush(): Unit = current.foreach { case (pid, application, message) =>
      result += LogEntry(pid, application, message.toString)
    }
    text.linesIterator.foreach { line =>
      if (line.startsWith(Prefix)) {
        flush()
        val parts = line.split("\\|", 4)
        current = Some((parts(1), parts(2), new StringBuilder(if (parts.length > 3) parts(3) else "")))
      } else current.foreach(_._3.append('\n').append(line))
    }
    flush()
    result.result()
  }
}

/** One executed statement of an observation: the pool that ran it, its kind and its shape. */
final case class Classified(role: String, kind: String, shape: String)

object Classified {
  def apply(entry: LogEntry, sql: String): Classified = {
    val role = if (entry.application == SharedPools.ClockApplication) "clock" else "work"
    Classified(role, kind(sql), shape(sql))
  }

  /** Driver-issued transaction and isolation statements, the host's statements, the clock's and the rest. */
  def kind(sql: String): String = {
    val upper = sql.trim.toUpperCase
    if (upper.startsWith("SET SESSION CHARACTERISTICS")) "isolationSet"
    else if (upper.startsWith("SHOW TRANSACTION ISOLATION")) "isolationShow"
    else if (upper == "BEGIN") "begin"
    else if (upper == "COMMIT") "commit"
    else if (upper == "ROLLBACK") "rollback"
    else if (sql.contains("bench-host-policy")) "hostPolicyLock"
    else if (sql.contains("bench_host_session")) "hostRead"
    else if (sql.contains("spoonbill_auth_clock")) "clock"
    else "framework"
  }

  /** Whitespace collapsed and long statements shortened, so that equal statements compare equal. */
  def shape(sql: String): String = {
    val collapsed = sql.replaceAll("\\s+", " ").trim
    if (collapsed.length > 160) collapsed.take(157) + "..." else collapsed
  }
}

/** The statements of one observation's window, with what is excluded from its count. */
final case class ObservationCounts(statements: Vector[Classified], pings: Int, errors: Int) {
  private def kinds(kind: String): Int = statements.count(_.kind == kind)
  def total: Int                       = statements.size
  def work: Int                        = statements.count(_.role == "work")
  def clock: Int                       = statements.count(_.role == "clock")
  def framework: Int                   = statements.count(entry => entry.role == "work" && entry.kind == "framework")
  def hostResolves: Int                = kinds("hostRead")
  def hostPolicyLocks: Int             = kinds("hostPolicyLock")
  def clockCommits: Int                = statements.count(entry => entry.role == "clock" && entry.kind == "commit")
  def isolationSets: Int               = kinds("isolationSet")
  def isolationShows: Int              = kinds("isolationShow")
  def rollbacks: Int                   = kinds("rollback")

  def metrics: Vector[(String, Int)] = Vector(
    "statements" -> total, "work" -> work, "clock" -> clock, "framework" -> framework,
    "hostResolves" -> hostResolves, "hostPolicyLocks" -> hostPolicyLocks, "clockCommits" -> clockCommits,
    "isolationSets" -> isolationSets, "isolationShows" -> isolationShows, "rollbacks" -> rollbacks,
    "pings" -> pings, "errors" -> errors
  )
}

object ObservationCounts {

  /** Empty statements are connection validation pings, not work of the observation. */
  def of(window: Vector[LogEntry]): ObservationCounts = {
    val executed = window.flatMap(entry => entry.statement.map(entry -> _))
    val (pings, statements) = executed.partition(_._2.isEmpty)
    ObservationCounts(statements.map { case (entry, sql) => Classified(entry, sql) }, pings.size, window.count(_.failed))
  }
}

/**
 * The statement budget of one successful observation of an active view:
 * eight framework statements (realm lock, retention row, token row twice,
 * slot, view and two last-seen renewals), the host's statements for each
 * resolution, the provider transaction's BEGIN and COMMIT, one isolation SET
 * for every transaction on a pool that is not certified to establish read
 * committed on checkout, and the independently committed clock transactions.
 */
final case class ObservationBudget(
  resolves: Int,
  clockTransactions: Int,
  clockStatements: Int,
  workCertified: Boolean,
  clockCertified: Boolean,
  policyLock: Boolean,
  sharedLock: Boolean = false
) {
  // A check under the shared realm lock skips the retention row, which only enrollment needs.
  val framework: Int     = if (sharedLock) 7 else 8
  def hostStatements: Int = resolves * (if (policyLock) 2 else 1)
  def isolationSets: Int = (if (workCertified) 0 else 1) + (if (clockCertified) 0 else clockTransactions)
  def statements: Int    = framework + hostStatements + 2 + isolationSets + clockTransactions * clockStatements

  def json: String = BenchJson.obj(
    "resolves" -> resolves.toString, "clockTransactions" -> clockTransactions.toString,
    "clockStatementsPerTransaction" -> clockStatements.toString, "framework" -> framework.toString,
    "hostStatements" -> hostStatements.toString, "isolationSets" -> isolationSets.toString,
    "statements" -> statements.toString
  )
}

object ObservationBudget {

  /**
   * The expected cost for the selected switches. The legacy host is resolved
   * twice and every time sample commits its own clock checkpoint (BEGIN,
   * INSERT … ON CONFLICT … RETURNING, COMMIT), five per observation. A host
   * that holds its resolution until settlement is resolved once, with one
   * row-locked checkpoint (BEGIN, SELECT … FOR UPDATE, UPDATE … RETURNING, COMMIT).
   */
  def of(switches: SharedSwitches): ObservationBudget = switches.host match {
    case "held" =>
      ObservationBudget(1, 1, 4, switches.workCertified, switches.clockCertified, switches.globalLock, switches.cap > 0)
    case _      => ObservationBudget(2, 5, 3, switches.workCertified, switches.clockCertified, switches.globalLock)
  }
}
