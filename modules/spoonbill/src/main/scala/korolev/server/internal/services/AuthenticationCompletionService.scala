package spoonbill.server.internal.services

import java.security.SecureRandom
import java.util.{Base64, UUID}
import spoonbill.data.Bytes
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.server.{AuthenticationCompletionConfig, HttpRequest, HttpResponse}
import spoonbill.web.{Request, Response}

/** The only browser authentication endpoint: bounded cookie delivery, never factor verification. */
private[spoonbill] final class AuthenticationCompletionService[F[_]: Effect](
  config: AuthenticationCompletionConfig[F]
) {
  private val F = Effect[F]
  private val random = new SecureRandom()
  private val noStore = Seq("Cache-Control" -> "no-store", "Pragma" -> "no-cache")
  private val forbidden = Response.Status(403, "Forbidden")

  def acceptsOrigin(request: Request.Head): Boolean =
    request.headers.collect { case (name, value) if name.equalsIgnoreCase("Origin") => value } match {
      case Seq(origin) if config.allowedOrigins.contains(origin) =>
        request.headers.collect { case (name, value) if name.equalsIgnoreCase("Sec-Fetch-Site") => value } match {
          case Seq() | Seq("same-origin") => true
          case _ => false
        }
      case _ => false
    }

  private def cookie(name: String, value: String, age: Long): (String, String) =
    "Set-Cookie" -> s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=$age${if (config.secureCookies) "; Secure" else ""}"

  private def unambiguousCookies(request: Request.Head): Boolean = {
    val names = Option(request.renderedCookie).toSeq.flatMap(_.split(';')).map(_.trim).filter(_.nonEmpty)
      .map(pair => scala.util.Try(java.net.URLDecoder.decode(pair.takeWhile(_ != '='), "UTF-8")).toOption)
    names.forall(_.nonEmpty) && Seq(config.bindingCookieName, config.sessionCookieName)
      .forall(name => names.count(_.contains(name)) <= 1)
  }

  /** Establish an unguessable HttpOnly binding before the first socket, without reparenting a credential. */
  def initialCookies(request: Request.Head): F[Seq[(String, String)]] = F.delay {
    if (request.cookie(config.bindingCookieName).exists(_.matches("[A-Za-z0-9_-]{43}"))) Nil
    else {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      val binding = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      Seq(cookie(config.bindingCookieName, binding, config.cookieMaxAgeSeconds), cookie(config.sessionCookieName, "", 0))
    }
  }

  /** Give the HTTP guard and loader the binding issued for this same request.
    * An old credential must never be reparented to a fresh browser binding.
    */
  def prepareInitial(request: HttpRequest[F]): F[(HttpRequest[F], Seq[(String, String)])] =
    initialCookies(request).map { headers =>
      headers.collectFirst {
        case ("Set-Cookie", value) if value.startsWith(config.bindingCookieName + "=") =>
          value.drop(config.bindingCookieName.length + 1).takeWhile(_ != ';')
      } match {
        case None => request -> headers
        case Some(binding) =>
          val retained = Option(request.renderedCookie).toSeq.flatMap(_.split(';')).map(_.trim).filter { pair =>
            val name = scala.util.Try(java.net.URLDecoder.decode(pair.takeWhile(_ != '='), "UTF-8")).toOption
            name.exists(value => value != config.bindingCookieName && value != config.sessionCookieName)
          }
          val cookie = (retained :+ s"${config.bindingCookieName}=$binding").mkString("; ")
          request.copy(headers = request.headers.filterNot(_._1.equalsIgnoreCase("Cookie")) :+ ("Cookie" -> cookie),
            renderedCookie = cookie) -> headers
      }
    }

  def complete(request: HttpRequest[F]): F[HttpResponse[F]] = {
    val binding = request.cookie(config.bindingCookieName).filter(_.matches("[A-Za-z0-9_-]{43}"))
    if (request.method != Request.Method.Post || !acceptsOrigin(request) || !unambiguousCookies(request) || binding.isEmpty ||
        !request.header("Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("text/plain")) ||
        request.contentLength.exists(_ != 36L)) reject(request)
    else readHandle(request, Bytes.empty).flatMap {
      case None => HttpResponse(forbidden, "", noStore)
      case Some(id) =>
        binding.fold(HttpResponse[F](forbidden, "", noStore)) { secret =>
          F.delayAsync(config.deliver(id, secret)).flatMap {
            case None => HttpResponse(forbidden, "", noStore)
            case Some(token) => HttpResponse(Response.Status(204, "No Content"), "",
              noStore :+ cookie(config.sessionCookieName, token.value, config.cookieMaxAgeSeconds))
          }
        }
    }
  }

  def logout(request: HttpRequest[F]): F[HttpResponse[F]] = {
    val binding = request.cookie(config.bindingCookieName).filter(_.matches("[A-Za-z0-9_-]{43}"))
    if (request.method != Request.Method.Post || !acceptsOrigin(request) || !unambiguousCookies(request) || binding.isEmpty) reject(request)
    else binding.fold(reject(request)) { secret =>
      request.body.cancel().flatMap { _ =>
        F.delayAsync(config.logout(secret, request.cookie(config.sessionCookieName))).flatMap { _ =>
          val headers = noStore :+ cookie(config.sessionCookieName, "", 0)
          config.logoutRedirect match {
            case Some(path) => HttpResponse(Response.Status(303, "See Other"), "", headers :+ ("Location" -> path))
            case None => HttpResponse(Response.Status(204, "No Content"), "", headers)
          }
        }
      }
    }
  }

  private def reject(request: HttpRequest[F]): F[HttpResponse[F]] =
    request.body.cancel().flatMap(_ => HttpResponse(forbidden, "", noStore))

  private def readHandle(request: HttpRequest[F], bytes: Bytes): F[Option[UUID]] =
    request.body.pull().flatMap {
      case Some(chunk) if bytes.length + chunk.length > 36 =>
        request.body.cancel().map(_ => None)
      case Some(chunk) => readHandle(request, bytes ++ chunk)
      case None => F.delay {
        val raw = bytes.asUtf8String
        if (!raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) None
        else scala.util.Try(UUID.fromString(raw)).toOption
      }
    }
}
