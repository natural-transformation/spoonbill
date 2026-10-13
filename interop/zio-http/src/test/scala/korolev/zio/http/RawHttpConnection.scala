package spoonbill.zio.http

import java.io.{BufferedInputStream, ByteArrayOutputStream, EOFException, InputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import zio.{Scope, Task, ZIO}

/**
 * A strict HTTP/1.1 client over exactly one TCP connection.
 *
 * It never reconnects or follows redirects, so a second response proves that
 * the first one left the same connection usable. Responses are delimited by
 * status, Content-Length or chunked framing; connection EOF is never treated as
 * the end of a persistent response.
 */
final class RawHttpConnection private (socket: Socket) {
  import RawHttpConnection.*

  private val in: InputStream = new BufferedInputStream(socket.getInputStream)

  /** Send one request and read its complete response. */
  def exchange(
    method: String,
    path: String,
    headers: Seq[(String, String)] = Nil,
    body: Array[Byte] = Array.emptyByteArray
  ): Task[RawResponse] =
    ZIO.attemptBlocking {
      val lengthHeader =
        if (body.nonEmpty || method == "POST") Seq("Content-Length" -> body.length.toString) else Nil
      val fields = (("Host" -> "127.0.0.1") +: (headers ++ lengthHeader)).map { case (name, value) => s"$name: $value" }
      val head   = (s"$method $path HTTP/1.1" +: fields).mkString("", "\r\n", "\r\n\r\n")
      val out    = socket.getOutputStream
      out.write(head.getBytes(StandardCharsets.US_ASCII))
      out.write(body)
      out.flush()
      readResponse(method)
    }

  private def readResponse(method: String): RawResponse = {
    val statusLine = readLine()
    val status = statusLine.split(' ') match {
      case Array(version, code, _*) if version.startsWith("HTTP/1.") => code.toInt
      case _                                                         => throw new IllegalStateException(s"Malformed status line: $statusLine")
    }
    val headers  = readHeaderBlock()
    val bodyless = method == "HEAD" || status / 100 == 1 || status == 204 || status == 304
    val chunked  = header(headers, "Transfer-Encoding").exists(_.toLowerCase.contains("chunked"))
    val body =
      if (bodyless) Array.emptyByteArray
      else if (chunked) readChunked()
      else
        header(headers, "Content-Length") match {
          case Some(length) => readExactly(length.trim.toInt)
          case None         => throw new IllegalStateException(s"Status $status has no persistent framing")
        }
    RawResponse(status, headers, body, chunked)
  }

  private def readChunked(): Array[Byte] = {
    val body = new ByteArrayOutputStream()
    var size = readChunkSize()
    while (size > 0) {
      body.write(readExactly(size))
      if (readLine().nonEmpty) throw new IllegalStateException("Chunk is not followed by CRLF")
      size = readChunkSize()
    }
    readHeaderBlock() // Trailers end with an empty line.
    body.toByteArray
  }

  private def readChunkSize(): Int =
    Integer.parseInt(readLine().takeWhile(_ != ';').trim, 16)

  private def readHeaderBlock(): Seq[(String, String)] =
    Iterator
      .continually(readLine())
      .takeWhile(_.nonEmpty)
      .map { line =>
        val separator = line.indexOf(':')
        if (separator <= 0) throw new IllegalStateException(s"Malformed header: $line")
        line.take(separator).trim -> line.drop(separator + 1).trim
      }
      .toVector

  private def readLine(): String = {
    val line     = new ByteArrayOutputStream()
    var previous = -1
    var current  = in.read()
    while (!(previous == '\r' && current == '\n')) {
      if (current < 0) throw new EOFException("Connection closed inside an HTTP message")
      if (previous >= 0) line.write(previous)
      previous = current
      current = in.read()
    }
    line.toString(StandardCharsets.US_ASCII)
  }

  private def readExactly(length: Int): Array[Byte] = {
    val bytes = in.readNBytes(length)
    if (bytes.length != length) throw new EOFException(s"Expected $length body bytes, got ${bytes.length}")
    bytes
  }
}

object RawHttpConnection {

  final case class RawResponse(status: Int, headers: Seq[(String, String)], body: Array[Byte], chunked: Boolean) {
    def header(name: String): Option[String] = RawHttpConnection.header(headers, name)
    def all(name: String): Seq[String]       = headers.collect { case (key, value) if key.equalsIgnoreCase(name) => value }
    def text: String                         = new String(body, StandardCharsets.UTF_8)
  }

  private def header(headers: Seq[(String, String)], name: String): Option[String] =
    headers.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  /**
   * Open one loopback connection with bounded connect and read timeouts; it
   * closes with the scope.
   */
  def open(port: Int, timeoutMillis: Int = 5000): ZIO[Scope, Throwable, RawHttpConnection] =
    ZIO
      .acquireRelease(ZIO.attemptBlocking {
        val socket = new Socket()
        try {
          socket.setSoTimeout(timeoutMillis)
          socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMillis)
          socket
        } catch {
          case error: Throwable => socket.close(); throw error
        }
      })(socket => ZIO.attemptBlocking(socket.close()).ignore)
      .map(new RawHttpConnection(_))
}
