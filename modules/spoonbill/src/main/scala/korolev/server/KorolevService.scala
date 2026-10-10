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

trait SpoonbillService[F[_]] {

  /**
   * Process HTTP request
   */
  def http(request: HttpRequest[F]): F[HttpResponse[F]]

  /** Process one WebSocket upgrade.
    *
    * A successful result is a [[WebSocketResponse]] disposition:
    * [[WebSocketResponse.Duplex]] keeps coupled input and output, and
    * [[WebSocketResponse.SendThenClose]] sends finite output after application
    * input has been released. Failure, including a rejected origin or access
    * denial, is an error in `F`. Adapters must not infer either disposition
    * from frame payloads, from an HTTP status, or from cancellation timing.
    *
    * Before the result is known, canceling the request input must not close the
    * underlying transport. After `SendThenClose`, deliver `output` on an
    * inbound drain that is independent of that input, then close. After
    * `Duplex`, an already-canceled input or a later input cancellation still
    * terminates the connection. If the upgrade is abandoned, execute
    * `release` and do not materialize `output`.
    */
  def ws(request: WebSocketRequest[F]): F[WebSocketResponse[F]]
}
