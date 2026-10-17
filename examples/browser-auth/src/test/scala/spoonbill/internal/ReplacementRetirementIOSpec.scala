package spoonbill.internal

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.action.InvocationBinding
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.server.SessionAccessDenied

class ReplacementRetirementIOSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given Effect[IO]       = new IO3Effect(global)
  private given Reporter         = Reporter.PrintReporter

  "Replacement retirement in IO" should "observe the invocation marker at evaluation rather than construction" in {
    val connection = ConnectionId.fromUuid(UUID.randomUUID())
    val binding    = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), connection)
    val closed     = new AtomicBoolean()
    val incoming   = Queue[IO, String]()
    val input = new Stream[IO, String] {
      def pull(): IO[Option[String]] = incoming.stream.pull()
      def cancel(): IO[Unit]         = IO(closed.set(true)).flatMap(_ => incoming.stream.cancel())
    }
    val frontend   = new Frontend[IO](input, None, Some(connection), authorize = Some(() => IO.unit))
    val retirement = frontend.retireReplacement(binding)
    try {
      closed.get() shouldBe false
      retirement.timeout(5.seconds).unsafeRunSync()
      closed.get() shouldBe false
      binding.replacementAttempted.set(true)
      closed.get() shouldBe false
      retirement.timeout(5.seconds).unsafeRunSync()
      closed.get() shouldBe true
      intercept[SessionAccessDenied](frontend.authorizeInteraction().unsafeRunSync())
    } finally frontend.close().timeout(5.seconds).unsafeRunSync()
  }
}
