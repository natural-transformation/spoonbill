package spoonbill.effect

import scala.concurrent.Future

/** Compile only this adapter for the closed built-in Future API. */
object BenchmarkBackend {
  def create(hooks: StreamOperatorBenchmarkV3.Instrumentation): Effect[Future] =
    Effect.future(Effect.FutureInstrumentation(
      beforeStart = () => hooks.beforeStart(),
      afterStart = completion => hooks.afterStart(completion),
      continuation = if (hooks.checked) Some(() => hooks.check()) else None
    ))
}
