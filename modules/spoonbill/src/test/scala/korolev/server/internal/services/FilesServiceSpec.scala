package spoonbill.server.internal.services

import spoonbill.effect.Effect
import spoonbill.web.{Headers, PathAndQuery}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FilesServiceSpec extends AnyFlatSpec with Matchers {

  "resourceFromClasspath" should "disable cache for Spoonbill client JS assets" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val service                         = new FilesService[Future](new CommonService[Future]())

    val responseF = service.resourceFromClasspath(PathAndQuery.fromString("/static/spoonbill-client.min.js"))
    val response  = Await.result(responseF, 3.seconds)

    response.headers should contain(Headers.CacheControlNoCache)
    response.headers should contain(Headers.PragmaNoCache)
  }

  it should "leave cache headers unchanged for other JS assets" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val service                         = new FilesService[Future](new CommonService[Future]())

    val responseF = service.resourceFromClasspath(PathAndQuery.fromString("/static/test.js"))
    val response  = Await.result(responseF, 3.seconds)

    response.headers should not contain Headers.CacheControlNoCache
    response.headers should not contain Headers.PragmaNoCache
  }
}
