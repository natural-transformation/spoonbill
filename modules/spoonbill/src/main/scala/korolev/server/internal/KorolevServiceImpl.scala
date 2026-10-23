/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.server.internal

import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.server._
import spoonbill.server.internal.services._
import spoonbill.web.PathAndQuery._

private[spoonbill] final class SpoonbillServiceImpl[F[_]: Effect](
  http: PartialFunction[HttpRequest[F], F[HttpResponse[F]]],
  commonService: CommonService[F],
  filesService: FilesService[F],
  messagingService: MessagingService[F],
  postService: PostService[F],
  ssrService: ServerSideRenderingService[F, _, _],
  authenticationService: Option[AuthenticationCompletionService[F]] = None,
  guardedSessions: Boolean = false
) extends SpoonbillService[F] {

  def http(request: HttpRequest[F]): F[HttpResponse[F]] =
    (request.cookie(Cookies.DeviceId).filter(Cookies.validDeviceId), request.pq) match {

      case (_, Root / "auth" / "complete") if authenticationService.nonEmpty =>
        authenticationService.fold(commonService.notFoundResponseF)(_.complete(request))
      case (_, Root / "auth" / "logout") if authenticationService.nonEmpty =>
        authenticationService.fold(commonService.notFoundResponseF)(_.logout(request))
      case (_, Root / "auth" / "recover") if authenticationService.nonEmpty =>
        authenticationService.fold(commonService.notFoundResponseF)(_.recoverReplacement(request))
      case (_, Root / "auth" / "replacement-bootstrap.js") if authenticationService.nonEmpty =>
        authenticationService.fold(commonService.notFoundResponseF)(_.replacementBootstrapScript(request))

      // Legacy attachment and long-polling routes have no per-request session
      // binding. Fail closed until those transports participate in the guard.
      case (_, path) if guardedSessions && path.startsWith("bridge") =>
        request.body.cancel().flatMap(_ => commonService.notFoundResponseF)

      // Static files
      case (_, Root / "static") =>
        commonService.notFoundResponseF
      case (_, path) if path.startsWith("static") =>
        filesService.resourceFromClasspath(path)

      // Long polling
      case (Some(deviceId), Root / "bridge" / "long-polling" / sessionId / "publish") =>
        messagingService.longPollingPublish(Qsid(deviceId, sessionId), request.body)
      case (Some(deviceId), Root / "bridge" / "long-polling" / sessionId / "subscribe") =>
        messagingService.longPollingSubscribe(Qsid(deviceId, sessionId), request)

      // Data for app given via POST requests
      case (Some(deviceId), Root / "bridge" / sessionId / "form-data" / descriptor) =>
        postService.formData(Qsid(deviceId, sessionId), descriptor, request.headers, request.body)
      case (Some(deviceId), Root / "bridge" / sessionId / "file" / descriptor / "info") =>
        postService.filesInfo(Qsid(deviceId, sessionId), descriptor, request.body)
      case (Some(deviceId), Root / "bridge" / sessionId / "file" / descriptor / _) =>
        postService.downloadFile(Qsid(deviceId, sessionId), descriptor)
      case (Some(deviceId), Root / "bridge" / sessionId / "file" / descriptor) =>
        postService.uploadFile(Qsid(deviceId, sessionId), descriptor, request.headers, request.body)

      // Server side rendering
      case (_, path) if path == Root || ssrService.canBeRendered(request.pq) =>
        for {
          initial <- authenticationService
                       .fold(Effect[F].pure(request -> Seq.empty[(String, String)]))(_.prepareInitial(request))
          bootstrap <- authenticationService.fold(Effect[F].pure(Option.empty[HttpResponse[F]]))(
                         _.replacementBootstrap(initial._1)
                       )
          response <- bootstrap.fold(ssrService.serverSideRenderedPage(initial._1))(Effect[F].pure)
        } yield response.copy(headers = response.headers ++ initial._2)

      // Not found
      case _ => http.applyOrElse(request, (_: HttpRequest[F]) => commonService.notFoundResponseF)
    }

  def ws(wsRequest: WebSocketRequest[F]): F[WebSocketResponse[F]] =
    if (guardedSessions && !authenticationService.exists(_.acceptsOrigin(wsRequest.httpRequest)))
      Effect[F].fail(BadRequestException("WebSocket origin rejected"))
    else
      (wsRequest.httpRequest.cookie(Cookies.DeviceId).filter(Cookies.validDeviceId), wsRequest.httpRequest.pq) match {
        case (_, Root / "auth" / "activate") if authenticationService.nonEmpty =>
          authenticationService.get.activateReplacement(wsRequest)
        case (Some(deviceId), Root / "bridge" / "web-socket" / sessionId) =>
          messagingService.webSocketMessaging(
            Qsid(deviceId, sessionId),
            wsRequest.httpRequest,
            wsRequest.httpRequest.body,
            wsRequest.protocols
          )
        case _ =>
          webSocketBadRequestF
      }

  private val webSocketBadRequestF = {
    val error = BadRequestException(
      "Malformed request. Headers MUST contain deviceId cookie. Path MUST be '/bridge/web-socket/<session>'."
    )
    Effect[F].fail[WebSocketResponse[F]](error)
  }
}
