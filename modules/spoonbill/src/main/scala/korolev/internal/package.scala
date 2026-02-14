package spoonbill

import scala.annotation.switch
import scala.collection.AbstractMapView
import scala.collection.MapView
import scala.collection.mutable

package object internal {

  private[spoonbill] implicit final class MapViewConcat[K, +V](val left: MapView[K, V]) extends AnyVal {
    def +++[V1 >: V](rightArg: => MapView[K, V1]): MapView[K, V1] =
      new AbstractMapView[K, V1] {
        private lazy val right = rightArg
        def get(key: K): Option[V1] = left
          .get(key)
          .orElse(right.get(key))
        def iterator: Iterator[(K, V1)] = left.iterator.filter { case (k, _) => !right.contains(k) }
          .concat(right.iterator)
      }
  }

  private[spoonbill] def jsonEscape(sb: mutable.StringBuilder, s: String, unicode: Boolean): Unit = {
    var i   = 0
    val len = s.length
    while (i < len) {
      (s.charAt(i): @switch) match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c =>
          if (c < ' ' || (c > '~' && unicode)) sb.append("\\u%04x" format c.toInt)
          else sb.append(c)
      }
      i += 1
    }
    ()
  }

}
