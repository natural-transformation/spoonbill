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

import java.io.InputStream
import java.net.{JarURLConnection, URL}
import java.nio.file.{Files, Paths}
import java.util.LinkedHashMap
import scala.concurrent.ExecutionContext
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.syntax._
import spoonbill.server.{HttpResponse, StaticResourceLimits}
import spoonbill.web.{Headers, MimeTypes, PathAndQuery, Response}

private[spoonbill] final class FilesService[F[_]: Effect](
  commonService: CommonService[F],
  limits: StaticResourceLimits = StaticResourceLimits(),
  resolve: String => Option[URL] = FilesService.resolve,
  open: URL => InputStream = FilesService.open
) {
  private case class Asset(chunks: Vector[Bytes], size: Long, headers: Seq[(String, String)])
  private val cache       = new LinkedHashMap[String, Asset](16, 0.75f, true)
  private var cachedBytes = 0L
  private var activeReads = 0

  private[services] def usage: (Int, Long, Int) = synchronized {
    (cache.size(), cachedBytes, activeReads)
  }

  def resourceFromClasspath(pq: PathAndQuery): F[HttpResponse[F]] =
    // Admission and the finite local read finish before honoring cancellation.
    // No request waiters or negative results are retained by this service.
    Effect[F].uncancelable {
      Effect[F].delay {
        FilesService.canonicalPath(pq, limits.maxPathLength).map { path =>
          val admission = synchronized {
            Option(cache.get(path)) match {
              case Some(asset)                                      => Left(Some(asset))
              case None if activeReads >= limits.maxConcurrentReads => Left(None)
              case None =>
                activeReads += 1
                Right(())
            }
          }
          path -> admission
        }
      }.flatMap {
        case None                         => commonService.notFoundResponseF
        case Some((_, Left(Some(asset)))) => response(asset)
        case Some((_, Left(None)))        => unavailable
        case Some((path, Right(_))) =>
          Effect[F].blocking {
            try {
              resolve(path).filter(FilesService.contained(_, path)).map { url =>
                val in = open(url)
                val asset =
                  try readAsset(in, path)
                  finally in.close()
                asset.foreach(retain(path, _))
                asset
              }
            } finally synchronized(activeReads -= 1)
          }(ExecutionContext.global).flatMap {
            case None              => commonService.notFoundResponseF
            case Some(None)        => unavailable
            case Some(Some(asset)) => response(asset)
          }
      }
    }

  private def retain(path: String, asset: Asset): Unit = synchronized {
    if (limits.maxCacheEntries > 0 && asset.size <= limits.maxCacheBytes) {
      Option(cache.remove(path)).foreach(old => cachedBytes -= old.size)
      while (
        !cache.isEmpty &&
        (cache.size() >= limits.maxCacheEntries || cachedBytes + asset.size > limits.maxCacheBytes)
      ) {
        val iterator = cache.entrySet().iterator()
        cachedBytes -= iterator.next().getValue.size
        iterator.remove()
      }
      cache.put(path, asset)
      cachedBytes += asset.size
    }
  }

  private def unavailable: F[HttpResponse[F]] =
    HttpResponse(Response.Status(503, "Service Unavailable"), "Static resource unavailable", Nil)

  private def response(asset: Asset): F[HttpResponse[F]] =
    Stream.emits(asset.chunks).mat[F]().map { stream =>
      Response(Response.Status.Ok, stream, asset.headers, Some(asset.size))
          }

  private def readAsset(in: InputStream, path: String): Option[Asset] = {
    val chunks   = Vector.newBuilder[Bytes]
    val buffer   = new Array[Byte](math.min(8192, limits.maxResourceBytes + 1))
    var total    = 0
    var buffered = 0
    var done     = false
    while (!done) {
      val count = in.read(buffer, buffered, math.min(buffer.length - buffered, limits.maxResourceBytes - total + 1))
      if (count == -1) done = true
      else if (count == 0) {
        // InputStream implementations should not return zero for a nonempty
        // read, but guarantee progress without spinning if one does.
        val next = in.read()
        if (next == -1) done = true
        else {
          total += 1
          if (total > limits.maxResourceBytes) return None
          buffer(buffered) = next.toByte
          buffered += 1
          }
            } else {
        total += count
        if (total > limits.maxResourceBytes) return None
        buffered += count
            }
      if (buffered == buffer.length || done && buffered > 0) {
        chunks += Bytes.wrap(java.util.Arrays.copyOf(buffer, buffered))
        buffered = 0
            }
          }
    val fileName = path.substring(path.lastIndexOf('/') + 1)
    val extension = fileName.lastIndexOf('.') match {
      case -1    => "bin"
      case index => fileName.substring(index + 1)
        }
    val contentType = MimeTypes.typeForExtension.get(extension).toSeq.map(Headers.ContentType -> _)
    val cacheHeaders =
      if (fileName.startsWith("spoonbill-client")) Seq(Headers.CacheControlNoCache, Headers.PragmaNoCache)
      else Nil
    Some(Asset(chunks.result(), total.toLong, contentType ++ cacheHeaders))
  }
}

private[spoonbill] object FilesService {
  private def resolve(path: String): Option[URL] = Option(getClass.getResource(path))

  private def open(url: URL): InputStream = {
    val connection = url.openConnection()
    connection.setUseCaches(false)
    connection.getInputStream
  }

  private def canonicalPath(pq: PathAndQuery, maxLength: Int): Option[String] = {
    // Inspect segments before joining: callers may construct a segment with a
    // slash themselves, and adapters differ in whether they decode escapes.
    var rest     = pq.asPath
    var segments = List.empty[String]
    var length   = 0
    while (rest != PathAndQuery.Root) {
      val part  = rest.asInstanceOf[PathAndQuery./]
      val value = part.value
      length += value.length + 1
      if (
        length > maxLength || value.isEmpty || value == "." || value == ".." ||
        !value.forall(c =>
          c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' ||
            c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~'
        )
      ) return None
      segments = value :: segments
      rest = part.prev
    }
    if (segments.headOption.contains("static") && segments.size > 1) Some(segments.mkString("/", "/", ""))
    else None
  }

  private def contained(url: URL, path: String): Boolean = url.getProtocol match {
    case "file" =>
      val candidate = Paths.get(url.toURI)
      val relative  = path.stripPrefix("/static/")
      val count     = relative.count(_ == '/') + 1
      var root      = candidate
      var remaining = count
      while (remaining > 0 && root != null) {
        root = root.getParent
        remaining -= 1
      }
      root != null && root.getFileName.toString == "static" && Files.isRegularFile(candidate) &&
      candidate.toRealPath() == root.toRealPath().resolve(relative)
    case "jar" =>
      val connection = url.openConnection().asInstanceOf[JarURLConnection]
      connection.getEntryName == path.stripPrefix("/") && connection.getJarFileURL.getProtocol == "file"
    case _ => false
  }
}
