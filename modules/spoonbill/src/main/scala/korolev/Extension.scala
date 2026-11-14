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

package spoonbill

import Extension.Handlers
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.util.Lens

/**
 * Server code that lives as long as one attached view.
 *
 * The runtime sets an extension up once per view. It tells the extension about
 * state changes and published messages, and it destroys the extension when the
 * view ends. The callbacks of one view never run at the same time.
 *
 * @see
 *   docs/extension-contract-design.md for the full contract.
 */
trait Extension[F[_], S, M] { self =>

  /**
   * Names this extension in failure reports.
   */
  def name: String

  /**
   * Runs when a connection attaches to a view. It does not run for the HTTP
   * page render.
   *
   * Extensions are set up one at a time, in registration order. Each call has a
   * time bound. A failed `setup` fails the attach: the view is closed, and the
   * extensions that are already set up are destroyed.
   *
   * The connection is completed after the last `setup`. Until then nothing is
   * sent to the browser. So a `setup` must not wait for the browser: an
   * `evalJs` result, a property read or a callback from the page cannot arrive
   * before it completes. Start such a call without waiting for it.
   *
   * @return
   *   the handlers of this extension for this view
   */
  def setup(access: Context.BaseAccess[F, S, M]): F[Handlers[F, S, M]]

  /**
   * Runs this extension inside an application with a larger state.
   *
   * The extension reads and writes its part of the state through `read` and
   * `write`. Messages pass through unchanged. The lifecycle is not changed:
   * `setup` and `onDestroy` pass straight through.
   *
   * @param read
   *   takes the part this extension works on out of the larger state
   * @param write
   *   puts a changed part back into the larger state
   */
  def imapState[S0](read: S0 => S, write: (S0, S) => S0)(using F: Effect[F]): Extension[F, S0, M] =
    new Extension[F, S0, M] {
      val name: String = self.name

      private val lens = Lens[S0, S]({ case state => read(state) }, { case (state, part) => write(state, part) })

      def setup(access: Context.BaseAccess[F, S0, M]): F[Handlers[F, S0, M]] =
        F.delayAsync(self.setup(access.imap(lens))).map { handlers =>
          new Handlers[F, S0, M] {
            def onState(state: S0): F[Unit]       = handlers.onState(read(state))
            def onMessage(message: M): F[Unit]    = handlers.onMessage(message)
            def onDestroy(lastState: S0): F[Unit] = handlers.onDestroy(read(lastState))
          }
        }
    }
}

object Extension {

  trait Handlers[F[_], S, M] {

    /**
     * Runs with the state the view has when delivery starts, and then once for
     * every later write of the top-level state, in order.
     *
     * States that were written before delivery started are folded into the
     * first call. Only the newest one is delivered.
     */
    def onState(state: S): F[Unit]

    /**
     * Runs once for every published message, in publish order.
     *
     * Like `onState`, it runs in a task of the session's execution context, one
     * callback of the view at a time. A callback that fails is reported, and
     * the next one still runs. With ZIO each callback runs in a fiber of its
     * own: start long work with `forkDaemon`, because a plain child fiber is
     * interrupted when the callback returns.
     *
     * @see
     *   Context.BaseAccess.publish
     */
    def onMessage(message: M): F[Unit]

    /**
     * Runs once when the view ends: the browser closed or left the page, the
     * idle timeout fired, access was denied or revoked, the view was replaced,
     * or the attach failed.
     *
     * It is the last callback. The view is already closed: a state read on the
     * access fails, and nothing can be written or published any more.
     *
     * @param lastState
     *   the last state that was passed to `onState` of these handlers. A state
     *   that was written but did not reach `onState` before the view ended is
     *   not passed here either. If no state was passed, the state the view was
     *   accepted with.
     */
    def onDestroy(lastState: S): F[Unit]
  }

  private final class FunctionHandlers[F[_], S, M](
    state: S => F[Unit],
    message: M => F[Unit],
    destroy: S => F[Unit]
  ) extends Handlers[F, S, M] {
    def onState(value: S): F[Unit]       = state(value)
    def onMessage(value: M): F[Unit]     = message(value)
    def onDestroy(lastState: S): F[Unit] = destroy(lastState)
  }

  private final class NamedExtension[F[_], S, M](
    val name: String,
    run: Context.BaseAccess[F, S, M] => F[Handlers[F, S, M]]
  ) extends Extension[F, S, M] {
    def setup(access: Context.BaseAccess[F, S, M]): F[Handlers[F, S, M]] = run(access)
  }

  object Handlers {

    /**
     * Builds handlers from total functions. A callback that is left out does
     * nothing.
     *
     * @param onState
     *   Invokes with the attach state and with every later state.
     * @param onMessage
     *   Invokes when message published.
     * @param onDestroy
     *   Invokes once when the view ends, with the last state.
     */
    def apply[F[_], S, M](using F: Effect[F])(
      onState: S => F[Unit] = (_: S) => F.unit,
      onMessage: M => F[Unit] = (_: M) => F.unit,
      onDestroy: S => F[Unit] = (_: S) => F.unit
    ): Handlers[F, S, M] =
      new FunctionHandlers(
        nonNull(onState, "onState"),
        nonNull(onMessage, "onMessage"),
        nonNull(onDestroy, "onDestroy")
      )

    /**
     * Like [[apply]], but a state or a message that is not matched is ignored.
     * So a handler lists only the cases it cares about.
     *
     * `onDestroy` stays a total function, so cleanup is never skipped by
     * accident.
     */
    def partial[F[_], S, M](using F: Effect[F])(
      onState: PartialFunction[S, F[Unit]] = PartialFunction.empty,
      onMessage: PartialFunction[M, F[Unit]] = PartialFunction.empty,
      onDestroy: S => F[Unit] = (_: S) => F.unit
    ): Handlers[F, S, M] = {
      nonNull(onState, "onState")
      nonNull(onMessage, "onMessage")
      new FunctionHandlers(
        state => onState.applyOrElse(state, (_: S) => F.unit),
        message => onMessage.applyOrElse(message, (_: M) => F.unit),
        nonNull(onDestroy, "onDestroy")
      )
    }

    /**
     * `null` once meant "no callback". It still compiles, so it is rejected
     * here, when the handlers are built, and not at every delivery.
     */
    private def nonNull[A <: AnyRef](callback: A, name: String): A =
      java.util.Objects.requireNonNull(callback, s"$name is null. Leave it out for a callback that does nothing.")
  }

  /**
   * @param name
   *   names the extension in failure reports
   */
  def apply[F[_], S, M](name: String)(
    setup: Context.BaseAccess[F, S, M] => F[Handlers[F, S, M]]
  ): Extension[F, S, M] =
    new NamedExtension(name, setup)

  /**
   * An extension whose handlers are built without an effect.
   */
  def pure[F[_]: Effect, S, M](name: String)(
    setup: Context.BaseAccess[F, S, M] => Handlers[F, S, M]
  ): Extension[F, S, M] =
    new NamedExtension(name, access => Effect[F].delay(setup(access)))

  /**
   * An extension around one resource. The resource is released exactly once:
   * after `onDestroy`, or at once when building the handlers fails.
   */
  def managed[F[_]: Effect, S, M, R](name: String)(
    acquire: Context.BaseAccess[F, S, M] => F[R],
    release: R => F[Unit]
  )(handlers: (R, Context.BaseAccess[F, S, M]) => Handlers[F, S, M]): Extension[F, S, M] =
    managedF(name)(acquire, release) { (resource, access) =>
      Effect[F].delay(handlers(resource, access))
    }

  /**
   * Like [[managed]], but the handlers are built with an effect.
   *
   * When building the handlers fails, the resource is released inside `setup`,
   * before the runtime learns of the failure. The view is still open then. This
   * is the one cleanup that the runtime does not own.
   */
  def managedF[F[_]: Effect, S, M, R](name: String)(
    acquire: Context.BaseAccess[F, S, M] => F[R],
    release: R => F[Unit]
  )(handlers: (R, Context.BaseAccess[F, S, M]) => F[Handlers[F, S, M]]): Extension[F, S, M] =
    Extension(name) { access =>
      acquire(access).flatMap { resource =>
        val released = new AtomicBoolean(false)

        def releaseOnce(): F[Unit] =
          if (released.compareAndSet(false, true)) release(resource)
          else Effect[F].unit

        def releaseAndFail[A](error: Throwable): F[A] =
          releaseOnce().flatMap(_ => Effect[F].fail[A](error))

        Effect[F]
          .delayAsync(handlers(resource, access))
          .map[Handlers[F, S, M]] { built =>
            new Handlers[F, S, M] {
              def onState(state: S): F[Unit]     = built.onState(state)
              def onMessage(message: M): F[Unit] = built.onMessage(message)
              def onDestroy(lastState: S): F[Unit] =
                Effect[F]
                  .delayAsync(built.onDestroy(lastState))
                  .recoverF { case error => releaseAndFail[Unit](error) }
                  .flatMap(_ => releaseOnce())
            }
          }
          .recoverF { case error => releaseAndFail[Handlers[F, S, M]](error) }
      }
    }
}
