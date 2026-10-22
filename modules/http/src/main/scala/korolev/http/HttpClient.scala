package spoonbill.http

import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executor, Executors}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal
import spoonbill.data.BytesLike
import spoonbill.effect.{AsyncResourcePool, Decoder, Effect, Reporter, Scheduler, Stream}
import spoonbill.effect.AsyncResourcePool.Borrow
import spoonbill.effect.io.{DataSocket, RawDataSocket, SecureDataSocket}
import spoonbill.effect.syntax._
import spoonbill.http.protocol.{Http11, WebSocketProtocol}
import spoonbill.http.protocol.WebSocketProtocol.Frame
import spoonbill.web.{Headers, PathAndQuery, Request, Response}

class HttpClient[F[_]: Effect, B: BytesLike] private (
  name: String,
  maxIdleTime: FiniteDuration,
  maxConnectionsPerAddress: Int,
  blockingExecutor: Executor,
  group: AsynchronousChannelGroup,
  bufferSize: Int,
  sslContext: SSLContext,
  cleanupTicks: Stream[F, Unit],
  maxWebSocketMessageBytes: Int
)(implicit executor: ExecutionContext, reporter: Reporter) {
  def apply(
    method: Request.Method,
    uri: URI,
    headers: Seq[(String, String)],
    contentLength: Option[Long],
    body: Stream[F, B]
  ): F[Response[Stream[F, B]]] = {
    val updatedHeaders =
      if (uri.getUserInfo != null) Headers.basicAuthorization(uri.getUserInfo) +: headers
      else headers
    val request = Request(method, pqFromUri(uri), updatedHeaders, contentLength, body)

    uri.getScheme match {
      case "http" if uri.getPort == -1  => http(new InetSocketAddress(uri.getHost, 80), request)
      case "https" if uri.getPort == -1 => https(new InetSocketAddress(uri.getHost, 443), request)
      case "http"                       => http(new InetSocketAddress(uri.getHost, uri.getPort), request)
      case "https"                      => https(new InetSocketAddress(uri.getHost, uri.getPort), request)
      case "ws" | "wss"                 => Effect[F].fail(new IllegalArgumentException(s"Use HttpClient.webSocket() of HttpClient()"))
      case scheme                       => Effect[F].fail(new IllegalArgumentException(s"$scheme is not supported"))
    }
  }

  def https(address: InetSocketAddress, request: Request[Stream[F, B]]): F[Response[Stream[F, B]]] =
    for {
      borrow   <- takeSecureConnection(address)
      response <- http(borrow, address.getHostName, request)
    } yield response

  def http(address: InetSocketAddress, request: Request[Stream[F, B]]): F[Response[Stream[F, B]]] =
    for {
      borrow   <- takeRawConnection(address)
      response <- http(borrow, address.getHostName, request)
    } yield response

  private def http(
    borrow: Borrow[F, DataSocket[F, B]],
    host: String,
    request: Request[Stream[F, B]],
    recycleAfterBody: Boolean = true
  ): F[Response[Stream[F, B]]] =
    for {
      requestStream <- http11.renderRequest(request.withHeader(Headers.Host, host))
      socket         = borrow.value
      writeBytesFiber <- requestStream.foreach(socket.write).start // Write response asynchronously
      maybeResponse <- http11.decodeResponse(Decoder(socket.stream)).pull()
      response <-
        maybeResponse match {
          case Some(response) if !recycleAfterBody => Effect[F].pure(response)
          case Some(response) =>
            val (consumed, consumableBody) = response.body.handleConsumed
            consumed
              .after(writeBytesFiber.join())
              // Give back connection when body was consumed
              .after(borrow.give())
              .start
              .as(response.copy(body = consumableBody))
          case None =>
            Effect[F].fail[Response[Stream[F, B]]](
              new IllegalStateException("Peer has closed connection before sending response.")
            )
        }
    } yield {
      response
    }

  def secureWebSocket(
    address: InetSocketAddress,
    path: PathAndQuery,
    outgoingFrames: Stream[F, WebSocketProtocol.Frame[B]],
    cookie: Map[String, String],
    headers: Map[String, String]
  ): F[Response[Stream[F, Frame.Merged[B]]]] =
    for {
      borrow <- takeSecureConnection(address)
      frames <- webSocket(borrow, address.getHostName, path, outgoingFrames, cookie, headers)
    } yield frames

  def webSocket(
    uri: URI,
    outgoingFrames: Stream[F, WebSocketProtocol.Frame[B]],
    cookie: Map[String, String],
    headers: Map[String, String]
  ): F[Response[Stream[F, Frame.Merged[B]]]] = {
    val pq = pqFromUri(uri)
    val updatedHeaders =
      if (uri.getUserInfo != null) headers + Headers.basicAuthorization(uri.getUserInfo)
      else headers

    uri.getScheme match {
      case "ws" if uri.getPort == -1 =>
        webSocket(new InetSocketAddress(uri.getHost, 80), pq, outgoingFrames, cookie, updatedHeaders)
      case "wss" if uri.getPort == -1 =>
        secureWebSocket(new InetSocketAddress(uri.getHost, 443), pq, outgoingFrames, cookie, updatedHeaders)
      case "ws" =>
        webSocket(new InetSocketAddress(uri.getHost, uri.getPort), pq, outgoingFrames, cookie, updatedHeaders)
      case "wss" =>
        secureWebSocket(new InetSocketAddress(uri.getHost, uri.getPort), pq, outgoingFrames, cookie, updatedHeaders)
      case "http" | "https" =>
        Effect[F].fail(new IllegalArgumentException(s"Use HttpClient.http() of HttpClient.webSocket()"))
      case scheme => Effect[F].fail(new IllegalArgumentException(s"$scheme is not supported"))
    }
  }

  def webSocket(
    address: InetSocketAddress,
    path: PathAndQuery,
    outgoingFrames: Stream[F, WebSocketProtocol.Frame[B]],
    cookie: Map[String, String],
    headers: Map[String, String]
  ): F[Response[Stream[F, Frame.Merged[B]]]] =
    for {
      borrow <- takeRawConnection(address)
      frames <- webSocket(borrow, address.getHostName, path, outgoingFrames, cookie, headers)
    } yield frames

  private def webSocket(
    borrow: Borrow[F, DataSocket[F, B]],
    host: String,
    path: PathAndQuery,
    outgoingFrames: Stream[F, WebSocketProtocol.Frame[B]],
    cookie: Map[String, String],
    headers: Map[String, String]
  ): F[Response[Stream[F, WebSocketProtocol.Frame.Merged[B]]]] = {
    val closed = new AtomicBoolean(false)
    def close(): F[Unit] = Effect[F].uncancelable {
      Effect[F].delayAsync {
        if (!closed.compareAndSet(false, true)) Effect[F].unit
        else borrow.value.shutdown().recover { case NonFatal(_) => () } *>
          outgoingFrames.cancel().recover { case NonFatal(_) => () } *>
          borrow.discard()
      }
    }
    val response = for {
      intention            <- WebSocketProtocol.Intention.random
      encodedOutgoingFrames = outgoingFrames.map(frame => webSocketProtocol.encodeFrame(frame, Some(123)))
      requestRaw            = Request(Request.Method.Get, path, headers.toSeq, None, encodedOutgoingFrames)
      requestWithCookie     = cookie.foldLeft(requestRaw) { case (acc, (k, v)) => acc.withCookie(k, v) }
      requestWithIntention  = webSocketProtocol.addIntention(requestWithCookie, intention)
      rawResponse          <- http(borrow, host, requestWithIntention, recycleAfterBody = false)
      frameDecoder          = Decoder(rawResponse.body)
      frames                = webSocketProtocol.mergeFrames(webSocketProtocol.decodeFrames(frameDecoder))
    } yield {
      val boundedInput = new Stream[F, Frame.Merged[B]] {
        def pull(): F[Option[Frame.Merged[B]]] = frames.pull().flatMap {
          case None => close().start.as(None)
          case value => Effect[F].pure(value)
        }.recoverF { case NonFatal(error) =>
          // A user output finalizer may await this read; settle the read first.
          close().start *> Effect[F].fail(error)
        }
        def cancel(): F[Unit] = close()
      }
      rawResponse.copy(body = boundedInput)
    }
    response.recoverF { case NonFatal(error) => close().start *> Effect[F].fail(error) }
  }

  private def pqFromUri(uri: URI) = {
    val path  = if (uri.getPath == null) "" else uri.getPath
    val query = if (uri.getQuery == null) "" else uri.getQuery
    PathAndQuery.fromString(s"$path$query")
  }

  private def takeRawConnection(address: InetSocketAddress) = {
    def factory = RawDataSocket.connect(
      address,
      readBuffer = ByteBuffer.allocate(bufferSize),
      writeBuffer = ByteBuffer.allocate(bufferSize),
      group
    )
    val pool = rawConnectionsPools.getOrElseUpdate(
      address,
      new AsyncResourcePool(
        s"$name-raw-socket-pool",
        factory,
        () => Effect[F].delay(System.nanoTime()),
        maxConnectionsPerAddress,
        maxIdleTime
      )
    )
    pool.borrow()
  }

  private def takeSecureConnection(address: InetSocketAddress) = {
    def factory =
      for {
        rawSocket <- RawDataSocket.connect(
                       address,
                       readBuffer = ByteBuffer.allocate(bufferSize),
                       writeBuffer = ByteBuffer.allocate(bufferSize),
                       group
                     )
        _       = reporter.debug("%s - Connection established", name)
        engine  = sslContext.createSSLEngine(address.getHostString, address.getPort)
        _ = {
          val parameters = engine.getSSLParameters
          parameters.setEndpointIdentificationAlgorithm("HTTPS")
          engine.setSSLParameters(parameters)
        }
        socket <- SecureDataSocket.forClientMode(rawSocket, engine, blockingExecutor)
        _       = reporter.debug("%s - TLS handshake finished", name)
      } yield socket
    val pool = secureConnectionsPools.getOrElseUpdate(
      // InetSocketAddress equality collapses names sharing an IP; TLS identity must not.
      (address.getHostString.toLowerCase(java.util.Locale.ROOT), address),
      new AsyncResourcePool(
        s"$name-tls-socket-pool",
        factory,
        () => Effect[F].delay(System.nanoTime()),
        maxConnectionsPerAddress,
        maxIdleTime
      )
    )
    pool.borrow()
  }

  cleanupTicks.foreach { _ =>
    (rawConnectionsPools.values ++ secureConnectionsPools.values).toList
      .map(_.cleanup())
      .sequence
      .map { results =>
        val sum = results.sum
        if (sum > 0) {
          reporter.debug("HttpClient(%s) closes %d idle connection after timeout", name, sum)
        }
      }
  }.runAsyncForget

  private val http11                 = new Http11[B]()
  private val webSocketProtocol      = new WebSocketProtocol[B](maxWebSocketMessageBytes)
  private val rawConnectionsPools    = TrieMap.empty[InetSocketAddress, AsyncResourcePool[F, RawDataSocket[F, B]]]
  private val secureConnectionsPools = TrieMap.empty[(String, InetSocketAddress), AsyncResourcePool[F, SecureDataSocket[F, B]]]
}

object HttpClient {

  private lazy val defaultBlockingExecutor = Executors.newCachedThreadPool()

  def create[F[_]: Effect: Scheduler, B: BytesLike](
    name: String = null,
    maxIdleTime: FiniteDuration = 10.seconds,
    maxConnectionsPerAddress: Int = 8,
    poolCleanupInterval: FiniteDuration = 11.seconds,
    blockingExecutor: Executor = null,
    group: AsynchronousChannelGroup = null,
    incomingBufferSize: Int = 8096,
    sslContext: SSLContext = SSLContext.getDefault,
    maxWebSocketMessageBytes: Int = 16 * 1024 * 1024
  )(implicit executor: ExecutionContext, reporter: Reporter): F[HttpClient[F, B]] =
    for {
      _     <- Effect[F].delay(require(maxWebSocketMessageBytes > 0, "maxWebSocketMessageBytes must be positive"))
      ticks <- Scheduler[F].schedule(poolCleanupInterval)
    } yield {
      val safeName = Option(name).getOrElse(s"${Random.alphanumeric.take(5).mkString}-http-client")
      val updatedExecutor =
        if (blockingExecutor != null) blockingExecutor
        else defaultBlockingExecutor
      new HttpClient(
        safeName,
        maxIdleTime,
        maxConnectionsPerAddress,
        updatedExecutor,
        group,
        incomingBufferSize,
        sslContext,
        ticks,
        maxWebSocketMessageBytes
      )
    }
}
