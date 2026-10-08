package com.raquo.airstream

import com.raquo.airstream.platform.JsSet

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

package object util {

  type Id[A] = A

  implicit class IdWrap[M[_], A](private val container: M[A]) extends AnyVal {
    def idWrap: M[Id[A]] = container
  }

  implicit class IdUnwrap[M[_], A](private val container: M[Id[A]]) extends AnyVal {
    def idUnwrap: M[A] = container
  }

  val always: Any => Boolean = _ => true

  /** Returns true if two or more of the `items` map to the same key.
    *
    * Note: `@inline` should eliminate the allocation of `key` lambda.
    *
    * #Note: keys are compared using JS `===` semantics, i.e. by reference
    *  for objects, NOT using Scala `equals`. Only use this for identity-keyed
    *  checks such as Var / WriteBus de-duplication.
    */
  @inline def hasDuplicateKeys[A](items: Seq[A])(key: A => Any): Boolean = {
    val seenKeys = new JsSet[Any]
    items.foreach { item =>
      seenKeys.add(key(item))
    }
    items.size != seenKeys.size
  }

  /** Like `Try(v).flatten`, but avoids allocating another `Success`. */
  def tryOrFailure[A](v: => Try[A]): Try[A] = {
    try {
      v
    } catch {
      case err: Throwable => Failure(err)
    }
  }

  /** `project` applied to `value`, its non-fatal exception caught exactly as `Try.map` catches it. Written out for the map
    * nodes, which project once per event: `Try.map` hands `Try.apply` a closure over the function and the value each time.
    */
  private[airstream] def projectCatching[A, B](project: A => B, value: A): Try[B] = {
    try {
      Success(project(value))
    } catch {
      case NonFatal(err) => Failure(err)
    }
  }
}
