package example.contract

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeCheckErrors

/**
 * The guard contract must also be implementable outside the spoonbill package.
 */
final class SessionInitializationContractSpec extends AnyFlatSpec with Matchers {
  "Session initialization" should "allow external guards to explicitly retain or reconstruct presentation" in {
    typeCheckErrors("""
      import scala.concurrent.Future
      import spoonbill.server.*
      new SessionGuard[Future, String] {
        val applicationStateInitialization = ApplicationStateInitialization.Retained[Future]()
        def authorize(state: String) = Future.successful(())
        def connected(state: String) = Future.successful(state)
        def close() = Future.successful(())
      }
    """) shouldBe empty
    typeCheckErrors("""
      import scala.concurrent.Future
      import spoonbill.server.*
      new SessionGuard[Future, String] {
        val applicationStateInitialization = ApplicationStateInitialization.Reconstructed[Future](
          () => Future.successful(())
        )
        def authorize(state: String) = Future.successful(())
        def connected(state: String) = Future.successful(state)
        def close() = Future.successful(())
      }
    """) shouldBe empty
  }

  it should "reject missing initialization decisions and incomplete or eager reconstruction acknowledgements" in {
    typeCheckErrors("""
      import scala.concurrent.Future
      import spoonbill.server.*
      new SessionGuard[Future, String] {
        def authorize(state: String) = Future.successful(())
        def connected(state: String) = Future.successful(state)
        def close() = Future.successful(())
      }
    """) should not be empty
    typeCheckErrors("""
      spoonbill.server.ApplicationStateInitialization.Reconstructed[scala.concurrent.Future]()
    """) should not be empty
    typeCheckErrors("""
      spoonbill.server.ApplicationStateInitialization.Reconstructed[scala.concurrent.Future](
        scala.concurrent.Future.successful(())
      )
    """) should not be empty
    typeCheckErrors("""
      spoonbill.server.ApplicationStateInitialization.Retained[scala.concurrent.Future](
        () => scala.concurrent.Future.successful(())
      )
    """) should not be empty
  }
}
