package spoonbill.security.jdbc

import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.security.jdbc.JdbcHeldAuthority.HostDeadlines

/** The pure rules of a held shared-view check: one instant decides every time rule. */
class JdbcHeldAuthoritySpec extends AnyFlatSpec with Matchers {
  private val at      = Instant.parse("2026-11-25T12:00:00Z")
  private val far     = at.plusSeconds(3600)
  private val open    = HostDeadlines(far, far)
  private val session = Right("active")

  "A held check" should "fail every deadline at equality and pass one nanosecond before it" in {
    JdbcHeldAuthority.decide(at, open, session, at) shouldBe Left(JdbcAuthError.Expired)
    JdbcHeldAuthority.decide(at.plusNanos(1), open, session, at) shouldBe session
    JdbcHeldAuthority.decide(far, HostDeadlines(at, far), session, at) shouldBe Left(JdbcAuthError.HostDenied)
    JdbcHeldAuthority.decide(far, HostDeadlines(at.plusNanos(1), far), session, at) shouldBe session
    JdbcHeldAuthority.decide(far, HostDeadlines(far, at), session, at) shouldBe Left(JdbcAuthError.HostDenied)
    JdbcHeldAuthority.decide(far, HostDeadlines(far, at.plusNanos(1)), session, at) shouldBe session
  }

  it should "report expiry before a host denial, and a host denial before a structural failure" in {
    val missing = Left(JdbcAuthError.NotFound)
    JdbcHeldAuthority.decide(at, HostDeadlines(at, at), missing, at) shouldBe Left(JdbcAuthError.Expired)
    JdbcHeldAuthority.decide(far, HostDeadlines(at, far), missing, at) shouldBe Left(JdbcAuthError.HostDenied)
    JdbcHeldAuthority.decide(far, open, missing, at) shouldBe missing
  }

  it should "let only session expiry outrank a stable host denial" in {
    JdbcHeldAuthority.denied(at, at) shouldBe JdbcAuthError.Expired
    JdbcHeldAuthority.denied(at.plusNanos(1), at) shouldBe JdbcAuthError.HostDenied
  }

  it should "decide a binding mismatch and a revoked token row without an instant, binding first" in {
    JdbcHeldAuthority.untimed(bindingMatches = false, revoked = true) shouldBe Some(JdbcAuthError.BindingMismatch)
    JdbcHeldAuthority.untimed(bindingMatches = true, revoked = true) shouldBe Some(JdbcAuthError.Revoked)
    JdbcHeldAuthority.untimed(bindingMatches = true, revoked = false) shouldBe None
  }

  "A shared checkpoint" should "take the later of the stored floor and the process floor that holds the wall sample" in {
    val (early, late) = (at, at.plusNanos(1))
    JdbcClockMachine.sharedCheckpoint(late, early) shouldBe late
    JdbcClockMachine.sharedCheckpoint(early, late) shouldBe late
    JdbcClockMachine.sharedCheckpoint(at, at) shouldBe at
  }
}
