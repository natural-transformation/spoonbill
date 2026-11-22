package spoonbill.server.internal.services

import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PollAdmissionMachineSpec extends AnyFlatSpec with Matchers {
  import PollAdmissionMachine.*
  import SessionRegistryMachine.FreshClaim

  private val pageText = "11111111-1111-4111-8111-111111111111"
  private val page     = UUID.fromString(pageText)
  private val other    = UUID.fromString("22222222-2222-4222-8222-222222222222")

  "A page id" should "be a UUID in its canonical form" in {
    PollAdmissionMachine.page(pageText) shouldBe Some(page)
    // The view compares the page id with its connection id in lower case, as
    // the client writes it: an id in upper case would attach and then have
    // every event refused, so admission refuses it at once.
    val lettered = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    PollAdmissionMachine.page(lettered) shouldBe Some(UUID.fromString(lettered))
    PollAdmissionMachine.page(lettered.toUpperCase) shouldBe None
    // java.util.UUID would take these. A page id does not.
    for (value <- List("1-1-1-1-1", "", "11111111111141118111111111111111", pageText + "0", " " + pageText))
      withClue(value)(PollAdmissionMachine.page(value) shouldBe None)
  }

  "A cursor" should "be a count without sign or leading zeros, up to what a page can count" in {
    seen("0") shouldBe Some(0L)
    seen("17") shouldBe Some(17L)
    seen(maxCursor.toString) shouldBe Some(maxCursor)
    for (value <- List("", "-1", "+1", "01", "1.0", "1e3", " 1", (maxCursor + 1L).toString, "99999999999999999"))
      withClue(value)(seen(value) shouldBe None)
  }

  "A request" should "have a cursor only with a well formed page id and count" in {
    cursor(Some(pageText), Some("3")) shouldBe Some(Cursor(page, 3L))
    cursor(None, Some("3")) shouldBe None
    cursor(Some(pageText), None) shouldBe None
    cursor(Some("not a page"), Some("3")) shouldBe None
    cursor(Some(pageText), Some("x")) shouldBe None
  }

  "The route of a poll" should "follow the table: which poll builds a view" in {
    val rows = List(
      (None, None, Route.Reload),                           // no cursor
      (None, Some(page), Route.Reload),                     // no cursor, any entry
      (Some(Cursor(page, 0L)), Some(page), Route.Deliver),  // the entry of this page
      (Some(Cursor(page, 5L)), Some(page), Route.Deliver),  // any cursor
      (Some(Cursor(page, 0L)), Some(other), Route.Reload),  // the entry of another page
      (Some(Cursor(page, 0L)), None, Route.FirstAttach),    // no entry, nothing applied
      (Some(Cursor(page, 1L)), None, Route.Reload)          // no entry, the page has applied frames
    )
    for ((request, entry, expected) <- rows) withClue((request, entry))(route(request, entry) shouldBe expected)
  }

  "A message" should "go to the entry of its own page only" in {
    accepts(Some(Cursor(page, 0L)), Some(page)) shouldBe true
    accepts(Some(Cursor(page, 9L)), Some(page)) shouldBe true
    accepts(Some(Cursor(page, 0L)), Some(other)) shouldBe false
    accepts(Some(Cursor(page, 0L)), None) shouldBe false
    accepts(None, Some(page)) shouldBe false
  }

  "A claim of the fresh-page record" should "let a poll go on only when the record is fresh" in {
    for (claim <- FreshClaim.values)
      withClue(claim)(goesOn(Transport.Poll, claim) shouldBe (claim == FreshClaim.Fresh))
    takesExpired(Transport.Poll) shouldBe false
  }

  it should "let a WebSocket go on unless the state is being removed, and take an expired record" in {
    for (claim <- FreshClaim.values)
      withClue(claim)(goesOn(Transport.WebSocket, claim) shouldBe (claim != FreshClaim.Releasing))
    takesExpired(Transport.WebSocket) shouldBe true
  }

  "The output of a view" should "belong to the attach that built it, and to nobody else that comes later" in {
    for (owner <- Transport.values; requester <- Transport.values)
      withClue((owner, requester))(output(owner, built = true, requester) shouldBe Output.Owns)
    output(Transport.Poll, built = false, Transport.Poll) shouldBe Output.Reload
    output(Transport.WebSocket, built = false, Transport.Poll) shouldBe Output.Reload
    output(Transport.Poll, built = false, Transport.WebSocket) shouldBe Output.Refused
    // Not changed: two WebSockets of one session share a view, as before.
    output(Transport.WebSocket, built = false, Transport.WebSocket) shouldBe Output.Shared
  }

  "The wait of a poll" should "be its setting, at most half the idle timeout, and a sound timer" in {
    // (setting, idle timeout) -> wait, in milliseconds
    val table = List(
      (15000L, 60000L)                  -> 15000L, // the defaults
      (15000L, 6000L)                   -> 3000L,  // a short idle timeout: half of it
      (15000L, 100L * 86400000L)        -> 15000L, // a very long idle timeout: the setting
      (100L * 86400000L, 400L * 86400000L) -> 600000L, // never more than 10 minutes
      (0L, 60000L)                      -> 100L,   // never less than 100 ms
      (15000L, 100L)                    -> 100L
    )
    for (((setting, idle), wait) <- table)
      withClue((setting, idle))(PollAdmissionMachine.pollWaitMillis(setting, idle) shouldBe wait)
  }
}
