package spoonbill.http.protocol

import scala.collection.mutable
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{Decoder, Effect, Stream}
import spoonbill.effect.syntax._
import spoonbill.web.{Headers, PathAndQuery, Request, Response}
import spoonbill.web.Response.Status

class Http11[B: BytesLike](
  maxRequestHeaderBytes: Int = 16 * 1024,
  maxChunkLineBytes: Int = 1024,
  maxTrailerBytes: Int = 16 * 1024
) {

  require(maxRequestHeaderBytes >= 4, "maxRequestHeaderBytes must include the four-byte header delimiter")
  require(maxChunkLineBytes >= 3 && maxTrailerBytes >= 2, "Invalid chunk framing limits")

  import Http11._

  private final val NewLine         = "\r\n"
  private final val LastChunk       = Stream(BytesLike[B].ascii("0\r\n\r\n"))
  private final val HeaderDelimiter = BytesLike[B].ascii("\r\n\r\n")
  private final val CRLF            = BytesLike[B].ascii("\r\n")

//  private def printDebug(bytes: ByteVector, prefix: String): Unit = println(
//    prefix + bytes
//      .utf8String
//      .replaceAll("\r", s"${Console.RESET}${Console.YELLOW_B}\\\\r${Console.RESET}${Console.YELLOW}")
//      .replaceAll("\n", s"${Console.RESET}${Console.YELLOW_B}\\\\n${Console.RESET}${Console.YELLOW}\n$prefix")
//  )

  def decodeRequest[F[_]: Effect](
    decoder: Decoder[F, B],
    onBodyStarted: () => Unit = () => (),
    onBodyCompleted: () => Unit = () => ()
  ): Stream[F, Request[Stream[F, B]]] = decoder
    .decode(BytesLike[B].empty)(decodeRequest)
    .map { request =>
      def framed(make: (() => Unit) => Stream[F, B]): Stream[F, B] = {
        var completed = false
        var notified = false
        onBodyStarted()
        val body = make(() => { completed = true })
        new Stream[F, B] {
          def pull(): F[Option[B]] = body.pull().map { value =>
            if (value.isEmpty && !completed) throw new InvalidFraming("Incomplete HTTP request body")
            // Decoder has now installed its take-back tail. Only then can the
            // connection safely move on to a pipelined request.
            if (completed && !notified) { onBodyCompleted(); notified = true }
            value
          }
          def cancel(): F[Unit] = body.cancel()
        }
      }
      val body = request.contentLength match {
          case Some(0) =>
            Stream.empty
          case Some(contentLength) =>
            framed { complete => decoder.decode(0L) { (count, incoming) =>
              val result = decodeLimitedBody(count, incoming, contentLength)
              result._2 match {
                case _: Decoder.Action.PushFinish[?] | _: Decoder.Action.ForkFinish[?, ?] => complete()
                case _ => ()
              }
              result
            } }
          case None if request.header(Headers.TransferEncoding).contains("chunked") =>
            framed(complete => decodeChunkedBody(decoder, complete))
          case None => Stream.empty
      }
      request.copy(body = body)
    }

  def decodeRequest(buffer: B, incoming: B): (B, Decoder.Action[B, Request[Unit]]) = {
    // Inspect only the remaining header budget before concatenating. A socket
    // read may also contain a large body, which is not part of this limit.
    require(buffer.length < maxRequestHeaderBytes, "HTTP request header too large")
    val accepted = math.min(incoming.length, maxRequestHeaderBytes.toLong - buffer.length)
    val allBytes = buffer ++ incoming.slice(0, accepted)
    findLastHeaderEnd(allBytes) match {
      case -1 =>
        require(allBytes.length < maxRequestHeaderBytes, "HTTP request header too large")
        (allBytes, Decoder.Action.TakeNext)
      case lastByteOfHeader =>
        val (bodyBytes, request) = parseRequest(allBytes, lastByteOfHeader)
        (BytesLike[B].empty, Decoder.Action.Fork(request, bodyBytes ++ incoming.slice(accepted)))
    }
  }

  def decodeResponse[F[_]: Effect](decoder: Decoder[F, B]): Stream[F, Response[Stream[F, B]]] = decoder
    .decode(BytesLike[B].empty)(decodeResponse)
    .map { response =>
      response.copy(
        body = response.contentLength match {
          case Some(contentLength) =>
            decoder.decode(0L)(decodeLimitedBody(_, _, contentLength))
          case None if response.header(Headers.TransferEncoding).contains("chunked") =>
            decodeChunkedBody(decoder)
          case None =>
            decoder
        }
      )
    }

  def decodeResponse(buffer: B, incoming: B): (B, Decoder.Action[B, Response[Unit]]) = {
    val allBytes = buffer ++ incoming
    findLastHeaderEnd(allBytes) match {
      case -1 =>
        (allBytes, Decoder.Action.TakeNext)
      case lastByteOfHeader =>
        val (bodyBytes, response) = parseResponse(allBytes, lastByteOfHeader)
        (BytesLike[B].empty, Decoder.Action.Fork(response, bodyBytes))
    }
  }

  def decodeLimitedBody[F[_]: Effect](
    prevBytesTotal: Long,
    incoming: B,
    contentLength: Long
  ): (Long, Decoder.Action[B, B]) =
    prevBytesTotal + incoming.length match {
      case `contentLength` =>
        (contentLength, Decoder.Action.PushFinish(incoming))
      case total if total > contentLength =>
        val pos      = contentLength - prevBytesTotal
        val value    = incoming.slice(0, pos)
        val takeBack = incoming.slice(pos)
        // Push left slice to a downstream
        // and take back right slice to the upstream
        (total, Decoder.Action.ForkFinish(value, takeBack))
      case total =>
        (total, Decoder.Action.Push(incoming))
    }

  def decodeChunkedBody[F[_]: Effect](decoder: Decoder[F, B], complete: () => Unit = () => ()): Stream[F, B] = {
    val framing = new ChunkedBodyDecoder[B](maxChunkLineBytes, maxTrailerBytes)
    val stream = decoder.decode(()) { (_, incoming) =>
      val result = framing.decode(incoming)
      if (framing.completed) complete()
      ((), result)
    }
    new Stream[F, B] {
      def pull(): F[Option[B]] = stream.pull().map { value =>
        if (value.isEmpty && !framing.completed) throw new InvalidFraming("Incomplete chunked HTTP body")
        value
      }
      def cancel(): F[Unit] = stream.cancel()
    }
  }

  def parseRequest(allBytes: B, lastByteOfHeader: Long): (B, Request[Unit]) = {
    require(lastByteOfHeader >= 0 && lastByteOfHeader <= allBytes.length - 4 &&
      allBytes(lastByteOfHeader) == '\r' && allBytes(lastByteOfHeader + 1) == '\n' &&
      allBytes(lastByteOfHeader + 2) == '\r' && allBytes(lastByteOfHeader + 3) == '\n',
      "Malformed HTTP header delimiter")
    val protocolVersionEnd = allBytes.indexOf('\r')
    require(protocolVersionEnd > 0 && protocolVersionEnd <= lastByteOfHeader &&
      allBytes(protocolVersionEnd + 1) == '\n', "Malformed HTTP request line")
    val line = allBytes.slice(0, protocolVersionEnd).asAsciiString
    val methodEnd = line.indexOf(' ')
    val pathEnd = line.indexOf(' ', methodEnd + 1)
    require(methodEnd > 0 && pathEnd > methodEnd + 1 && pathEnd < line.length - 1,
      "Malformed HTTP request line")
    val method = line.substring(0, methodEnd)
    Headers.validate(method, "") // HTTP methods use the same token syntax as field names.
    val target = line.substring(methodEnd + 1, pathEnd)
    require(target.forall(c => c > ' ' && c < 127), "Malformed HTTP request target")
    val version = line.substring(pathEnd + 1)
    require(version == "HTTP/1.1" || version == "HTTP/1.0", "Unsupported HTTP request version")
    val paramsStart = target.indexOf('?')
    val path = if (paramsStart < 0) target else target.substring(0, paramsStart)
    val params = if (paramsStart < 0) null else target.substring(paramsStart + 1)
    // Parse headers.
    val headers               = mutable.Buffer.empty[(String, String)]
    var headerStart           = protocolVersionEnd + 2 // first line end plus \r\n chars
    var cookie: String        = null
    var contentLength: String = null
    var transferEncoding: String = null
    while (headerStart < lastByteOfHeader) {
      // Bound each field to its own CRLF before searching/slicing its value.
      // A missing colon must never send the parser back to the request line.
      val valueEnd = allBytes.indexOf('\r', headerStart)
      require(valueEnd >= headerStart && valueEnd <= lastByteOfHeader && allBytes(valueEnd + 1) == '\n',
        "Malformed HTTP header line")
      val nameEnd  = allBytes.indexOf(':', headerStart)
      require(nameEnd > headerStart && nameEnd < valueEnd, "Malformed HTTP header field")
      val name     = allBytes.slice(headerStart, nameEnd).asAsciiString
      val value =
        if (allBytes(nameEnd + 1) == ' ') allBytes.slice(nameEnd + 2, valueEnd).asAsciiString
        else allBytes.slice(nameEnd + 1, valueEnd).asAsciiString
      Headers.validate(name, value)
      name match {
        case _ if name.equalsIgnoreCase(Headers.Cookie)        => cookie = if (cookie == null) value else cookie + "; " + value
        case _ if name.equalsIgnoreCase(Headers.ContentLength) =>
          if (contentLength != null) throw new InvalidFraming("Repeated Content-Length")
          contentLength = value.trim
        case _ if name.equalsIgnoreCase(Headers.TransferEncoding) =>
          if (transferEncoding != null) throw new InvalidFraming("Repeated Transfer-Encoding")
          transferEncoding = value.trim.toLowerCase(java.util.Locale.ROOT)
        case _                                                 => headers += ((name, value))
      }
      val nextHeader = valueEnd + 2
      require(nextHeader > headerStart, "HTTP header parser made no progress")
      headerStart = nextHeader
    }
    require(headerStart == lastByteOfHeader + 2, "Malformed HTTP header boundary")
    if (transferEncoding != null) {
      if (contentLength != null || transferEncoding != "chunked" || version != "HTTP/1.1")
        throw new InvalidFraming("Ambiguous or unsupported HTTP transfer framing")
      headers += Headers.TransferEncoding -> "chunked"
    }
    val length = Option(contentLength).map { value =>
      if (value.isEmpty || !value.forall(c => c >= '0' && c <= '9'))
        throw new InvalidFraming("Invalid Content-Length")
      value.toLongOption.getOrElse(throw new InvalidFraming("Content-Length overflow"))
    }
    val request = Request(
      method = Request.Method.fromString(method),
      pq = PathAndQuery.fromString(path).withParams(Option(params)),
      renderedCookie = cookie,
      headers = headers.toVector,
      body = (),
      contentLength = length
    )
    val bodyBytes = allBytes.slice(lastByteOfHeader + 4, allBytes.length)
    (bodyBytes, request)
  }

  def findLastHeaderEnd(bytes: B): Long =
    bytes.indexOfSlice(HeaderDelimiter)

  def parseResponse(bytes: B, lastHeaderEnd: Long): (B, Response[Unit]) = {
    // First line
    val protocolVersionEnd = bytes.indexOf(' ')
    val statusCodeStart    = protocolVersionEnd + 1
    val statusCodeEnd      = bytes.indexOf(' ', statusCodeStart)
    val statusPhraseStart  = statusCodeEnd + 1
    val statusPhraseEnd    = bytes.indexOf('\r', statusPhraseStart)
    val statusCode         = bytes.slice(statusCodeStart, statusCodeEnd)
    val statusPhrase       = bytes.slice(statusPhraseStart, statusPhraseEnd)
    // Headers
    var headerStart           = statusPhraseEnd + 2
    val headers               = mutable.Buffer.empty[(String, String)]
    var cookie: String        = null
    var contentLength: String = null
    while (headerStart < lastHeaderEnd) {
      val nameEnd  = bytes.indexOf(':', headerStart)
      val valueEnd = bytes.indexOf('\r', nameEnd)
      val name     = bytes.slice(headerStart, nameEnd).asAsciiString
      val value =
        if (bytes(nameEnd + 1) == ' ') bytes.slice(nameEnd + 2, valueEnd).asAsciiString
        else bytes.slice(nameEnd + 1, valueEnd).asAsciiString
      name match {
        case _ if name.equalsIgnoreCase(Headers.Cookie) =>
          cookie = value
          headers += ((name, value))
        case _ if name.equalsIgnoreCase(Headers.ContentLength) =>
          contentLength = value
        case _ =>
          headers += ((name, value))
      }
      headerStart = valueEnd + 2
    }
    val response = Response(
      status = Status(statusCode.asAsciiString.toInt, statusPhrase.asAsciiString),
      headers = headers.toVector,
      contentLength =
        if (contentLength == null) None
        else Some(contentLength.toLong),
      body = ()
    )
    val bodyBytes = bytes.slice(lastHeaderEnd + 4, bytes.length)
    (bodyBytes, response)
  }

  def renderResponseHeader(status: Status, headers: Seq[(String, String)]): String = {
    Headers.validateValue(status.phrase)
    headers.foreach { case (name, value) => Headers.validate(name, value) }
    val builder = new java.lang.StringBuilder()

    builder
      .append("HTTP/1.1 ")
      .append(status.codeAsString)
      .append(' ')
      .append(status.phrase)
      .append(NewLine)
    def putHeader(name: String, value: String) = builder
      .append(name)
      .append(':')
      .append(' ')
      .append(value)
      .append(NewLine)
    headers.foreach { case (name, value) =>
      putHeader(name, value)
    }

    builder
      .append(NewLine)
      .toString
  }

  def renderRequestHead[T](request: Request[T]): String = {
    val sb = new StringBuilder()
      .appendAll(request.method.value)
      .append(' ')
    request.pq.mkString(sb)
    sb
      .append(' ')
      .appendAll("HTTP/1.1")
      .newLine()
    if (request.renderedCookie != null && request.renderedCookie.nonEmpty)
      sb
        .appendAll(Headers.Cookie)
        .appendAll(": ")
        .appendAll(request.renderedCookie)
        .newLine()
    request.contentLength match {
      case None => ()
      case Some(contentLength) =>
        sb
          .appendAll(Headers.ContentLength)
          .appendAll(": ")
          .append(contentLength)
          .newLine()
    }
    request.headers.foreach { case (k, v) =>
      sb
        .appendAll(k)
        .appendAll(": ")
        .appendAll(v)
        .newLine()
    }
    sb
      .newLine()
      .mkString
  }
  def renderRequest[F[_]: Effect](request: Request[Stream[F, B]]): F[Stream[F, B]] = {
    val head = renderRequestHead(request)
    Stream(BytesLike[B].ascii(head))
      .mat()
      .map(_ ++ request.body) // TODO add chunked encoding
  }

  def renderResponse[F[_]: Effect](response: Response[Stream[F, B]]): F[Stream[F, B]] = {
    def go(readers: Seq[(String, String)], body: Stream[F, B]) =
      Effect[F].delay {
      val fullHeaderString = renderResponseHeader(response.status, readers)
        BytesLike[B].ascii(fullHeaderString)
      }.flatMap(bytes => Stream(bytes).mat().map(_ ++ body))
    response.contentLength match {
      case Some(s)                                              => go((Headers.ContentLength -> s.toString) +: response.headers, response.body)
      case None if response.status == Status.SwitchingProtocols => go(response.headers, response.body)
      case None if response.header(Headers.TransferEncoding).contains("chunked") =>
        val chunkedBody = response.body.map { chunk =>
          BytesLike[B].ascii(chunk.length.toHexString) ++
            CRLF ++
            chunk ++
            CRLF
        }
        LastChunk
          .mat()
          .flatMap { lastChunk =>
            go(response.headers, chunkedBody ++ lastChunk)
          }
      case None =>
        println("NOT CHUNKED BUT NONE")
        response.headers.foreach(println)
        Effect[F].pure(Stream.empty)
    }
  }
}

object Http11 {

  final class InvalidFraming(message: String) extends IllegalArgumentException(message)

  private implicit final class StringBuilderOps(val builder: StringBuilder) extends AnyVal {
    def newLine(): StringBuilder = builder
      .append('\r')
      .append('\n')
  }
}
