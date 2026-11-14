package spoonbill

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.testExecution.defaultExecutor

/** The two handler builders and the named constructors. */
class ExtensionHandlersSpec extends AnyFlatSpec with Matchers {

  private given Effect[Future] = Effect.futureEffect

  private def await[A](future: Future[A]): A = Await.result(future, 2.seconds)

  "Handlers.apply" should "accept a stored function value" in {
    var seen = Vector.empty[String]
    // A value of a function type, not a function literal.
    val onState: Int => Future[Unit] = state => { seen :+= s"state $state"; Future.unit }
    val handlers                     = Extension.Handlers[Future, Int, String](onState = onState)
    await(handlers.onState(1))
    seen shouldBe Vector("state 1")
  }

  it should "do nothing for a callback that is left out" in {
    val handlers = Extension.Handlers[Future, Int, String]()
    await(handlers.onState(1))
    await(handlers.onMessage("message"))
    await(handlers.onDestroy(2))
  }

  it should "infer its types inside a for comprehension" in {
    def setup: Future[Extension.Handlers[Future, Int, String]] =
      for (_ <- Future.unit) yield Extension.Handlers(onState = _ => Future.unit, onDestroy = _ => Future.unit)
    await(await(setup).onState(1))
  }

  "Handlers.partial" should "ignore a state and a message that are not matched" in {
    var seen = Vector.empty[String]
    val handlers = Extension.Handlers.partial[Future, Int, String](
      onState = { case 1 => seen :+= "state 1"; Future.unit },
      onMessage = { case "hello" => seen :+= "hello"; Future.unit },
      onDestroy = last => { seen :+= s"destroy $last"; Future.unit }
    )
    await(handlers.onState(1))
    await(handlers.onState(2))
    await(handlers.onMessage("hello"))
    await(handlers.onMessage("other"))
    await(handlers.onDestroy(3))
    seen shouldBe Vector("state 1", "hello", "destroy 3")
  }

  it should "do nothing when every callback is left out" in {
    val handlers = Extension.Handlers.partial[Future, Int, String]()
    await(handlers.onState(1))
    await(handlers.onMessage("message"))
    await(handlers.onDestroy(2))
  }

  "The constructors" should "name the extension" in {
    Extension[Future, Int, String]("plain")(_ => Future.failed(new IllegalStateException)).name shouldBe "plain"
    Extension.pure[Future, Int, String]("pure")(_ => Extension.Handlers[Future, Int, String]()).name shouldBe "pure"
    Extension
      .managed[Future, Int, String, Unit]("managed")(_ => Future.unit, _ => Future.unit) { (_, _) =>
        Extension.Handlers[Future, Int, String]()
      }
      .name shouldBe "managed"
  }

  "Extension.pure" should "turn a thrown exception into a failed setup" in {
    val failure   = new IllegalStateException("synthetic")
    val extension = Extension.pure[Future, Int, String]("throwing")(_ => throw failure)
    await(extension.setup(null).failed) shouldBe failure
  }
}
