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

package spoonbill.server.internal.services

import spoonbill.effect.Effect
import spoonbill.effect.syntax._
import spoonbill.server.{HttpRequest, HttpResponse, SpoonbillServiceConfig}
import spoonbill.server.internal.{Cookies, Html5RenderContext}
import spoonbill.web.{Headers, PathAndQuery}
import spoonbill.web.Response.Status

private[spoonbill] final class ServerSideRenderingService[F[_]: Effect, S, M](
  sessionsService: SessionsService[F, S, _],
  pageService: PageService[F, S, M],
  config: SpoonbillServiceConfig[F, S, M]
) {

  def canBeRendered(pq: PathAndQuery): Boolean =
    config.router.toState.isDefinedAt(pq)

  def serverSideRenderedPage(request: HttpRequest[F]): F[HttpResponse[F]] =
    for {
      qsid  <- sessionsService.initSession(request)
      state <- sessionsService.initAppState(qsid, request)
      rc     = new Html5RenderContext[F, S, M](config.presetIds)
      proxy  = pageService.setupStatelessProxy(rc, qsid)
      _      = rc.builder.append("<!DOCTYPE html>\n")
      _      = config.document(state)(proxy)
      response <- HttpResponse(
                    Status.Ok,
                    rc.mkString,
                    Seq(
                      Headers.ContentTypeHtmlUtf8,
                      Headers.CacheControlNoCache,
                      Headers.setCookie(
                        Cookies.DeviceId,
                        qsid.deviceId,
                        config.rootPath.mkString,
                        maxAge = 60 * 60 * 24 * 365 * 10 /* 10 years */
                      )
                    )
                  )
    } yield {
      response
    }
}
