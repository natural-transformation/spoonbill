package spoonbill.server.internal.services

import java.security.SecureRandom
import java.util.{Base64, UUID}
import spoonbill.auth.{BrowserReplacementRequest, CeremonyId}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*
import spoonbill.server.{
  AuthenticationCompletionConfig,
  AuthenticationReplacementRecovery,
  HttpRequest,
  HttpResponse,
  SessionAccessDenied,
  WebSocketRequest,
  WebSocketResponse
}
import spoonbill.web.{Request, Response}

/**
 * The only browser authentication endpoint: bounded cookie delivery, never
 * factor verification.
 */
private[spoonbill] final class AuthenticationCompletionService[F[_]: Effect](
  config: AuthenticationCompletionConfig[F],
  rootPath: String = "/"
) {
  private val F         = Effect[F]
  private val random    = new SecureRandom()
  private val noStore   = Seq("Cache-Control" -> "no-store", "Pragma" -> "no-cache")
  private val forbidden = Response.Status(403, "Forbidden")
  private def replacementRequest(request: Request.Head, lookup: Option[CeremonyId]): Option[BrowserReplacementRequest] =
    if (!unambiguousCookies(request)) None
    else {
      val token = request.cookie(config.sessionCookieName)
      if (token.exists(!_.matches("[A-Za-z0-9_-]{43}"))) None
      else
        request
          .cookie(config.bindingCookieName)
          .filter(_.matches("[A-Za-z0-9_-]{43}"))
          .map(binding => new BrowserReplacementRequest(binding, token, lookup))
    }

  def acceptsOrigin(request: Request.Head): Boolean =
    request.headers.collect { case (name, value) if name.equalsIgnoreCase("Origin") => value } match {
      case Seq(origin) if config.allowedOrigins.contains(origin) =>
        request.headers.collect { case (name, value) if name.equalsIgnoreCase("Sec-Fetch-Site") => value } match {
          case Seq() | Seq("same-origin") => true
          case _                          => false
        }
      case _ => false
    }

  private def cookie(name: String, value: String, age: Long): (String, String) =
    "Set-Cookie" -> s"$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=$age${if (config.secureCookies) "; Secure"
    else ""}"

  private def unambiguousCookies(request: Request.Head): Boolean = {
    val names = Option(request.renderedCookie).toSeq
      .flatMap(_.split(';'))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(pair => scala.util.Try(java.net.URLDecoder.decode(pair.takeWhile(_ != '='), "UTF-8")).toOption)
    names.forall(_.nonEmpty) && Seq(config.bindingCookieName, config.sessionCookieName)
      .forall(name => names.count(_.contains(name)) <= 1)
  }

  /**
   * Establish an unguessable HttpOnly binding before the first socket, without
   * reparenting a credential.
   */
  def initialCookies(request: Request.Head): F[Seq[(String, String)]] = F.delay {
    if (request.cookie(config.bindingCookieName).exists(_.matches("[A-Za-z0-9_-]{43}"))) Nil
    else {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      val binding = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      Seq(
        cookie(config.bindingCookieName, binding, config.cookieMaxAgeSeconds),
        cookie(config.sessionCookieName, "", 0)
      )
    }
  }

  /**
   * Give the HTTP guard and loader the binding issued for this same request. An
   * old credential must never be reparented to a fresh browser binding.
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
          request.copy(
            headers = request.headers.filterNot(_._1.equalsIgnoreCase("Cookie")) :+ ("Cookie" -> cookie),
            renderedCookie = cookie
          ) -> headers
      }
    }

  def complete(request: HttpRequest[F]): F[HttpResponse[F]] = {
    val binding = request.cookie(config.bindingCookieName).filter(_.matches("[A-Za-z0-9_-]{43}"))
    if (
      request.method != Request.Method.Post || !acceptsOrigin(request) || !unambiguousCookies(
        request
      ) || binding.isEmpty ||
      !request.header("Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("text/plain")) ||
      request.contentLength.exists(_ != 36L)
    ) reject(request)
    else
      readHandle(request, Bytes.empty).flatMap {
        case None => HttpResponse(forbidden, "", noStore)
        case Some(id) =>
          binding.fold(HttpResponse[F](forbidden, "", noStore)) { secret =>
            F.delayAsync(config.deliver(id, secret)).flatMap {
              case None => HttpResponse(forbidden, "", noStore)
              case Some(token) =>
                HttpResponse(
                  Response.Status(204, "No Content"),
                  "",
                  noStore :+ cookie(config.sessionCookieName, token.value, config.cookieMaxAgeSeconds)
                )
            }
          }
      }
  }

  /**
   * Recovery is a checked POST independent of application loading and view
   * ownership.
   */
  def recoverReplacement(request: HttpRequest[F]): F[HttpResponse[F]] =
    if (
      request.method != Request.Method.Post || !acceptsOrigin(request) || config.replacement.isEmpty ||
      !request.header("Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("text/plain")) ||
      request.contentLength.exists(length => length != 0L && length != 36L) || replacementRequest(request, None).isEmpty
    )
      reject(request)
    else
      readRecoveryHandle(request, Bytes.empty).flatMap {
        case None => HttpResponse(forbidden, "", noStore)
        case Some(lookup) =>
          replacementRequest(request, lookup) match {
            case None => HttpResponse(forbidden, "", noStore)
            case Some(bound) =>
              F.delayAsync(config.replacement.get.recover(bound)).flatMap {
                case AuthenticationReplacementRecovery.ActivateExistingCookie |
                    AuthenticationReplacementRecovery.NotCommitted =>
                  HttpResponse(Response.Status(204, "No Content"), "", noStore)
                case AuthenticationReplacementRecovery.Deliver(token) =>
                  HttpResponse(
                    Response.Status(204, "No Content"),
                    "",
                    noStore :+ cookie(config.sessionCookieName, token.value, config.cookieMaxAgeSeconds)
                  )
                case AuthenticationReplacementRecovery.Unavailable => HttpResponse(forbidden, "", noStore)
              }
          }
      }

  /**
   * GET only observes committed pending state. It never reconciles a writer,
   * delivers a credential, activates a session, or invokes an application
   * loader.
   */
  def replacementBootstrap(request: HttpRequest[F]): F[Option[HttpResponse[F]]] =
    (config.replacement, replacementRequest(request, None)) match {
      case (Some(replacement), Some(bound)) if request.method == Request.Method.Get =>
        F.delayAsync(replacement.pending(bound)).flatMap {
          case false => F.pure(None)
          case true =>
            val script = (rootPath + "auth/replacement-bootstrap.js")
              .replace("&", "&amp;")
              .replace("\"", "&quot;")
              .replace("<", "&lt;")
            val logout = (rootPath + "auth/logout").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            HttpResponse(
              Response.Status.Ok,
              s"""<!doctype html><html><head><meta charset="utf-8"><title>Restoring session</title></head><body><p id="spoonbill-recovery-status">Restoring your secure session…</p><form method="post" action="$logout"><button>Sign in again</button></form><script src="$script" defer></script></body></html>""",
              noStore :+ ("Content-Type" -> "text/html; charset=utf-8")
            ).map(Some(_))
        }
      case _ => F.pure(None)
    }

  def replacementBootstrapScript(request: HttpRequest[F]): F[HttpResponse[F]] =
    if (config.replacement.isEmpty || request.method != Request.Method.Get) reject(request)
    else
      request.body
        .cancel()
        .flatMap(_ =>
          HttpResponse(
            Response.Status.Ok,
            AuthenticationCompletionService.replacementBootstrapScript,
            noStore :+ ("Content-Type" -> "application/javascript; charset=utf-8")
          )
        )

  /**
   * A fresh physical handshake activates the cookie without allocating an
   * application view, invoking a loader, or returning any protected output.
   */
  def activateReplacement(request: WebSocketRequest[F]): F[WebSocketResponse[F]] =
    (config.replacement, replacementRequest(request.httpRequest, None)) match {
      case (Some(replacement), Some(bound)) if acceptsOrigin(request.httpRequest) =>
        F.delayAsync(replacement.activate(bound.binding, bound.token)).flatMap {
          case false => F.fail(new SessionAccessDenied)
          case true =>
            Stream("activated").mat[F]().map { values =>
              val output =
                values.map(value => Bytes.wrap[Array[Byte]](value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              WebSocketResponse.SendThenClose(
                output,
                request.protocols.find(_ == "json").getOrElse(""),
                WebSocketResponse.releaseOnce[F](() => request.httpRequest.body.cancel().flatMap(_ => output.cancel()))
              )
            }
        }
      case _ => F.fail(new SessionAccessDenied)
    }

  private def readRecoveryHandle(
    request: HttpRequest[F],
    bytes: Bytes,
    remainingChunks: Int = 64
  ): F[Option[Option[CeremonyId]]] =
    request.body.pull().flatMap {
      case Some(_) if remainingChunks <= 0                                => request.body.cancel().map(_ => None)
      case Some(chunk) if bytes.length + chunk.length > 36                => request.body.cancel().map(_ => None)
      case Some(chunk)                                                    => readRecoveryHandle(request, bytes ++ chunk, remainingChunks - 1)
      case None if request.contentLength.exists(_ != bytes.length.toLong) => F.pure(None)
      case None if bytes.length == 0                                      => F.pure(Some(None))
      case None =>
        F.delay {
          val raw = bytes.asUtf8String
          if (!raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) None
          else scala.util.Try(UUID.fromString(raw)).toOption.map(id => Some(CeremonyId(id)))
        }
    }

  def logout(request: HttpRequest[F]): F[HttpResponse[F]] = {
    val binding = request.cookie(config.bindingCookieName).filter(_.matches("[A-Za-z0-9_-]{43}"))
    if (
      request.method != Request.Method.Post || !acceptsOrigin(request) || !unambiguousCookies(
        request
      ) || binding.isEmpty
    ) reject(request)
    else
      binding.fold(reject(request)) { secret =>
        request.body.cancel().flatMap { _ =>
          F.delayAsync(config.logout(secret, request.cookie(config.sessionCookieName))).flatMap { _ =>
            val headers = noStore :+ cookie(config.sessionCookieName, "", 0)
            config.logoutRedirect match {
              case Some(path) => HttpResponse(Response.Status(303, "See Other"), "", headers :+ ("Location" -> path))
              case None       => HttpResponse(Response.Status(204, "No Content"), "", headers)
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
      case None =>
        F.delay {
          val raw = bytes.asUtf8String
          if (!raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) None
          else scala.util.Try(UUID.fromString(raw)).toOption
        }
    }
}

private[services] object AuthenticationCompletionService {
  // Static framework code. No application state, lookup handle, credential or
  // caller-provided URL is interpolated into this script.
  val replacementBootstrapScript: String = """
(() => {
  'use strict';
  const base = new URL('.', document.currentScript.src);
  if (base.origin !== window.location.origin) return;
  const status = document.getElementById('spoonbill-recovery-status');
  const post = async () => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);
      try {
        const response = await fetch(new URL('recover', base), {
          method: 'POST', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store',
          headers: {'Content-Type': 'text/plain'}, body: '', signal: controller.signal
        });
        if (response.status === 204) return;
        throw new Error('Recovery unavailable');
      } catch (_) {
        if (attempt === 2) throw new Error('Recovery unavailable');
      } finally { clearTimeout(timeout); }
    }
  };
  const activate = () => new Promise((resolve, reject) => {
    const url = new URL('activate', base);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(url.toString(), ['json']);
    let settled = false;
    let receiving = false;
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      socket.close();
      if (ok) resolve(); else reject(new Error('Activation unavailable'));
    };
    const timeout = setTimeout(() => finish(false), 10000);
    socket.onmessage = async event => {
      if (settled || receiving) return;
      receiving = true;
      try {
        const message = typeof event.data === 'string' ? event.data : await event.data.text();
        finish(message === 'activated');
      } catch (_) { finish(false); }
    };
    // A terminal binary frame can arrive before close while Blob.text is still
    // pending. Let that one received acknowledgement settle before treating
    // the normal terminal close as a missing response; the timeout stays armed.
    socket.onerror = () => { if (!receiving) finish(false); };
    socket.onclose = () => { if (!receiving) finish(false); };
  });
  (async () => {
    // Another tab may activate after the GET probe and before this POST. The
    // HttpOnly cookie can then already be current even though discovery has no
    // pending reservation. Its fresh handshake still verifies that authority.
    try { await post(); } catch (_) {}
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try { await activate(); window.location.reload(); return; }
      catch (_) { if (attempt === 2) throw new Error('Activation unavailable'); }
    }
  })().catch(() => {
    status.textContent = 'Session recovery could not finish. Reload to retry, or sign in again.';
  });
})();
"""
}
