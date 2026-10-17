package example.browserauth

import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.compiletime.testing.typeChecks
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.web.PathAndQuery

class PageRouteSpec extends AnyFlatSpec with Matchers {
  private def continuation(value: String): PublicContinuation = PublicContinuation.fromText(value)
  private def parse(value: String): Option[PageRoute]         = PageRoute.fromPath.lift(PathAndQuery.fromString(value))
  private val alice                                           = Principal(UUID.randomUUID(), "alice")

  "The page route API" should "require typed routes and continuations without independent route flags" in {
    typeChecks("""
      import example.browserauth.*
      val continuation = PublicContinuation.fromText("return")
      Seq(PageRoute.Landing, PageRoute.Protected, PageRoute.Login(continuation), PageRoute.Information(continuation))
        .map(route => Page(route = route))
    """) shouldBe true
    typeChecks("""
      example.browserauth.Page(route = "login")
    """) shouldBe false
    typeChecks("""
      example.browserauth.PageRoute.Login("return")
    """) shouldBe false
    typeChecks("""
      example.browserauth.PageRoute.Information("return")
    """) shouldBe false
    typeChecks("""
      example.browserauth.Page(protectedPage = true)
    """) shouldBe false
    typeChecks("""
      example.browserauth.Page(publicRoute = "login")
    """) shouldBe false
    typeChecks("""
      example.browserauth.Page(continuation = "return")
    """) shouldBe false
  }

  "The page route codec" should "round-trip every route including empty and escaped continuation text" in {
    val routes = Seq(
      PageRoute.Landing,
      PageRoute.Protected,
      PageRoute.Login(continuation("")),
      PageRoute.Information(continuation("")),
      PageRoute.Login(continuation("/return?q=hello world&label=résumé+done")),
      PageRoute.Information(continuation("/return?q=hello world&label=résumé+done"))
    )
    routes.foreach(route => parse(route.toPath.mkString) shouldBe Some(route))
  }

  it should "reject unknown routes and retain the established public-query behavior" in {
    Seq("/missing", "/login/extra", "/public/extra", "/protected/extra", "/?continue=x")
      .foreach(path => parse(path) shouldBe None)
    parse("/protected?continue=x") shouldBe Some(PageRoute.Protected)
    parse("/protected?continue=x").flatMap(_.continuation) shouldBe None
    parse("/login?unrelated=ignored&continue=next") shouldBe Some(PageRoute.Login(continuation("next")))
    parse("/public?unrelated=ignored") shouldBe Some(PageRoute.Information(continuation("")))
    parse("/login?continue=").map(_.toPath.mkString) shouldBe Some("/login")
    parse("/public?continue=a&continue=b") shouldBe Some(PageRoute.Information(continuation("b")))
  }

  "Application routing" should "replace the complete route without retaining a public continuation on other pages" in {
    given ExecutionContext = ExecutionContext.global
    val accounts           = new MemoryAccounts[Future]()
    try {
      val router        = new Application[Future](accounts).config.router
      val original      = Page(route = PageRoute.Login(continuation("previous")), draft = "retained draft")
      val protectedPage = Await.result(router.toState(PathAndQuery.fromString("/protected"))(original), 5.seconds)
      protectedPage.route shouldBe PageRoute.Protected
      protectedPage.route.continuation shouldBe None
      protectedPage.draft shouldBe original.draft
      Presentation.policy.allowed(protectedPage, None) shouldBe false

      val landing = Await.result(router.toState(PathAndQuery.Root)(protectedPage), 5.seconds)
      landing.route shouldBe PageRoute.Landing
      landing.route.continuation shouldBe None
      Presentation.policy.allowed(landing, None) shouldBe true

      val information = Await.result(
        router.toState(PathAndQuery.fromString("/public?continue=new"))(landing),
        5.seconds
      )
      information.route shouldBe PageRoute.Information(continuation("new"))
      information.draft shouldBe original.draft
      router.fromState(information).mkString shouldBe "/public?continue=new"
      router.toState.isDefinedAt(PathAndQuery.fromString("/unknown")) shouldBe false
    } finally Await.result(accounts.close(), 5.seconds)
  }

  "Continuation edits" should "retain the public route and leave routes without a continuation unchanged" in {
    val old    = continuation("old")
    val edited = continuation("edited")
    PageRoute.Login(old).withContinuation(edited) shouldBe PageRoute.Login(edited)
    PageRoute.Information(old).withContinuation(edited) shouldBe PageRoute.Information(edited)
    PageRoute.Landing.withContinuation(edited) shouldBe PageRoute.Landing
    PageRoute.Protected.withContinuation(edited) shouldBe PageRoute.Protected
  }

  "Presentation authority changes" should "retain anonymous edits until authority changes and then reset public state" in {
    for (route <- Seq(PageRoute.Login(continuation("return")), PageRoute.Information(continuation("return")))) {
      val anonymous = Page(route = route, draft = "unfinished", message = "edited")
      Presentation.policy.connected(anonymous, None, None) shouldBe anonymous
      val authenticated = Presentation.policy.connected(anonymous, Some(alice), None)
      authenticated shouldBe Page(account = Some("alice"))
      Presentation.policy.connected(authenticated.copy(route = route, draft = "private"), None, None) shouldBe Page()
    }
  }

  it should "preserve protected-route admission when authority is removed" in {
    val original = Page(account = Some("alice"), route = PageRoute.Protected, draft = "private")
    Presentation.policy.connected(original, Some(alice), None) shouldBe original
    val disconnected = Presentation.policy.connected(original, None, None)
    disconnected shouldBe Page(route = PageRoute.Protected)
    Presentation.policy.allowed(disconnected, None) shouldBe false
    Presentation.policy.allowed(disconnected, Some(alice)) shouldBe true
  }
}
