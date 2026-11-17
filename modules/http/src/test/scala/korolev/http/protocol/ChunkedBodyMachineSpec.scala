package spoonbill.http.protocol

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.data.Bytes
import spoonbill.effect.Decoder.Action

final class ChunkedBodyMachineSpec extends AnyFlatSpec with Matchers {
  import ChunkedBodyDecoder.*
  private def bytes(value: String): Bytes       = Bytes.wrap(value.getBytes("ISO-8859-1"))
  private def scan(state: State, input: String) = decode(state, bytes(input), 32, 32)

  "Chunk framing" should "replay an immutable prefix into independent payload branches" in {
    val (prefix, more) = scan(initial, "3;foo=\"x\"")
    more shouldBe Action.TakeNext
    val (left, leftAction)   = scan(prefix, "\r\nab")
    val (right, rightAction) = scan(prefix, "\r\nxyz")
    left.phase shouldBe Phase.Payload
    left.remaining shouldBe 1L
    leftAction shouldBe Action.Fork(bytes("ab"), Bytes.empty)
    right.phase shouldBe Phase.PayloadCR
    rightAction shouldBe Action.Fork(bytes("xyz"), Bytes.empty)
    prefix.line shouldBe "3;foo=\"x\""
    initial.phase shouldBe Phase.Size
  }

  it should "leave every empty-input state unchanged without a framing event" in {
    val states = Seq(
      initial,
      State(Phase.Payload, 64L),
      State(Phase.Size, line = "4", lineBytes = 1),
      State(Phase.Trailers, line = "X:", lineBytes = 2, trailerBytes = 2),
      completed
    )
    states.foreach { state =>
      val (next, action) = decode(state, Bytes.empty, 32, 32)
      next should be theSameInstanceAs state
      action shouldBe Action.TakeNext
    }
  }

  it should "retire a carried trailer prefix when its LF arrives alone" in {
    val (prefix, _) = scan(initial, "0\r\nX: y\r")
    prefix.line shouldBe "X: y"
    val (next, action) = scan(prefix, "\n")
    action shouldBe Action.TakeNext
    next.line shouldBe ""
    next.lineBytes shouldBe 0
    next.lineCR shouldBe false
    val (end, tail) = scan(next, "\r\nNEXT")
    end shouldBe completed
    tail shouldBe Action.TakeBackFinish(bytes("NEXT"))
    prefix.line shouldBe "X: y"
  }

  it should "keep payload, delimiters, completion and unconsumed prefix in order" in {
    val (afterPayload, action) = scan(initial, "1\r\nx\r\n0\r\nX: y\r\n\r\nGET")
    afterPayload.phase shouldBe Phase.PayloadCR
    action shouldBe Action.Fork(bytes("x"), bytes("\r\n0\r\nX: y\r\n\r\nGET"))
    val (complete, tail) = scan(afterPayload, "\r\n0\r\nX: y\r\n\r\nGET")
    complete shouldBe completed
    tail shouldBe Action.TakeBackFinish(bytes("GET"))
  }

  it should "reject framing without mutating a saved prefix or retaining payload" in {
    val (prefix, _) = scan(initial, "1\r")
    intercept[Http11.InvalidFraming](scan(prefix, "X"))
    scan(prefix, "\nx")._1.phase shouldBe Phase.PayloadCR
    val (payload, _) = scan(initial, "7fffffffffffffff\r\nx")
    payload.remaining shouldBe Long.MaxValue - 1
    payload.line shouldBe ""
  }

  it should "match the immutable model through compact storage at every framing split" in {
    val wires = Seq(
      "3;foo=bar\r\nxyz\r\n0\r\nX: y\r\n\r\nNEXT",
      "1\r\nx\r\n0\r\n\r\nNEXT",
      "10\r\n" + "x" * 16 + "\r\n0\r\n\r\nNEXT",
      "3\r\nxyz\r\n2\r\nab\r\n00\r\n\r\nNEXT"
    )
    for (wire <- wires; split <- 1 until wire.length) {
      val cell   = new Cell[Bytes](32, 32)
      var model  = initial
      var inputs = List(bytes(wire.take(split)), bytes(wire.drop(split)))
      while (inputs.nonEmpty && model != completed) {
        val incoming = inputs.head
        inputs = inputs.tail
        val (next, expected) = decode(model, incoming, 32, 32)
        cell.decode(incoming) shouldBe expected
        cell.completed shouldBe (next == completed)
        expected match {
          case Action.Fork(_, tail) => inputs = tail :: inputs
          case _                    => ()
        }
        model = next
      }
      model shouldBe completed
    }
  }

  it should "match simple scalar spans to the checked scan without accepting extensions or excess budgets" in {
    val simpleHeaders = Seq("1", "A", "0001", "7fffffffffffffff", "0", "00")
    val phases        = Seq(Phase.Size -> "", Phase.PayloadCR -> "\r\n", Phase.PayloadLF -> "\n")
    for (header <- simpleHeaders; (phase, prefix) <- phases) {
      val payload = if (header.forall(_ == '0')) "" else "xyz"
      val input   = bytes(prefix + header + "\r\n" + payload + "\r\nNEXT")
      val fast    = simple(phase, 0, false, input, 32, 32)
      fast should not be null
      val (next, expected) = decode(State(phase), input, 32, 32)
      fast.phase shouldBe next.phase
      fast.remaining shouldBe next.remaining
      val action =
        if (fast.phase == Phase.Complete) Action.TakeBackFinish(input.slice(fast.until, input.length))
        else Action.Fork(input.slice(fast.from, fast.until), input.slice(fast.until, input.length))
      action shouldBe expected
    }
    Seq("", "1", "1\r", "1\r\n", "0\r\n", "1;x=z\r\nx", "8000000000000000\r\nx", "00000000000000001\r\nx").foreach {
      value =>
        simple(Phase.Size, 0, false, bytes(value), 32, 32) shouldBe null
    }
    simple(Phase.Size, 0, false, bytes("10\r\nx"), 3, 32) shouldBe null
    simple(Phase.Size, 0, false, bytes("0\r\n\r\n"), 32, 1) shouldBe null
  }
}
