package avocet

import avocet.dsl.*
import avocet.dsl.html.*
import avocet.impl.DiffRenderContext
import avocet.impl.internal.Hashing

/**
 * Fixed hash-collision witnesses for the F2 real-service renderer fixture.
 *
 * The values are copied from Avocet's test-only `HashCollisionFixtures`
 * (avocet core/shared/src/test/scala/levsha/HashCollisionFixtures.scala),
 * which is not published. This file lives in package `avocet` only so that it
 * can call the engine's package-private `Hashing` and `finalizedRootSum`. The
 * fixture therefore proves that the witnesses still collide inside the exact
 * avocet-core JAR on the classpath, instead of in a re-implementation of its
 * hash functions. No collision is searched for at run time.
 */
object RendererCollisionWitnesses {

  /** Tag and attribute names with equal `Hashing.nameHash` (String.hashCode). */
  val nameA = "Aa"
  val nameB = "BB"

  /** Text, attribute and style values with equal `Hashing.valueHash` (MurmurHash3). */
  val valueA = "t86388"
  val valueB = "t118091"

  /** Texts whose `div(text)` subtrees have equal content hashes. */
  val subtreeTextA = "s121742"
  val subtreeTextB = "s143709"

  private def rootSum(document: Document[Nothing]): Int = {
    val rc = DiffRenderContext[Nothing]()
    document(rc)
    rc.finalizeDocument()
    rc.finalizedRootSum
  }

  /** Fails when a hash change has turned a witness into an ordinary update. */
  def requireStillCollide(): Unit = {
    require(nameA != nameB && Hashing.nameHash(nameA) == Hashing.nameHash(nameB), "Name witnesses no longer collide")
    require(valueA != valueB && Hashing.valueHash(valueA) == Hashing.valueHash(valueB), "Value witnesses no longer collide")
    require(
      subtreeTextA != subtreeTextB && rootSum(div(subtreeTextA)) == rootSum(div(subtreeTextB)),
      "Subtree witnesses no longer collide"
    )
  }
}
