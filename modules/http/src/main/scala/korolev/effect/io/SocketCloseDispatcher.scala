package spoonbill.effect.io

import java.util.concurrent.{ArrayBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NonFatal
import spoonbill.effect.{DrainGateMachine, Effect, StateCell}

/** Per-server admission reserves notification capacity before user work starts.
  * A slow finalizer can exhaust admission, but cannot block physical deadlines
  * or grow an unbounded executor queue. There is no caller-runs fallback.
  */
private[spoonbill] final class SocketCloseDispatcher(capacity: Int, workers: Int) {
  require(capacity > 0 && workers > 0, "Socket notification capacity and workers must be positive")
  private var admission = DrainGateMachine.initial
  private val budget = Some(capacity)
  private val names = new AtomicInteger()
  private val executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](capacity), new ThreadFactory {
      def newThread(task: Runnable): Thread = {
        val thread = new Thread(task, s"spoonbill-socket-close-${names.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }, new ThreadPoolExecutor.AbortPolicy)

  def reserve(): Option[SocketCloseDispatcher.Reservation] = synchronized {
    if (DrainGateMachine.admission(admission, budget) != DrainGateMachine.Admission.Accepted) None
    else {
      admission = DrainGateMachine.entered(admission)
      Some(new SocketCloseDispatcher.Reservation(task => executor.execute(task), () => returned()))
    }
  }

  private def returned(): Unit = synchronized {
    admission = DrainGateMachine.finished(admission)
    if (DrainGateMachine.drained(admission)) executor.shutdown()
  }

  def stopAdmission(): Unit = synchronized {
    admission = DrainGateMachine.close(admission)
    if (DrainGateMachine.drained(admission)) executor.shutdown()
  }

  private[io] def inUse: Int = synchronized(DrainGateMachine.count(admission))
  private[io] def queued: Int = executor.getQueue.size()
}

private[spoonbill] object SocketCloseDispatcher {
  final class Reservation private[io](submit: Runnable => Unit, giveBack: () => Unit) {
    import SocketCloseReservationMachine.{Command, Event}
    private val state = new StateCell(SocketCloseReservationMachine.initial)
    private val released = Promise[Unit]()

    private def advance(event: Event): Command = {
      val command = state.modify(SocketCloseReservationMachine.step(_, event))
      if (command == Command.Release) {
        giveBack()
        released.trySuccess(())
      }
      command
    }

    /** Cancel only before installation on a socket. */
    def cancel(): Unit = { advance(Event.Cancel); () }

    def connectionFinished(): Unit = { advance(Event.ConnectionFinished); () }

    private[io] def afterReleased(callback: () => Unit): Unit =
      released.future.onComplete(_ => callback())(ExecutionContext.parasitic)

    def awaitReleased[F[_]: Effect](): F[Unit] = Effect[F].promise { callback =>
      released.future.onComplete(result => callback(result.toEither))(ExecutionContext.parasitic)
    }

    /** One reserved task; async cleanup keeps the permit without holding a worker. */
    private[io] def dispatch(start: (() => Unit) => Unit): Unit = {
      if (advance(Event.Dispatch) == Command.Submit) {
        submit(new Runnable {
          def run(): Unit = {
            try start(() => { advance(Event.NotificationFinished); () })
            catch { case NonFatal(error) =>
              advance(Event.NotificationFinished)
              val thread = Thread.currentThread()
              thread.getUncaughtExceptionHandler.uncaughtException(thread, error)
            }
          }
        })
      }
    }
  }
}
