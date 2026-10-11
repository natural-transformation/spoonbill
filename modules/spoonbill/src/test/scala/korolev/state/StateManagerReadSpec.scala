package spoonbill.state

import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.state.javaSerialization.*

class StateManagerReadSpec extends AnyFlatSpec with Matchers {
  private def await[A](value: Future[A]): A = Await.result(value, 2.seconds)

  "Cached node reads" should "observe current values while explicit snapshots retain the previous immutable state" in {
    val node     = Id("1_2")
    val manager  = StateManager.cached[Future](Map(node -> Vector(1, 2)))
    val snapshot = await(manager.snapshot)
    await(manager.write(node, Vector(3, 4)))
    await(manager.read[Vector[Int]](node)) shouldBe Some(Vector(3, 4))
    snapshot.apply[Vector[Int]](node) shouldBe Some(Vector(1, 2))
    await(manager.delete(node))
    await(manager.read[Vector[Int]](node)) shouldBe None
    snapshot.apply[Vector[Int]](node) shouldBe Some(Vector(1, 2))
  }

  "Serialized node reads" should "isolate retained bytes from input, hooks, and a mutating deserializer" in {
    final case class Value(bytes: Array[Byte])
    given StateSerializer[Value] with {
      def serialize(value: Value): Array[Byte] = value.bytes
    }
    given StateDeserializer[Value] with {
      def deserialize(bytes: Array[Byte]): Option[Value] = {
        val original = bytes.clone()
        java.util.Arrays.fill(bytes, 99.toByte)
        Some(Value(original))
      }
    }
    val node    = Id("1_3")
    val other   = Id("1_4")
    val initial = Array[Byte](1, 2)
    val manager = StateManager.serialized[Future](
      Map(node -> initial, other -> Array[Byte](7)),
      onWrite = (_, bytes) => { java.util.Arrays.fill(bytes, 88.toByte); Future.unit }
    )
    initial(0) = 42
    val snapshot = await(manager.snapshot)
    await(manager.read[Value](node)).get.bytes.toVector shouldBe Vector[Byte](1, 2)
    await(manager.read[Value](node)).get.bytes.toVector shouldBe Vector[Byte](1, 2)
    val replacement = Array[Byte](3, 4)
    await(manager.write(node, Value(replacement)))
    replacement(0) = 55
    await(manager.read[Value](node)).get.bytes.toVector shouldBe Vector[Byte](3, 4)
    await(manager.read[Value](other)).get.bytes.toVector shouldBe Vector[Byte](7)
    snapshot.apply[Value](node).get.bytes.toVector shouldBe Vector[Byte](1, 2)
    snapshot.apply[Value](node).get.bytes.toVector shouldBe Vector[Byte](1, 2)
    await(manager.delete(node))
    await(manager.read[Value](node)) shouldBe None
  }
}
