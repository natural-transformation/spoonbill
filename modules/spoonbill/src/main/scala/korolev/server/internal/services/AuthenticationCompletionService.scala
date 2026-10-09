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
    request.header("Origin").exists(config.allowedOrigins.contains) &&
      request.header("Sec-Fetch-Site").forall(_ == "same-origin")

  private def cookie(name: String, value: String, age: Long): (String, String) =
    "Set-Cookie" -> s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=$age${if (config.secureCookies) "; Secure" else ""}"

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

  def complete(request: HttpRequest[F]): F[HttpResponse[F]] = {
    val binding = request.cookie(config.bindingCookieName).filter(_.matches("[A-Za-z0-9_-]{43}"))
    if (request.method != Request.Method.Post || !acceptsOrigin(request) || binding.isEmpty ||
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
    if (request.method != Request.Method.Post || !acceptsOrigin(request) || binding.isEmpty) reject(request)
    else binding.fold(reject(request)) { secret =>
      request.body.cancel().flatMap { _ =>
        F.delayAsync(config.logout(secret, request.cookie(config.sessionCookieName))).flatMap { _ =>
          HttpResponse(Response.Status(204, "No Content"), "", noStore :+ cookie(config.sessionCookieName, "", 0))
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
