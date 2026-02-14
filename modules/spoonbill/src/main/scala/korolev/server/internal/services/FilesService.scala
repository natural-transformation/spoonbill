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

import spoonbill.data.Bytes
import spoonbill.effect.{AsyncTable, Effect, Stream}
import spoonbill.effect.io.JavaIO
import spoonbill.effect.syntax._
import spoonbill.server.HttpResponse
import spoonbill.web.{Headers, MimeTypes, Path, PathAndQuery, Response}
import spoonbill.web.PathAndQuery._

private[spoonbill] final class FilesService[F[_]: Effect](commonService: CommonService[F]) {

  type ResponseFactory = () => F[HttpResponse[F]]

  private val table = AsyncTable.unsafeCreateEmpty[F, Path, ResponseFactory]

  private val notFoundToken = Effect[F].pure(() => commonService.notFoundResponseF)

  def resourceFromClasspath(pq: PathAndQuery): F[HttpResponse[F]] = {
    val path = pq.asPath
    table
      .getFill(path) {
        val fsPath              = path.mkString
        val maybeResourceStream = Option(this.getClass.getResourceAsStream(fsPath))
        maybeResourceStream.fold(notFoundToken) { javaSyncStream =>
          val fileName = path match {
            case p: PathAndQuery./ => p.value
            case Root              => ""
          }
          val fileExtension = fileName.lastIndexOf('.') match {
            case -1    => "bin" // default file extension
            case index => fileName.substring(index + 1)
          }
          val contentTypeHeader = MimeTypes.typeForExtension.get(fileExtension) match {
            case Some(mimeType) => Seq(Headers.ContentType -> mimeType)
            case None           => Nil
          }
          // Avoid stale Spoonbill client JS after local publish or upgrades.
          val cacheHeaders =
            if (fileName.startsWith("spoonbill-client")) {
              Seq(Headers.CacheControlNoCache, Headers.PragmaNoCache)
            } else {
              Nil
            }
          val headers = contentTypeHeader ++ cacheHeaders
          val size = javaSyncStream.available().toLong
          for {
            stream <- JavaIO.fromInputStream[F, Bytes](javaSyncStream) // TODO configure chunk size
            chunks  <- stream.fold(Vector.empty[Bytes])(_ :+ _)
            template = Stream.emits(chunks)
          } yield { () =>
            template.mat() map { stream =>
              Response(Response.Status.Ok, stream, headers, Some(size))
            }
          }
        }
      }
      .flatMap(_())
  }
}
