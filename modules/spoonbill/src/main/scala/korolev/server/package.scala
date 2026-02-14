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

package spoonbill

import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax.*
import spoonbill.server.internal.{FormDataCodec, SpoonbillServiceImpl}
import spoonbill.server.internal.services.*
import spoonbill.state.{DeviceId, StateDeserializer, StateSerializer}
import spoonbill.web.{Headers, MimeTypes, Request, Response}
import spoonbill.web.Request.Head
import scala.concurrent.ExecutionContext
import java.nio.charset.StandardCharsets

package object server {

  type HttpRequest[F[_]]  = Request[Stream[F, Bytes]]
  type HttpResponse[F[_]] = Response[Stream[F, Bytes]]

  object HttpResponse {

    final val defaultHeaders = Seq(Headers.ContentType -> MimeTypes.`text/plain`)

    def apply[F[_]: Effect](status: Response.Status, headers: Seq[(String, String)]): F[HttpResponse[F]] = {
      Effect[F].pure(
        Response(
          status = status,
          body = Stream.empty,
          headers = headers,
          contentLength = None
        )
      )
    }

    def apply[F[_]: Effect](status: Response.Status, bodyBytes: Array[Byte], headers: Seq[(String, String)]): F[HttpResponse[F]] = {
      Stream(Bytes.wrap(bodyBytes))
        .mat[F]()
        .map { stream =>
          Response(
            status = status,
            body = stream,
            headers = headers,
            contentLength = Some(bodyBytes.length.toLong)
          )
        }
    }

    def apply[F[_]: Effect](status: Response.Status, text: String, headers: Seq[(String, String)] = defaultHeaders): F[HttpResponse[F]] = {
      val bodyBytes = text.getBytes(StandardCharsets.UTF_8)
      apply(status, bodyBytes, headers)
    }

    def ok[F[_]: Effect](text: String, headers: Seq[(String, String)] = defaultHeaders): F[HttpResponse[F]] = {
      apply(Response.Status.Ok,  text, headers)
    }

    def badRequest[F[_]: Effect](text: String, headers: Seq[(String, String)] = defaultHeaders): F[HttpResponse[F]] = {
      apply(Response.Status.BadRequest, text, headers)
    }

    def seeOther[F[_]: Effect](location: String): F[HttpResponse[F]] =
      apply(Response.Status.SeeOther, headers = Seq(Headers.Location -> location))
  }

  final case class WebSocketRequest[F[_]](httpRequest: Request[Stream[F, Bytes]], protocols: Seq[String])
  final case class WebSocketResponse[F[_]](httpResponse: Response[Stream[F, Bytes]], selectedProtocol: String)

  type StateLoader[F[_], S] = (DeviceId, Head) => F[S]

  def spoonbillService[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
    config: SpoonbillServiceConfig[F, S, M]
  ): SpoonbillService[F] = {

    implicit val exeContext: ExecutionContext = config.executionContext

    val commonService   = new CommonService[F]()
    val filesService    = new FilesService[F](commonService)
    val pageService     = new PageService[F, S, M](config)
    val sessionsService = new SessionsService[F, S, M](config, pageService)
    val messagingService =
      new MessagingService[F](
        config.reporter,
        commonService,
        sessionsService,
        config.compressionSupport,
        config.sessionIdleTimeout
      )
    val formDataCodec = new FormDataCodec
    val postService   = new PostService[F](config.reporter, sessionsService, commonService, formDataCodec)
    val ssrService    = new ServerSideRenderingService[F, S, M](sessionsService, pageService, config)

    new SpoonbillServiceImpl[F](
      config.http,
      commonService,
      filesService,
      messagingService,
      postService,
      ssrService
    )
  }

}
