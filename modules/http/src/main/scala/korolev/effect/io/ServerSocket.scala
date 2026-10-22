package spoonbill.effect.io

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousCloseException,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import spoonbill.data.BytesLike
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.effect.{Close, Effect, Stream}
import spoonbill.effect.syntax._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NonFatal

/**
 * Stream API for AsynchronousServerSocketChannel. Use `ServerSocket.bind` to
 * start listening.
 * @see
 *   [[AsynchronousServerSocketChannel]]
 */
class ServerSocket[F[_]: Effect, B: BytesLike](channel: AsynchronousServerSocketChannel, bufferSize: Int)
    extends Stream[F, RawDataSocket[F, B]] {

  @volatile private var canceled = false

  def pull(): F[Option[RawDataSocket[F, B]]] = Effect[F].promise { cb =>
    if (canceled) cb(Right(None))
    else {
      channel.accept(
        (),
        new CompletionHandler[AsynchronousSocketChannel, Unit] {
          def completed(socket: AsynchronousSocketChannel, notUsed: Unit): Unit =
            cb(
              Right(
                Some(new RawDataSocket[F, B](socket, ByteBuffer.allocate(bufferSize), ByteBuffer.allocate(bufferSize)))
              )
            )
          def failed(throwable: Throwable, notUsed: Unit): Unit = throwable match {
            case _: AsynchronousCloseException if canceled =>
              // Its okay. Accepting new connection was
              // stopped by Stream cancel
              cb(Right(None))
            case _ => cb(Left(throwable))
          }
        }
      )
    }
  }

  def cancel(): F[Unit] = Effect[F].delay {
    canceled = true
    channel.close()
  }

  def localAddress: SocketAddress = channel.getLocalAddress
}

object ServerSocket {

  /**
   * Bind server socket to `address` and accept connections with `f`.
   *
   * @see
   *   [[bind]]
   */
  def accept[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null,
    gracefulShutdown: Boolean = false
  )(f: RawDataSocket[F, B] => F[Unit])(implicit ec: ExecutionContext): F[ServerSocketHandler[F]] =
    bind(address, backlog, bufferSize, group = group).flatMap { server =>
      val active = Option.when(gracefulShutdown)(new ActiveConnections)
      val accepting = server.foreach { connection =>
        Effect[F].delay {
          active.foreach(_.started())
          val settled = new AtomicBoolean()
          def finished(error: Option[Throwable]): Unit =
            if (settled.compareAndSet(false, true)) {
              connection.markConnectionWorkFinished(() => active.foreach(_.finished(error)))
            }
          def failed(error: Throwable): Unit = {
            // Closing the transport cannot depend on a runtime that refused
            // launch. A throwing runAsync is required not to retain task ownership.
            try connection.shutdownAfterHandlerFailure()
            catch { case NonFatal(_) => () }
            finally finished(Some(error))
          }
          // Construct the handler before launch: HttpServer reserves bounded
          // close-notification capacity here, including for lazy effects.
          try {
            val task = f(connection)
            Effect[F].runAsync(task) {
              case Right(_) => finished(None)
              case Left(error) => failed(error)
            }
          } catch { case NonFatal(error) => failed(error) }
        }
      }.recoverF { case NonFatal(error) =>
        Effect[F].delay(active.foreach(_.stopped(Some(error)))) *> Effect[F].fail(error)
      } *> Effect[F].delay(active.foreach(_.stopped(None)))
      Effect[F].delayAsync(accepting.start).recoverF { case NonFatal(error) =>
        server.cancel() *> Effect[F].fail(error)
      }.map { serverFiber =>
        new ServerSocketHandler[F] {

          def awaitShutdown(): F[Unit] =
            active match {
              case Some(connections) =>
                serverFiber.join().recover { case NonFatal(_) => () } *> connections.awaitFinished[F]()
              case None => serverFiber.join()
            }

          def stopServingRequests(): F[Unit] =
            server.cancel()

          def localAddress: SocketAddress = server.localAddress
        }
      }
    }

  /** One count and first failure, never a history of completed handler effects. */
  private[io] final class ActiveConnections {
    private var active = 0
    private var accepting = true
    private var failure = Option.empty[Throwable]
    private val done = Promise[Unit]()

    def started(): Unit = synchronized { active += 1 }

    def finished(error: Option[Throwable]): Unit = synchronized {
      active -= 1
      if (failure.isEmpty) failure = error
      completeIfStopped()
    }

    def stopped(error: Option[Throwable]): Unit = synchronized {
      accepting = false
      if (failure.isEmpty) failure = error
      completeIfStopped()
    }

    private def completeIfStopped(): Unit =
      if (!accepting && active == 0) failure match {
        case Some(error) => done.tryFailure(error); ()
        case None => done.trySuccess(()); ()
      }

    def awaitFinished[F[_]: Effect](): F[Unit] = Effect[F].promise { callback =>
      done.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
    }

    private[io] def retainedState: (Int, Int) = synchronized(active -> failure.size)
  }

  /**
   * Open an AsynchronousServerSocketChannel and bind it to `socketAddress`.
   * @see
   *   [[AsynchronousServerSocketChannel]]
   */
  def bind[F[_]: Effect, B: BytesLike](
    socketAddress: SocketAddress,
    backlog: Int = 0,
    bufferSize: Int = 8096,
    group: AsynchronousChannelGroup = null
  ): F[ServerSocket[F, B]] =
    Effect[F].delay {
      val channel = AsynchronousServerSocketChannel
        .open(group)
        .bind(socketAddress, backlog)
      new ServerSocket[F, B](channel, bufferSize)
    }

  sealed trait ServerSocketHandler[F[_]] {

    /**
     * Awaits server socket close. If server configured with graceful shutdown,
     * waits until all client connections are closed.
     */
    def awaitShutdown(): F[Unit]

    /** Address bound by [[bind]], including an ephemeral port. */
    def localAddress: SocketAddress

    /**
     * Stop accepting new connections and serving requests.
     *
     * If you are using server with HTTP note that WebSockets and other request
     * without content length will be open until connection closed by a client.
     */
    def stopServingRequests(): F[Unit]
  }

  object ServerSocketHandler {
    private[spoonbill] def beforeStop[F[_]: Effect](handler: ServerSocketHandler[F], before: () => F[Unit]): ServerSocketHandler[F] =
      new ServerSocketHandler[F] {
        def localAddress: SocketAddress = handler.localAddress
        def awaitShutdown(): F[Unit] = handler.awaitShutdown()
        def stopServingRequests(): F[Unit] = before() *> handler.stopServingRequests()
      }

    implicit def serverSocketHandlerCloseInstance[F[_]: Effect]: Close[F, ServerSocketHandler[F]] =
      new Close[F, ServerSocketHandler[F]] {
        def onClose(that: ServerSocketHandler[F]): F[Unit] =
          that.awaitShutdown()

        def close(that: ServerSocketHandler[F]): F[Unit] =
          that.stopServingRequests()
      }
  }
}
