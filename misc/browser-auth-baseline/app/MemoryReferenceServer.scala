package spoonbill.browserauthbaseline

import com.typesafe.config.ConfigFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, HttpCookiePair, RawHeader}
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.stream.Materializer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import spoonbill.pekko.{pekkoHttpService, PekkoHttpServerConfig}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.javaSerialization.*

/**
 * Only bootstrap-cookie and plain sign-out presentation wrap the official
 * adapter. Proofs, actions, completion delivery and socket ownership use v3.
 */
object MemoryReferenceServer {

  private[browserauthbaseline] def workloadPolicy(args: Array[String]): ReferencePolicy = {
    val selected = args.filter(_.startsWith("--proof="))
    require(selected.length <= 1, "Specify at most one proof profile")
    val proof = selected.headOption.getOrElse("--proof=short") match {
      case "--proof=short"          => ReferencePolicy.Proof.Short
      case "--proof=representative" => ReferencePolicy.Proof.Representative
      case _                        => throw new IllegalArgumentException("Use --proof=short or --proof=representative")
    }
    ReferencePolicy.Default.copy(proof = proof)
  }

  /**
   * One owned timer and at most one maintenance operation, even under load.
   * Stop admission before the backend's settlement-aware release runs.
   */
  def maintain[P](app: ReferenceApplication[P])(using system: ActorSystem, ec: ExecutionContext): Unit = {
    val shutdown = CoordinatedShutdown(system)
    val active   = new AtomicBoolean()
    val stopped  = new AtomicBoolean()
    val timer = system.scheduler.scheduleWithFixedDelay(1.second, 1.second) { () =>
      if (!stopped.get() && active.compareAndSet(false, true)) {
        val work =
          try app.backend.retireExpiredMaterial()
          catch { case scala.util.control.NonFatal(error) => scala.concurrent.Future.failed(error) }
        work.onComplete {
          case Success(_) => active.set(false)
          case Failure(_) =>
            active.set(false)
            shutdown.run(CoordinatedShutdown.UnknownReason)
        }
      }
    }
    shutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "stop-browser-auth-maintenance") { () =>
      stopped.set(true)
      timer.cancel()
      scala.concurrent.Future.successful(Done)
    }
  }

  private def cookie(name: String, value: String, age: Long): HttpHeader =
    RawHeader("Set-Cookie", s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=$age")
  private val clearSession = cookie("baseline_session", "", 0)
  private val noStore      = RawHeader("Cache-Control", "no-store")
  private val signOutPage  = """<!doctype html><html><head><title>Sign out</title></head><body>
    <h1>Sign out</h1><p>This signs this browser out and cancels its pending sign-ins.</p>
    <form method="post" action="/sign-out"><button type="submit">Confirm sign out</button></form>
    <a href="/">Return to the public page</a></body></html>"""
  private val deniedPage   = """<!doctype html><html><head><title>Authentication required</title></head><body>
    <h1>Authentication required</h1><p>This browser session could not authorize the request.</p>
    <p>Use sign out to fence the old browser state, then begin a fresh sign-in.</p>
    <a href="/sign-out">Sign out and start again</a> · <a href="/">Public page</a></body></html>"""

  def route[P](
    app: ReferenceApplication[P]
  )(using system: ActorSystem, mat: Materializer, ec: ExecutionContext): Route = {
    val service = pekkoHttpService(app.config).apply(PekkoHttpServerConfig(maxRequestBodySize = 64 * 1024))
    val denied = ExceptionHandler { case _: SessionAccessDenied =>
      complete(
        HttpResponse(
          StatusCodes.Forbidden,
          headers = List(noStore),
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, deniedPage)
        )
      )
    }
    handleExceptions(denied) {
      extractRequest { request =>
        val pairs         = request.headers.collect { case header: Cookie => header.cookies }.flatten
        val bindingValues = pairs.filter(_.name == "baseline_binding").map(_.value)
        val sessionValues = pairs.filter(_.name == "baseline_session").map(_.value)
        val binding       = bindingValues.headOption.filter(_.matches("[A-Za-z0-9_-]{43}"))
        if (bindingValues.size > 1 || sessionValues.size > 1) complete(StatusCodes.BadRequest)
        else if (request.uri.path.toString == "/reference-submit-unavailable") {
          request.discardEntityBytes()
          complete(StatusCodes.NotFound)
        } else if (request.uri.path.toString == "/sign-out") {
          if (request.method == HttpMethods.GET)
            complete(
              HttpResponse(headers = List(noStore), entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, signOutPage))
            )
          else if (request.method == HttpMethods.POST) {
            val origins = request.headers.filter(_.is("origin")).map(_.value())
            val sites   = request.headers.filter(_.is("sec-fetch-site")).map(_.value())
            if (origins != Seq(app.origin) || sites != Seq("same-origin") || binding.isEmpty)
              complete(StatusCodes.Forbidden)
            else {
              request.discardEntityBytes()
              onSuccess(app.backend.logoutBinding(binding.get)) {
                case Right(_) =>
                  complete(
                    HttpResponse(
                      StatusCodes.SeeOther,
                      headers = List(noStore, clearSession, RawHeader("Location", "/"))
                    )
                  )
                case Left(_) =>
                  complete(
                    HttpResponse(
                      StatusCodes.ServiceUnavailable,
                      headers = List(noStore),
                      entity = "Sign-out could not be confirmed. Retry this sign-out request."
                    )
                  )
              }
            }
          } else complete(StatusCodes.MethodNotAllowed)
        } else if (
          request.method == HttpMethods.GET &&
          Set("/", "/protected").contains(request.uri.path.toString)
        ) {
          val selected = binding.getOrElse(MemoryBrowserAuth.secureToken())
          onSuccess(app.backend.bootstrapBinding(selected)) {
            case Left(_)                      => complete(StatusCodes.ServiceUnavailable)
            case Right(_) if binding.nonEmpty => service
            case Right(_)                     =>
              // Never attach an existing credential to a newly issued binding.
              val retained = pairs.filterNot(pair => Set("baseline_binding", "baseline_session").contains(pair.name))
              val injected = request.withHeaders(
                request.headers.filterNot(_.is("cookie")) :+
                  Cookie((retained :+ HttpCookiePair("baseline_binding", selected)).toList)
              )
              mapRequest(_ => injected) {
                respondWithHeaders(cookie("baseline_binding", selected, 3600), clearSession, noStore)(service)
              }
          }
        } else service
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val policy     = workloadPolicy(args)
    val positional = args.filterNot(_.startsWith("--proof="))
    require(positional.length <= 1, "Use [port] [--proof=short|representative]")
    val port = positional.headOption.fold(8080)(_.toInt)
    require(port > 0 && port <= 65535, "Port must be between 1 and 65535")
    val shutdownConfig = ConfigFactory
      .parseString("pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 10 minutes")
      .withFallback(ConfigFactory.load())
    given ActorSystem      = ActorSystem("spoonbill-browser-auth-reference", shutdownConfig)
    given ExecutionContext = summon[ActorSystem].dispatcher
    given Materializer     = Materializer(summon[ActorSystem])
    val workers            = Executors.newFixedThreadPool(4)
    val proofContext       = ExecutionContext.fromExecutor(workers)
    val app                = new MemoryReferenceApplication(proofContext, s"http://localhost:$port", policy)
    val shutdown           = CoordinatedShutdown(summon[ActorSystem])
    maintain(app)
    shutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "release-browser-auth-reference") { () =>
      JdbcReferenceServer.releaseWorkersAfter(app.close(), workers)
    }
    Http().newServerAt("127.0.0.1", port).bindFlow(route(app)).onComplete {
      case scala.util.Success(binding) =>
        shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "unbind-browser-auth-reference")(() =>
          binding.unbind().map(_ => Done)
        )
        shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "drain-browser-auth-reference")(() =>
          binding.terminate(5.seconds).map(_ => Done)
        )
        println(s"Synthetic browser authentication reference: http://localhost:$port")
      case scala.util.Failure(_) =>
        System.err.println("The reference server could not bind its loopback port.")
        shutdown.run(CoordinatedShutdown.UnknownReason)
    }
  }
}
