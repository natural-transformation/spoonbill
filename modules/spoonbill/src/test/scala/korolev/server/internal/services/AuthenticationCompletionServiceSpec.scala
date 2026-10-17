package spoonbill.server.internal.services

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.server.{
  AuthenticationCompletionConfig,
  AuthenticationReplacementConfig,
  AuthenticationReplacementRecovery,
  BrowserSessionToken,
  HttpRequest,
  WebSocketRequest,
  WebSocketResponse
}
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

class AuthenticationCompletionServiceSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect

  private val origin       = "https://app.example"
  private val bindingName  = "app_binding"
  private val sessionName  = "app_session"
  private val bindingValue = "b" * 43
  private val tokenValue   = "t" * 43
  private val token        = BrowserSessionToken.fromString(tokenValue).fold(error => fail(error), identity)
  private val completionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

  private def config(
    allowedOrigins: Set[String] = Set(origin),
    secureCookies: Boolean = true,
    deliver: (UUID, String) => Future[Option[BrowserSessionToken]] = (_, _) => Future.successful(Some(token)),
    logout: (String, Option[String]) => Future[Unit] = (_, _) => Future.successful(())
  ): AuthenticationCompletionConfig[Future] =
    AuthenticationCompletionConfig[Future](
      bindingCookieName = bindingName,
      sessionCookieName = sessionName,
      allowedOrigins = allowedOrigins,
      secureCookies = secureCookies,
      cookieMaxAgeSeconds = 600,
      deliver = deliver,
      logout = logout
    )

  private def body(chunks: Seq[String]): Stream[Future, Bytes] =
    if (chunks.isEmpty) Stream.empty[Future, Bytes]
    else {
      val bytes = chunks.map(value => Bytes.wrap[Array[Byte]](value.getBytes(StandardCharsets.UTF_8)))
      Await.result(Stream.emits[Bytes](bytes).mat[Future](), 2.seconds)
    }

  private def request(
    chunks: Seq[String] = Seq.empty,
    contentLength: Option[Long] = None,
    requestOrigin: Option[String] = Some(origin),
    fetchSite: Option[String] = Some("same-origin"),
    binding: Option[String] = Some(bindingValue),
    session: Option[String] = Some("old-session-token"),
    method: Request.Method = Request.Method.Post,
    contentType: Option[String] = Some("text/plain")
  ): HttpRequest[Future] = {
    val headers =
      requestOrigin.toSeq.map("Origin"       -> _) ++
        fetchSite.toSeq.map("Sec-Fetch-Site" -> _) ++
        contentType.toSeq.map("Content-Type" -> _)
    val cookies =
      binding.toSeq.map(bindingName   -> _) ++
        session.toSeq.map(sessionName -> _)
    val renderedCookies = cookies.map { case (name, value) => s"$name=$value" }.mkString(";")
    Request(method, PathAndQuery.Root, headers, contentLength, body(chunks), renderedCookies)
  }

  private def run[A](result: Future[A]): A = Await.result(result, 3.seconds)

  private def responseBody(response: spoonbill.server.HttpResponse[Future]): String =
    run(response.body.fold(Bytes.empty)(_ ++ _).map(_.asUtf8String))

  "complete" should "reject missing, wrong, and cross-site origins before delivery" in {
    val delivered = new AtomicInteger(0)
    val service = new AuthenticationCompletionService[Future](config(deliver = (_, _) => {
      delivered.incrementAndGet()
      Future.successful(Some(token))
    }))
    val validBody = Seq("123e4567-e89b-12d3-a456-", "426614174000")
    val rejected = Seq(
      request(chunks = validBody, contentLength = Some(36L), requestOrigin = None),
      request(chunks = validBody, contentLength = Some(36L), requestOrigin = Some("https://evil.example")),
      request(chunks = validBody, contentLength = Some(36L), fetchSite = Some("cross-site"))
    ).map(value => run(service.complete(value)))

    rejected.map(_.status.code) shouldBe Seq(403, 403, 403)
    delivered.get() shouldBe 0
  }

  it should "reject missing or malformed browser bindings" in {
    val delivered = new AtomicInteger(0)
    val service = new AuthenticationCompletionService[Future](config(deliver = (_, _) => {
      delivered.incrementAndGet()
      Future.successful(Some(token))
    }))
    val validBody = Seq("123e4567-e89b-12d3-a456-426614174000")
    val missing   = run(service.complete(request(chunks = validBody, contentLength = Some(36L), binding = None)))
    val malformed =
      run(service.complete(request(chunks = validBody, contentLength = Some(36L), binding = Some("short"))))

    missing.status.code shouldBe 403
    malformed.status.code shouldBe 403
    delivered.get() shouldBe 0
  }

  it should "bound chunked UUID input before calling the delivery port" in {
    val delivered = new AtomicInteger(0)
    val service = new AuthenticationCompletionService[Future](config(deliver = (_, _) => {
      delivered.incrementAndGet()
      Future.successful(Some(token))
    }))
    val oversized        = "123e4567-e89b-12d3-a456-426614174000x"
    val declaredOversize = run(service.complete(request(chunks = Seq(oversized), contentLength = Some(37L))))
    val chunkedOversize = run(
      service.complete(
        request(
          chunks = Seq(oversized.take(12), oversized.drop(12)),
          contentLength = None
        )
      )
    )

    declaredOversize.status.code shouldBe 403
    chunkedOversize.status.code shouldBe 403
    delivered.get() shouldBe 0
  }

  it should "return 204 with a no-store HttpOnly cookie and no token body" in {
    val delivered = new AtomicReference[Option[(UUID, String)]](None)
    val service = new AuthenticationCompletionService[Future](config(deliver = (id, binding) => {
      delivered.set(Some(id -> binding))
      Future.successful(Some(token))
    }))
    val response = run(
      service.complete(
        request(
          chunks = Seq("123e4567-e89b-12d3-a456-426614174000"),
          contentLength = Some(36L)
        )
      )
    )
    val sessionCookie = response.header("Set-Cookie").getOrElse(fail("Missing session cookie"))

    response.status.code shouldBe 204
    response.header("Cache-Control") shouldBe Some("no-store")
    response.header("Pragma") shouldBe Some("no-cache")
    sessionCookie should include(s"$sessionName=$tokenValue")
    sessionCookie should include("Path=/")
    sessionCookie should include("HttpOnly")
    sessionCookie should include("SameSite=Lax")
    sessionCookie should include("Secure")
    responseBody(response) shouldBe ""
    delivered.get() shouldBe Some(completionId -> bindingValue)
  }

  "initialCookies" should "create a binding and clear an orphaned session cookie" in {
    val service = new AuthenticationCompletionService[Future](config())
    val responseCookies = run(
      service.initialCookies(
        request(
          method = Request.Method.Get,
          binding = None,
          session = Some(tokenValue),
          contentType = None
        )
      )
    )
    val bindingCookie =
      responseCookies.find(_._2.startsWith(s"$bindingName=")).getOrElse(fail("Missing binding cookie"))._2
    val clearCookie =
      responseCookies.find(_._2.startsWith(s"$sessionName=")).getOrElse(fail("Missing session clear cookie"))._2
    val generatedBinding = bindingCookie.takeWhile(_ != ';').split("=", 2).last

    generatedBinding should fullyMatch regex "[A-Za-z0-9_-]{43}"
    bindingCookie should include("HttpOnly")
    bindingCookie should include("Secure")
    clearCookie should include("=; Path=/")
    clearCookie should include("HttpOnly")
    clearCookie should include("Max-Age=0")
    run(service.initialCookies(request(method = Request.Method.Get, contentType = None))) shouldBe empty
  }

  "prepareInitial" should "give the loader the issued binding and remove orphaned authority" in {
    val service = new AuthenticationCompletionService[Future](config())
    val original = request(binding = None, session = Some(tokenValue))
      .copy(renderedCookie = s"theme=dark; $sessionName=$tokenValue")
    val (prepared, cookies) = run(service.prepareInitial(original))
    val issued = cookies
      .find(_._2.startsWith(s"$bindingName="))
      .get
      ._2
      .drop(bindingName.length + 1)
      .takeWhile(_ != ';')
    prepared.cookie(bindingName) shouldBe Some(issued)
    prepared.cookie(sessionName) shouldBe None
    prepared.cookie("theme") shouldBe Some("dark")
    prepared.header("Cookie") shouldBe Some(prepared.renderedCookie)
  }

  "completion endpoints" should "reject duplicate origin, fetch-site, and authority cookies" in {
    val called = new AtomicInteger()
    val service = new AuthenticationCompletionService[Future](
      config(
        deliver = (_, _) => { called.incrementAndGet(); Future.successful(Some(token)) },
        logout = (_, _) => { called.incrementAndGet(); Future.successful(()) }
      )
    )
    def valid = request(chunks = Seq(completionId.toString), contentLength = Some(36L))
    val variants: Seq[HttpRequest[Future] => HttpRequest[Future]] = Seq(
      request => request.copy(headers = request.headers :+ ("origin" -> origin)),
      request => request.copy(headers = request.headers :+ ("Sec-Fetch-Site" -> "same-origin")),
      request => request.copy(renderedCookie = request.renderedCookie + s"; $bindingName=$bindingValue"),
      request => request.copy(renderedCookie = request.renderedCookie + s"; $sessionName=$tokenValue")
    )
    variants.foreach { modify =>
      run(service.complete(modify(valid))).status.code shouldBe 403
      run(service.logout(modify(valid))).status.code shouldBe 403
    }
    called.get() shouldBe 0
  }

  "AuthenticationCompletionConfig" should "reject insecure remote origins" in {
    intercept[IllegalArgumentException] {
      config(allowedOrigins = Set("http://app.example"), secureCookies = false)
    }
  }

  "logout" should "invoke the browser-bound callback before clearing the session cookie" in {
    val callback  = new AtomicReference[Option[(String, Option[String])]](None)
    val completed = new AtomicBoolean(false)
    val service = new AuthenticationCompletionService[Future](config(logout = (binding, session) => {
      callback.set(Some(binding -> session))
      Future.successful(()).map { _ =>
        completed.set(true); ()
      }
    }))
    val response    = run(service.logout(request(chunks = Seq("ignored"), contentLength = Some(7L))))
    val clearCookie = response.header("Set-Cookie").getOrElse(fail("Missing session clear cookie"))

    response.status.code shouldBe 204
    completed.get() shouldBe true
    callback.get() shouldBe Some(bindingValue -> Some("old-session-token"))
    clearCookie should include(s"$sessionName=; Path=/")
    clearCookie should include("HttpOnly")
    clearCookie should include("Max-Age=0")
    responseBody(response) shouldBe ""
  }

  it should "redirect native HTML forms only after acknowledged logout" in {
    val service  = new AuthenticationCompletionService[Future](config().copy(logoutRedirect = Some("/")))
    val response = run(service.logout(request()))
    response.status.code shouldBe 303
    response.header("Location") shouldBe Some("/")
    response.header("Set-Cookie").get should include("Max-Age=0")
    intercept[IllegalArgumentException](config().copy(logoutRedirect = Some("//other.example")))
  }

  "Replacement recovery" should "use strict checked POST input and deliver only an HttpOnly cookie" in {
    val calls = new AtomicInteger()
    val replacement = AuthenticationReplacementConfig[Future](
      _ => Future.successful(true),
      bound => {
        bound.binding shouldBe bindingValue
        bound.ceremonyId.map(_.value) shouldBe Some(completionId)
        calls.incrementAndGet()
        Future.successful(AuthenticationReplacementRecovery.Deliver(token))
      },
      (_, _) => Future.successful(true)
    )
    val service = new AuthenticationCompletionService[Future](config().copy(replacement = Some(replacement)))
    val invalid = Seq(
      request(session = None, method = Request.Method.Get),
      request(session = None, requestOrigin = None),
      request(session = None, fetchSite = Some("cross-site")),
      request(session = None, chunks = Seq("x" * 37)),
      request(session = None, chunks = Seq.fill(65)("")),
      request(session = None, contentLength = Some(36L)),
      request(session = None, chunks = Seq(completionId.toString), contentLength = Some(0L)),
      request(session = Some("malformed")),
      request(session = None, chunks = Seq("not-a-uuid"))
    )
    invalid.foreach(value => run(service.recoverReplacement(value)).status.code shouldBe 403)
    calls.get() shouldBe 0
    val response = run(service.recoverReplacement(request(session = None, chunks = Seq(completionId.toString))))
    response.status.code shouldBe 204
    responseBody(response) shouldBe ""
    response.header("Cache-Control") shouldBe Some("no-store")
    response.header("Set-Cookie").get should include("HttpOnly")
    calls.get() shouldBe 1
  }

  it should "bootstrap an accepted pending successor cookie without another delivery" in {
    val probes      = new AtomicInteger()
    val recoveries  = new AtomicInteger()
    val activations = new AtomicInteger()
    val replacement = AuthenticationReplacementConfig[Future](
      _ => { probes.incrementAndGet(); Future.successful(true) },
      bound => {
        bound.token shouldBe Some(tokenValue)
        bound.ceremonyId shouldBe None
        recoveries.incrementAndGet()
        Future.successful(AuthenticationReplacementRecovery.ActivateExistingCookie)
      },
      (binding, supplied) => {
        binding shouldBe bindingValue
        supplied shouldBe Some(tokenValue)
        activations.incrementAndGet()
        Future.successful(true)
      }
    )
    val service    = new AuthenticationCompletionService[Future](config().copy(replacement = Some(replacement)), "/app/")
    val navigation = request(session = Some(tokenValue), method = Request.Method.Get, requestOrigin = None)
    val page       = run(service.replacementBootstrap(navigation)).get
    val html       = responseBody(page)
    html should include("/app/auth/replacement-bootstrap.js")
    html should not include tokenValue
    probes.get() shouldBe 1
    recoveries.get() shouldBe 0
    activations.get() shouldBe 0
    val recovered = run(service.recoverReplacement(request(session = Some(tokenValue))))
    recovered.status.code shouldBe 204
    recovered.header("Set-Cookie") shouldBe None
    val activated = run(service.activateReplacement(WebSocketRequest(request(session = Some(tokenValue)), Nil)))
    activated.isInstanceOf[WebSocketResponse.SendThenClose[Future]] shouldBe true
    run(activated.output.fold(Bytes.empty)(_ ++ _).map(_.asUtf8String)) shouldBe "activated"
    recoveries.get() shouldBe 1
    activations.get() shouldBe 1
    run(activated.release())
  }

  it should "deny foreign-origin activation and leave ordinary navigation unchanged without a pending replacement" in {
    val activations = new AtomicInteger()
    val replacement = AuthenticationReplacementConfig[Future](
      _ => Future.successful(false),
      _ => Future.successful(AuthenticationReplacementRecovery.Unavailable),
      (_, _) => { activations.incrementAndGet(); Future.successful(true) }
    )
    val service = new AuthenticationCompletionService[Future](config().copy(replacement = Some(replacement)))
    run(service.replacementBootstrap(request(session = Some(tokenValue), method = Request.Method.Get))) shouldBe None
    intercept[spoonbill.server.SessionAccessDenied] {
      run(
        service.activateReplacement(
          WebSocketRequest(request(session = Some(tokenValue), requestOrigin = Some("https://evil.example")), Nil)
        )
      )
    }
    activations.get() shouldBe 0
  }

  it should "acknowledge a fenced negative outcome without replacing the current cookie" in {
    val replacement = AuthenticationReplacementConfig[Future](
      _ => Future.successful(false),
      bound => {
        bound.ceremonyId.map(_.value) shouldBe Some(completionId)
        Future.successful(AuthenticationReplacementRecovery.NotCommitted)
      },
      (_, _) => Future.failed(new AssertionError("Negative reconciliation must not activate"))
    )
    val service = new AuthenticationCompletionService[Future](config().copy(replacement = Some(replacement)))
    val response =
      run(service.recoverReplacement(request(session = Some(tokenValue), chunks = Seq(completionId.toString))))
    response.status.code shouldBe 204
    response.header("Set-Cookie") shouldBe None
    responseBody(response) shouldBe ""
  }
}
