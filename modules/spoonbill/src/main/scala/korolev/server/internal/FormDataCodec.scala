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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import spoonbill.web.FormData
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuilder

private[spoonbill] final class FormDataCodec {

  import FormDataCodec._

  def decode(source: ByteBuffer, boundary: String): FormData = {

    val boundaryWithBreaks = s"\n--$boundary\n"
    val end                = s"\n--$boundary--\n"

    // Initialize dynamic buffer using ArrayBuilder
    val buffer = ArrayBuilder.make[Byte]

    // Check the delimiter is reached
    def checkDelimiter(delimiter: String): Boolean = {
      val d = delimiter.getBytes
      @tailrec def aux(pos: Int, startPos: Int): Boolean =
        if (pos < d.length) {
          if (source.position() == source.limit()) {
            true
          } else {
            val b = source.get()
            if (b == '\r') aux(pos, startPos)
            else if (b == d(pos)) aux(pos + 1, startPos)
            else {
              source.position(startPos)
              false
            }
          }
        } else true
      aux(0, source.position)
    }

    type Entries = List[FormData.Entry]
    type Headers = List[(String, String)]

    def loop(buffer: ArrayBuilder[Byte], entries: Entries, headers: Headers, state: DecoderState): Entries = {

      def updateEntries() = {
        val content = {
          val bytes = buffer.result()
          ByteBuffer.wrap(bytes)
        }
        val nameOpt = headers collectFirst {
          case (key, value) if key.toLowerCase == "content-disposition" =>
            value
        } flatMap { contentDisposition =>
          contentDisposition.split(';') collectFirst {
            case s if s.indexOf('=') + s.indexOf("name") > -1 =>
              val Array(_, value) = s.split('=')
              value.stripPrefix("\"").stripSuffix("\"")
          }
        }
        nameOpt.fold(entries) { name =>
          val newEntry = FormData.Entry(name, content, headers)
          newEntry :: entries
        }

      }

      state match {
        case _ if source.position() == source.limit() =>
          List.empty[FormData.Entry]
        case Buffering if checkDelimiter(end) =>
          updateEntries()
        case Buffering if checkDelimiter(boundaryWithBreaks) =>
          val updatedEntries = updateEntries()
          buffer.clear()
          loop(buffer, updatedEntries, Nil, Headers)
        case Headers if checkDelimiter("\n\n") =>
          val bytes      = buffer.result()
          val rawHeaders = new String(bytes, StandardCharsets.ISO_8859_1)
          val newHeaders = rawHeaders.split('\n').toList.collect {
            case line if line.contains(":") =>
              val Array(key, value) = line.split(":", 2)
              key.trim -> value.trim
          }
          buffer.clear()
          loop(buffer, entries, newHeaders, Buffering)
        case Buffering | Headers =>
          buffer += source.get()
          loop(buffer, entries, headers, state)
      }
    }

    checkDelimiter(s"--$boundary\n")
    FormData(loop(buffer, Nil, Nil, Headers))
  }
}

private[spoonbill] object FormDataCodec {
  private sealed trait DecoderState
  private case object Buffering extends DecoderState
  private case object Headers   extends DecoderState
}
