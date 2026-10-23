package spoonbill.effect.io

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.atomic.AtomicReference
import spoonbill.data.BytesLike
import spoonbill.data.syntax._
import spoonbill.effect.{Effect, Stream}
import spoonbill.effect.io.DataSocket.CloseReason
import spoonbill.effect.syntax._
import scala.annotation.tailrec
import scala.util.control.NonFatal

sealed class RawDataSocket[F[_]: Effect, B: BytesLike](
  channel: AsynchronousSocketChannel,
  readBuffer: ByteBuffer,
  writeBuffer: ByteBuffer
) extends DataSocket[F, B] {

  /** Open first so callers can own and close a pending TCP connection. */
  private[spoonbill] def connect(address: SocketAddress): F[Unit] = Effect[F].promise { cb =>
    channel.connect(address, (), completionHandler[Void](result => cb(result.map(_ => ()))))
  }

  val stream: Stream[F, B] = new Stream[F, B] {

    def pull(): F[Option[B]] = read(readBuffer).map {
      case -1 => None
      case _ =>
        readBuffer.flip()
        val bytes = BytesLike[B].copyBuffer(readBuffer)
        readBuffer.clear()
        Some(bytes)
    }

    def cancel(): F[Unit] = Effect[F].delay(markClosed(CloseReason.StreamCanceled))
  }

  /** Close the channel after a finished WebSocket so pending reads unblock and
    * the peer observes transport closure. Ordinary stream cancellation does not
    * do this: an HTTP handler may cancel an unused body and still write a response.
    */
  override def shutdown(): F[Unit] = Effect[F].delay {
    shutdownAndNotify()
  }

  /** Server admission must install a reserved dispatcher before any deadlines.
    * No observer, effect construction, or continuation runs on the timer.
    */
  private[spoonbill] def shutdownFromDeadline(): Unit = {
    require(state.get().notifications.nonEmpty, "Deadline shutdown requires reserved close notifications")
    shutdownAndNotify()
  }

  private[spoonbill] def admitCloseNotifications(reservation: SocketCloseDispatcher.Reservation): Boolean = {
    @tailrec def loop(): Boolean = {
      val before = state.get()
      if (before.closed.nonEmpty || before.notifications.nonEmpty) false
      else if (state.compareAndSet(before, before.copy(notifications = Some(reservation)))) true
      else loop()
    }
    loop()
  }

  /** A failed connection launch cannot reach the HTTP serving finalizer. */
  private[io] def markConnectionWorkFinished(afterCleanup: () => Unit): Unit =
    state.get().notifications match {
      case Some(reservation) =>
        reservation.connectionFinished()
        reservation.afterReleased(afterCleanup)
      case None => afterCleanup()
    }

  /** Launch failures must close even when the effect runtime cannot start cleanup. */
  private[io] def shutdownAfterHandlerFailure(): Unit = shutdownAndNotify()

  private def shutdownAndNotify(): Unit = {
    // Claim observers before close so a failed pending NIO read cannot steal
    // their notification, but never invoke them before physical closure.
    val notice = closeState(CloseReason.StreamCanceled)
    try channel.close()
    catch { case NonFatal(_) => () }
    notice.foreach(dispatchClose)
  }

  private def markClosed(reason: CloseReason): Unit = closeState(reason).foreach(dispatchClose)

  private def closeState(reason: CloseReason): Option[CloseNotice] = {
    @tailrec def loop(): Option[CloseNotice] = {
      val ref = state.get()
      if (ref.closed.isEmpty) {
        if (state.compareAndSet(ref, ref.copy(closed = Some(reason), onCloseCbs = Nil, onCloseEffects = Nil)))
          Some(CloseNotice(reason, ref.onCloseCbs, ref.onCloseEffects, ref.notifications))
        else loop()
      } else None
    }
    loop()
  }

  def read(buffer: ByteBuffer): F[Int] = Effect[F].promise[Int] { cb =>
    @tailrec def unsetInProgress(maybeReason: Option[CloseReason]): Unit = {
      val ref = state.get
      if (ref.closed.isEmpty) {
        val newState = ref.copy(inProgress = false, closed = maybeReason,
          onCloseCbs = if (maybeReason.nonEmpty) Nil else ref.onCloseCbs,
          onCloseEffects = if (maybeReason.nonEmpty) Nil else ref.onCloseEffects)
        if (state.compareAndSet(ref, newState)) {
          maybeReason match {
            case Some(reason) =>
              // EOF already forbids further writes in this transport. Release
              // its native handle before any application close notification.
              try channel.close()
              catch { case NonFatal(_) => () }
              dispatchClose(CloseNotice(reason, ref.onCloseCbs, ref.onCloseEffects, ref.notifications))
            case None         => // do nothing
          }
        } else {
          unsetInProgress(maybeReason)
        }
      } else {
        val newState = ref.copy(inProgress = false)
        if (!state.compareAndSet(ref, newState)) {
          unsetInProgress(maybeReason)
        }
      }
    }
    @tailrec def loop(): Unit = {
      val ref = state.get
      if (ref.closed.isEmpty) {
        if (ref.inProgress) {
          cb(Left(new IllegalStateException("This socket already in read state")))
        } else {
          // Get reading lock
          if (state.compareAndSet(ref, ref.copy(inProgress = true))) {
            val handler = new CompletionHandler[Integer, Unit] {
              def completed(result: Integer, attachment: Unit): Unit = {
                if (result == -1) {
                  // EOF
                  unsetInProgress(Some(CloseReason.ByPeer))
                } else {
                  unsetInProgress(None)
                }
                cb(Right(result))
              }
              def failed(exc: Throwable, attachment: Unit): Unit = {
                val reason = Some(CloseReason.Error(exc))
                unsetInProgress(reason)
                cb(Left(exc))
              }
            }
            channel.read(buffer, (), handler)
          } else {
            loop()
          }
        }
      } else {
        cb(Right(-1))
      }
    }
    loop()
  }

  def write(buffer: ByteBuffer): F[Unit] =
    Effect[F].promise { cb =>
      val ref = state.get
      if (ref.closed.isEmpty) {
        val handler = new CompletionHandler[Integer, Unit] {
          def completed(bytesWritten: Integer, notUsed: Unit): Unit =
            if (buffer.hasRemaining) channel.write(buffer, (), this)
            else cb(Right(()))
          def failed(throwable: Throwable, notUsed: Unit): Unit =
            cb(Left(throwable))
        }
        channel.write(buffer, (), handler)
      } else {
        cb(Left(new IllegalStateException(s"Try to write in closed (${ref.closed}) socket")))
      }
    }

  def write(bytes: B): F[Unit] = {
//    writeBuffer.clear()
//    BytesLike[B].copyToBuffer(bytes, writeBuffer)
//    writeBuffer.flip()
//    write(writeBuffer)
    val buffer = bytes.asBuffer // TODO Maybe it should be static allocated buffer
    write(buffer)
  }

  def onClose(): F[CloseReason] = Effect[F].promise[CloseReason] { cb =>
    registerClose(cb)
    ()
  }

  /** Internal upgrade ownership hook. Detaching removes retained callback state.
    * A callback already taken by close may still run; its owner must gate cleanup.
    */
  private[spoonbill] def subscribeClose(callback: CloseReason => Unit): () => Unit =
    registerClose(result => result.foreach(callback))

  /** Application-owned finalization remains part of the reserved cleanup.
    * Late registration awaits cleanup in the caller's serving effect instead.
    */
  private[spoonbill] def subscribeCloseEffect(callback: CloseReason => F[Unit]): F[() => Unit] = Effect[F].delayAsync {
    @tailrec def loop(): Option[CloseReason] = {
      val before = state.get()
      before.closed match {
        case reason @ Some(_) => reason
        case None =>
          if (state.compareAndSet(before, before.copy(onCloseEffects = callback :: before.onCloseEffects))) None
          else loop()
      }
    }
    val detach: () => Unit = () => {
      @tailrec def remove(): Unit = {
        val before = state.get()
        if (before.onCloseEffects.exists(_ eq callback) &&
            !state.compareAndSet(before, before.copy(onCloseEffects = before.onCloseEffects.filterNot(_ eq callback)))) remove()
      }
      remove()
    }
    loop() match {
      case None => Effect[F].pure(detach)
      case Some(reason) => Effect[F].delayAsync(callback(reason)).as(detach)
    }
  }

  private def registerClose(cb: OnCloseCallback): () => Unit = {
    @tailrec def loop(): Unit = {
      val ref = state.get
      ref.closed match {
        case Some(reason) => cb(Right(reason))
        case None =>
          if (!state.compareAndSet(ref, ref.copy(onCloseCbs = cb :: ref.onCloseCbs))) {
            loop()
          }
      }
    }
    loop()
    () => {
      @tailrec def remove(): Unit = {
        val ref = state.get()
        if (ref.onCloseCbs.exists(_ eq cb)) {
          val updated = ref.copy(onCloseCbs = ref.onCloseCbs.filterNot(_ eq cb))
          if (!state.compareAndSet(ref, updated)) remove()
        }
      }
      remove()
    }
  }

  private def dispatchClose(notice: CloseNotice): Unit = {
    def notify(done: () => Unit): Unit = {
      notice.callbacks.foreach { callback =>
        try callback(Right(notice.reason))
        catch { case NonFatal(error) =>
          val thread = Thread.currentThread()
          thread.getUncaughtExceptionHandler.uncaughtException(thread, error)
        }
      }
      val cleanup = notice.effects.foldLeft(Effect[F].unit) { (previous, callback) =>
        previous *> Effect[F].delayAsync(callback(notice.reason)).recover { case NonFatal(_) => () }
      }
      Effect[F].runAsync(cleanup)(_ => done())
    }
    notice.notifications match {
      case Some(reservation) => reservation.dispatch(notify)
      case None => notify(() => ())
    }
  }

  private[io] def closeObserverCount: Int = {
    val current = state.get()
    current.onCloseCbs.size + current.onCloseEffects.size
  }

  private type OnCloseCallback = Effect.Promise[CloseReason]
  private case class CloseNotice(reason: CloseReason, callbacks: List[OnCloseCallback],
    effects: List[CloseReason => F[Unit]], notifications: Option[SocketCloseDispatcher.Reservation])
  private case class State(
    inProgress: Boolean = false,
    closed: Option[CloseReason] = None,
    onCloseCbs: List[OnCloseCallback] = Nil,
    onCloseEffects: List[CloseReason => F[Unit]] = Nil,
    notifications: Option[SocketCloseDispatcher.Reservation] = None
  )

  private val state = new AtomicReference(State())
}

object RawDataSocket {

  private[spoonbill] def open[F[_]: Effect, B: BytesLike](
    readBuffer: ByteBuffer,
    writeBuffer: ByteBuffer,
    group: AsynchronousChannelGroup
  ): RawDataSocket[F, B] =
    new RawDataSocket[F, B](AsynchronousSocketChannel.open(group), readBuffer, writeBuffer)

  def connect[F[_]: Effect, B: BytesLike](
    address: SocketAddress,
    readBuffer: ByteBuffer = ByteBuffer.allocate(8096),
    writeBuffer: ByteBuffer = ByteBuffer.allocate(8096),
    group: AsynchronousChannelGroup = null
  ): F[RawDataSocket[F, B]] =
    Effect[F].delayAsync {
      val socket = open[F, B](readBuffer, writeBuffer, group)
      socket.connect(address).as(socket).recoverF { case NonFatal(error) =>
        socket.shutdown() *> Effect[F].fail(error)
      }
    }
}
