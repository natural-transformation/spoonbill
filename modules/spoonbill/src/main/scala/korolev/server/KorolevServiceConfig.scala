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

package spoonbill.server

import spoonbill.{Context, Extension, Router}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter}
import spoonbill.state.IdGenerator
import spoonbill.web.{Path, PathAndQuery}
import avocet.Document
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class SpoonbillServiceConfig[F[_], S, M](
  stateLoader: StateLoader[F, S],
  stateStorage: spoonbill.state.StateStorage[F, S] = null, // By default it StateStorage.DefaultStateStorage
  http: PartialFunction[HttpRequest[F], F[HttpResponse[F]]] = PartialFunction.empty[HttpRequest[F], F[HttpResponse[F]]],
  router: Router[F, S] = Router.empty[F, S],
  rootPath: Path = PathAndQuery.Root,
  document: S => Document.Node[Context.Binding[F, S, M]] =
    (_: S) => avocet.dsl.html.Html(),
  connectionLostWidget: Document.Node[Context.Binding[F, S, M]] =
    SpoonbillServiceConfig.defaultConnectionLostWidget[Context.Binding[F, S, M]],
  extensions: List[Extension[F, S, M]] = Nil,
  idGenerator: IdGenerator[F] = IdGenerator.default[F](),
  heartbeatInterval: FiniteDuration = 5.seconds,
  reporter: Reporter = Reporter.PrintReporter,
  recovery: PartialFunction[Throwable, S => S] = PartialFunction.empty[Throwable, S => S],
  sessionIdleTimeout: FiniteDuration = 60.seconds,
  delayedRender: FiniteDuration = 0.seconds,
  heartbeatLimit: Option[Int] = Some(2),
  presetIds: Boolean = false,
  webSocketEnabled: Boolean = true,
  webSocketProtocolsEnabled: Boolean = true,
  compressionSupport: Option[DeflateCompressionService[F]] =
    None // users should use java.util.zip.{Deflater, Inflater} in their service to make sure of the right compression format
)(implicit val executionContext: ExecutionContext)

object SpoonbillServiceConfig {

  def defaultConnectionLostWidget[MiscType]: Document.Node[MiscType] = {
    import avocet.dsl._
    import avocet.dsl.html._
    optimize {
      div(
        position @= "fixed",
        top @= "0",
        left @= "0",
        right @= "0",
        backgroundColor @= "lightyellow",
        borderBottom @= "1px solid black",
        padding @= "10px",
        "Connection lost. Waiting to resume."
      )
    }
  }
}

case class DeflateCompressionService[F[_]: Effect](
  decoder: Bytes => F[String],
  encoder: String => F[Bytes]
)
