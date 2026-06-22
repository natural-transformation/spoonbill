package spoonbill

import spoonbill.state.{StateManager, StateSerializer, StateStorage}
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.*
import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

class StateStorageBuilderSpec extends AnyFlatSpec with Matchers {

  private def await[A](future: Future[A]): A =
    Await.result(future, 2.seconds)

  "StateManager.cached" should "store typed values and invoke write and delete hooks" in {
    val writes = TrieMap.empty[Id, Any]
    val deletes = TrieMap.empty[Id, Boolean]
    val nodeId = Id("1_2")
    val stateManager = StateManager.cached[Future](
      initial = Map(Id.TopLevel -> "initial"),
      onWrite = (id, value) => Future.successful(writes.put(id, value)).map(_ => ()),
      onDelete = id => Future.successful(deletes.put(id, true)).map(_ => ())
    )

    await(stateManager.read[String](Id.TopLevel)) shouldBe Some("initial")

    await(stateManager.write(nodeId, "updated"))
    await(stateManager.read[String](nodeId)) shouldBe Some("updated")
    await(stateManager.snapshot).apply[String](nodeId) shouldBe Some("updated")
    writes(nodeId) shouldBe "updated"

    await(stateManager.delete(nodeId))
    await(stateManager.read[String](nodeId)) shouldBe None
    deletes(nodeId) shouldBe true
  }

  "StateManager.serialized" should "store serialized values and invoke persistence hooks" in {
    val writes = TrieMap.empty[Id, Array[Byte]]
    val nodeId = Id("1_3")
    val stateManager = StateManager.serialized[Future](
      onWrite = (id, bytes) => Future.successful(writes.put(id, bytes)).map(_ => ())
    )

    await(stateManager.write(nodeId, "stored"))

    await(stateManager.read[String](nodeId)) shouldBe Some("stored")
    implicitly[spoonbill.state.StateDeserializer[String]].deserialize(writes(nodeId)) shouldBe Some("stored")
  }

  "StateStorage.fromRepository" should "create and restore repository-backed state managers" in {
    val repository = TrieMap.empty[String, TrieMap[Id, Array[Byte]]]
    val removed = TrieMap.empty[String, Boolean]

    val storage = StateStorage.fromRepository[Future, String](
      repositoryExists = key => Future.successful(repository.contains(key)),
      loadSnapshot = key => Future.successful(repository.getOrElse(key, TrieMap.empty).toMap),
      createSnapshot = (key, snapshot) =>
        Future.successful {
          repository.put(key, TrieMap.from(snapshot.view.mapValues(_.clone()).toMap))
          ()
        },
      writeNode = (key, nodeId, bytes) =>
        Future.successful {
          repository.getOrElseUpdate(key, TrieMap.empty).put(nodeId, bytes.clone())
          ()
        },
      deleteNode = (key, nodeId) =>
        Future.successful {
          repository.getOrElseUpdate(key, TrieMap.empty).remove(nodeId)
          ()
        },
      removeRepository = key => Future.successful {
        repository.remove(key)
        removed.put(key, true)
        ()
      }
    )

    await(storage.exists("device", "session")) shouldBe false

    val created = await(storage.create("device", "session", "top-level"))
    await(created.read[String](Id.TopLevel)) shouldBe Some("top-level")
    await(storage.exists("device", "session")) shouldBe true

    val child = Id("1_4")
    await(created.write(child, "child-state"))
    await(created.delete(child))
    repository("device-session").contains(child) shouldBe false

    val restored = await(storage.get("device", "session"))
    await(restored.read[String](Id.TopLevel)) shouldBe Some("top-level")

    storage.remove("device", "session")
    repository.contains("device-session") shouldBe false
    removed("device-session") shouldBe true
  }
}
