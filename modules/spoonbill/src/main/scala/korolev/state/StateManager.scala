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

package spoonbill.state

import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import avocet.Id
import scala.collection.concurrent.TrieMap

abstract class StateManager[F[_]: Effect] { self =>
  def snapshot: F[StateManager.Snapshot]
  def read[T: StateDeserializer](nodeId: Id): F[Option[T]]
  def delete(nodeId: Id): F[Unit]
  def write[T: StateSerializer](nodeId: Id, value: T): F[Unit]
}

object StateManager {

  trait Snapshot {
    def apply[T: StateDeserializer](nodeId: Id): Option[T]
  }

  def cached[F[_]: Effect](
    initial: Map[Id, Any] = Map.empty,
    onWrite: (Id, Any) => F[Unit] = null,
    onDelete: Id => F[Unit] = null
  ): StateManager[F] =
    new StateManager[F] {

      private val actualOnWrite =
        if (onWrite == null) (_: Id, _: Any) => Effect[F].unit else onWrite
      private val actualOnDelete =
        if (onDelete == null) (_: Id) => Effect[F].unit else onDelete
      private val cache = TrieMap.from(initial)

      def snapshot: F[Snapshot] =
        Effect[F].delay {
          val values = cache.toMap
          new Snapshot {
            def apply[T: StateDeserializer](nodeId: Id): Option[T] = try {
              values.get(nodeId).asInstanceOf[Option[T]]
            } catch {
              case _: ClassCastException => None
            }
          }
        }

      def read[T: StateDeserializer](nodeId: Id): F[Option[T]] =
        snapshot.map(_.apply(nodeId))

      def delete(nodeId: Id): F[Unit] =
        Effect[F].delay(cache.remove(nodeId)).flatMap(_ => actualOnDelete(nodeId))

      def write[T: StateSerializer](nodeId: Id, value: T): F[Unit] =
        Effect[F].delay(cache.put(nodeId, value)).flatMap(_ => actualOnWrite(nodeId, value))
    }

  def serialized[F[_]: Effect](
    initial: Map[Id, Array[Byte]] = Map.empty,
    onWrite: (Id, Array[Byte]) => F[Unit] = null,
    onDelete: Id => F[Unit] = null
  ): StateManager[F] =
    new StateManager[F] {

      private val actualOnWrite =
        if (onWrite == null) (_: Id, _: Array[Byte]) => Effect[F].unit else onWrite
      private val actualOnDelete =
        if (onDelete == null) (_: Id) => Effect[F].unit else onDelete
      private val cache = TrieMap.from(initial.view.mapValues(_.clone()).toMap)

      def snapshot: F[Snapshot] =
        Effect[F].delay {
          val values = cache.view.mapValues(_.clone()).toMap
          new Snapshot {
            def apply[T: StateDeserializer](nodeId: Id): Option[T] =
              values.get(nodeId).flatMap { bytes =>
                implicitly[StateDeserializer[T]].deserialize(bytes.clone())
              }
          }
        }

      def read[T: StateDeserializer](nodeId: Id): F[Option[T]] =
        snapshot.map(_.apply(nodeId))

      def delete(nodeId: Id): F[Unit] =
        Effect[F].delay(cache.remove(nodeId)).flatMap(_ => actualOnDelete(nodeId))

      def write[T: StateSerializer](nodeId: Id, value: T): F[Unit] = {
        val bytes = implicitly[StateSerializer[T]].serialize(value)
        Effect[F]
          .delay(cache.put(nodeId, bytes.clone()))
          .flatMap(_ => actualOnWrite(nodeId, bytes.clone()))
      }
    }
}
