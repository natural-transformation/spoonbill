package spoonbill

import java.util.concurrent.atomic.AtomicInteger

import spoonbill.effect.Effect
import spoonbill.testExecution.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

class ManagedExtensionSpec extends AnyFlatSpec with Matchers {

  private def await[A](future: Future[A]): A =
    Await.result(future, 2.seconds)

  "Extension.managed" should "release a resource once when destroyed more than once" in {
    val releases = new AtomicInteger(0)
    val extension = Extension.managed[Future, String, Any, String](
      acquire = _ => Future.successful("resource"),
      release = _ => Future.successful(releases.incrementAndGet()).map(_ => ())
    ) { (_, _) =>
      Extension.Handlers[Future, String, Any]()
    }

    val handlers = await(extension.setup(null))

    await(handlers.onDestroy())
    await(handlers.onDestroy())

    releases.get() shouldBe 1
  }

  it should "release a resource when handler setup fails after acquisition" in {
    val releases = new AtomicInteger(0)
    val extension = Extension.managedF[Future, String, Any, String](
      acquire = _ => Future.successful("resource"),
      release = _ => Future.successful(releases.incrementAndGet()).map(_ => ())
    ) { (_, _) =>
      Future.failed(new RuntimeException("boom"))
    }

    an[RuntimeException] shouldBe thrownBy {
      await(extension.setup(null))
    }
    releases.get() shouldBe 1
  }

  it should "release a resource when a wrapped onDestroy handler fails" in {
    val releases = new AtomicInteger(0)
    val extension = Extension.managed[Future, String, Any, String](
      acquire = _ => Future.successful("resource"),
      release = _ => Future.successful(releases.incrementAndGet()).map(_ => ())
    ) { (_, _) =>
      Extension.Handlers[Future, String, Any](
        onDestroy = () => Effect[Future].fail(new RuntimeException("destroy failed"))
      )
    }

    val handlers = await(extension.setup(null))

    an[RuntimeException] shouldBe thrownBy {
      await(handlers.onDestroy())
    }
    releases.get() shouldBe 1
  }
}
