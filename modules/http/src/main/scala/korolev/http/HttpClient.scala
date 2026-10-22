package spoonbill.http

import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executor, Executors, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLContext
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{AsyncResourcePool, Close, Decoder, Effect, Reporter, Scheduler, Stream}
import spoonbill.effect.AsyncResourcePool.Borrow
import spoonbill.effect.io.{DataSocket, RawDataSocket, SecureDataSocket, SocketCloseDispatcher}
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
  maxWebSocketMessageBytes: Int,
  maxResponseHeaderBytes: Int,
  responseHeaderTimeout: FiniteDuration,
  maxTotalConnections: Int,
  closeNotificationWorkers: Int
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
    borrow: Borrow[F, ClientConnection],
    host: String,
    request: Request[Stream[F, B]],
    recycleAfterBody: Boolean = true
  ): F[Response[Stream[F, B]]] = Effect[F].delayAsync {
    val connection = borrow.value
    val writer = Promise[Unit]()
    val failure = new AtomicReference[Throwable]()
    val settled = new AtomicBoolean()
    val retired = new AtomicBoolean()
    val deadline = new RequestDeadline(responseHeaderTimeout, () => connection.abort())
    def writerDone: F[Unit] = awaitPromise(writer)
    // This is invoked only by the reserved close dispatcher. It may await the
    // failed response itself; publishing that response never waits for cleanup.
    val cleanup = () => Effect[F].delayAsync(request.body.cancel()).recover { case NonFatal(_) => () } *>
      writerDone.recover { case NonFatal(_) => () }
    def discard(): Unit = {
      connection.abort()
      if (retired.compareAndSet(false, true)) borrow.discard().runAsyncForget
    }
    def failed(error: Throwable): Unit = {
      failure.compareAndSet(null, error)
      discard()
    }
    def guarded(response: Response[Stream[F, B]]): Response[Stream[F, B]] = {
      if (!recycleAfterBody) response
      else {
        var count = 0L
        val finished = new AtomicBoolean()
        val closes = response.headers.exists { case (name, value) =>
          name.equalsIgnoreCase(Headers.Connection) && value.split(',').exists(_.trim.equalsIgnoreCase("close"))
        }
        val reusable = !closes && (response.contentLength.isDefined || response.header(Headers.TransferEncoding).contains("chunked"))
        response.copy(body = new Stream[F, B] {
          def pull(): F[Option[B]] = Effect[F].delayAsync {
            Option(failure.get()) match {
              case Some(error) => Effect[F].fail(error)
              case None if finished.get() => Effect[F].none
              case None => response.body.pull().flatMap {
                case value @ Some(bytes) => count += bytes.length; Effect[F].pure(value)
                case None =>
                  if (response.contentLength.exists(_ != count))
                    Effect[F].fail(new IllegalStateException("Incomplete HTTP response body"))
                  else writerDone.flatMap { _ =>
                    if (finished.compareAndSet(false, true)) {
                      if (reusable && connection.finish(cleanup)) borrow.give().as(None)
                      else { discard(); Effect[F].none }
                    } else Effect[F].none
                  }
              }
            }
          }.recoverF { case NonFatal(error) => failed(error); Effect[F].fail(error) }
          def cancel(): F[Unit] = Effect[F].delayAsync {
            if (finished.compareAndSet(false, true)) { discard(); borrow.discard() }
            else Effect[F].unit
          }
        })
      }
    }
    Effect[F].promise[Response[Stream[F, B]]] { callback =>
      var writerObserved = false
      var deadlineStarted = false
      def complete(result: Either[Throwable, Response[Stream[F, B]]]): Unit = {
        if (settled.compareAndSet(false, true)) {
          val timely = if (deadlineStarted) deadline.complete() else { deadline.close(); true }
          val outcome = if (!timely) Left(new TimeoutException("HTTP response header deadline exceeded")) else result
          outcome match {
            case Left(error) => failed(error)
            case Right(_) => ()
          }
          callback(outcome)
        } else result.left.foreach(failed)
      }
      try {
        connection.begin(cleanup)
        deadline.start()
        deadlineStarted = true
        Effect[F].start {
          http11.renderRequest(request.withHeader(Headers.Host, host)).flatMap(_.foreach(connection.socket.write))
        }.flatMap(_.join()).runAsync { result =>
          writer.tryComplete(result.toTry)
          result.left.foreach(error => complete(Left(error)))
        }
        writerObserved = true
        http11.decodeResponse(Decoder(connection.socket.stream)).pull().runAsync {
          case Right(Some(response)) => complete(Right(guarded(response)))
          case Right(None) => complete(Left(new IllegalStateException("Peer has closed connection before sending response.")))
          case Left(error) => complete(Left(error))
        }
      } catch { case NonFatal(error) =>
        // A synchronous launch failure means the runtime did not take ownership.
        if (!writerObserved) writer.tryFailure(error)
        complete(Left(error))
      }
    }
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
    borrow: Borrow[F, ClientConnection],
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
        else { borrow.value.abort(); borrow.discard() }
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

  private def awaitPromise[A](promise: Promise[A]): F[A] = Effect[F].promise { callback =>
    promise.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
  }

  /** The slot belongs to the physical connection, including its finalizers. */
  private class ClientConnection(val raw: RawDataSocket[F, B], val reservation: SocketCloseDispatcher.Reservation) {
    var socket: DataSocket[F, B] = raw
    private var closed = false
    private var active: Option[() => F[Unit]] = None
    def begin(cleanup: () => F[Unit]): Unit = synchronized {
      require(!closed && active.isEmpty, "HTTP connection is closed or already in use")
      active = Some(cleanup)
    }
    def finish(cleanup: () => F[Unit]): Boolean = synchronized {
      if (!closed && active.contains(cleanup)) { active = None; true } else false
    }
    def onPhysicalClose(): F[Unit] = Effect[F].delayAsync {
      val cleanup = synchronized { closed = true; val previous = active; active = None; previous }
      cleanup.fold(Effect[F].unit)(_.apply())
    }
    def abort(): Unit = raw.shutdownFromDeadline()
  }

  private implicit val connectionClose: Close[F, ClientConnection] = new Close[F, ClientConnection] {
    def onClose(connection: ClientConnection): F[Unit] =
      connection.raw.onClose().flatMap(_ => connection.reservation.awaitReleased[F]())
    def close(connection: ClientConnection): F[Unit] =
      Effect[F].delay(connection.abort()).flatMap(_ => connection.reservation.awaitReleased[F]())
  }

  private def connect(address: InetSocketAddress, secure: Boolean): F[ClientConnection] = Effect[F].delayAsync {
    closeDispatcher.reserve() match {
      case None => Effect[F].fail(new IllegalStateException("HTTP client connection and cleanup capacity exhausted"))
      case Some(reservation) =>
        var connection: Option[ClientConnection] = None
        RawDataSocket.connect[F, B](address, ByteBuffer.allocate(bufferSize), ByteBuffer.allocate(bufferSize), group)
          .flatMap { raw =>
            if (!raw.admitCloseNotifications(reservation)) {
              reservation.cancel()
              raw.shutdown() *> Effect[F].fail(new IllegalStateException("HTTP connection closed during creation"))
            } else {
              reservation.connectionFinished()
              val value = new ClientConnection(raw, reservation)
              connection = Some(value)
              raw.subscribeCloseEffect(_ => value.onPhysicalClose()).flatMap { _ =>
                if (!secure) Effect[F].pure(value)
                else {
                  val engine = sslContext.createSSLEngine(address.getHostString, address.getPort)
                  val parameters = engine.getSSLParameters
                  parameters.setEndpointIdentificationAlgorithm("HTTPS")
                  engine.setSSLParameters(parameters)
                  SecureDataSocket.forClientMode(raw, engine, blockingExecutor).map { socket =>
                    value.socket = socket
                    value
                  }
                }
              }
            }
          }.recoverF { case NonFatal(error) =>
            connection match {
              case Some(value) => connectionClose.close(value) *> Effect[F].fail(error)
              case None => reservation.cancel(); Effect[F].fail(error)
            }
          }
    }
  }

  private def takeRawConnection(address: InetSocketAddress) = {
    val pool = rawConnectionsPools.getOrElseUpdate(
      address,
      new AsyncResourcePool(
        s"$name-raw-socket-pool",
        connect(address, secure = false),
        () => Effect[F].delay(System.nanoTime()),
        maxConnectionsPerAddress,
        maxIdleTime
      )
    )
    pool.borrow()
  }

  private def takeSecureConnection(address: InetSocketAddress) = {
    val pool = secureConnectionsPools.getOrElseUpdate(
      // InetSocketAddress equality collapses names sharing an IP; TLS identity must not.
      (address.getHostString.toLowerCase(java.util.Locale.ROOT), address),
      new AsyncResourcePool(
        s"$name-tls-socket-pool",
        connect(address, secure = true),
        () => Effect[F].delay(System.nanoTime()),
        maxConnectionsPerAddress,
        maxIdleTime
      )
    )
    pool.borrow()
  }

  private val closeDispatcher       = new SocketCloseDispatcher(maxTotalConnections, closeNotificationWorkers)
  private val http11                = new Http11[B](maxResponseHeaderBytes = maxResponseHeaderBytes)
  private val webSocketProtocol     = new WebSocketProtocol[B](maxWebSocketMessageBytes)
  private val rawConnectionsPools   = TrieMap.empty[InetSocketAddress, AsyncResourcePool[F, ClientConnection]]
  private val secureConnectionsPools = TrieMap.empty[(String, InetSocketAddress), AsyncResourcePool[F, ClientConnection]]

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

}

object HttpClient {

  private lazy val defaultBlockingExecutor = Executors.newCachedThreadPool()

  /** Response-header limits apply after a connection is borrowed, excluding
    * pool wait and TLS setup. maxTotalConnections bounds physical connections
    * across destinations, including those awaiting request-body cleanup; new
    * connections fail admission when that capacity is exhausted. A response
    * failure is published without waiting for user cleanup.
    */
  def create[F[_]: Effect: Scheduler, B: BytesLike](
    name: String = null,
    maxIdleTime: FiniteDuration = 10.seconds,
    maxConnectionsPerAddress: Int = 8,
    poolCleanupInterval: FiniteDuration = 11.seconds,
    blockingExecutor: Executor = null,
    group: AsynchronousChannelGroup = null,
    incomingBufferSize: Int = 8096,
    sslContext: SSLContext = SSLContext.getDefault,
    maxWebSocketMessageBytes: Int = 16 * 1024 * 1024,
    maxResponseHeaderBytes: Int = 16 * 1024,
    responseHeaderTimeout: FiniteDuration = 30.seconds,
    maxTotalConnections: Int = 1024,
    closeNotificationWorkers: Int = 2
  )(implicit executor: ExecutionContext, reporter: Reporter): F[HttpClient[F, B]] =
    for {
      _     <- Effect[F].delay(require(maxWebSocketMessageBytes > 0, "maxWebSocketMessageBytes must be positive"))
      _ <- Effect[F].delay {
        require(maxResponseHeaderBytes >= 4, "maxResponseHeaderBytes must include the header delimiter")
        require(responseHeaderTimeout > Duration.Zero, "responseHeaderTimeout must be positive")
        require(maxTotalConnections > 0 && closeNotificationWorkers > 0 && maxConnectionsPerAddress > 0,
          "HTTP connection and cleanup limits must be positive")
      }
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
        maxWebSocketMessageBytes,
        maxResponseHeaderBytes,
        responseHeaderTimeout,
        maxTotalConnections,
        closeNotificationWorkers
      )
    }
}
