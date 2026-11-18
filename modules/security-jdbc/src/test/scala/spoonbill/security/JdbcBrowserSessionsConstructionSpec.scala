package spoonbill.security

import org.scalatest.flatspec.AnyFlatSpec

/** Compile outside the jdbc package: managed construction is an internal capability. */
class JdbcBrowserSessionsConstructionSpec extends AnyFlatSpec {
  "JDBC session construction" should "preserve both public low-level constructor forms" in {
    assertCompiles("""
      new spoonbill.security.jdbc.JdbcBrowserSessions(
        null, "realm", "session", () => java.time.Instant.EPOCH, null)
    """)
    assertCompiles("""
      new spoonbill.security.jdbc.JdbcBrowserSessions(
        null, "realm", "session", () => java.time.Instant.EPOCH, null, maxActiveViews = 4)
    """)
  }

  it should "keep managed enrollment bypass internal" in {
    assertDoesNotCompile("""
      new spoonbill.security.jdbc.JdbcBrowserSessions(
        null, "realm", "session", () => java.time.Instant.EPOCH, null, 4, managedLifecycle = true)
    """)
  }
}
