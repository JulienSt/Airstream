/*
 * Copied without semantic changes from
 * app.tulz:tuplez-full_sjs1_3:0.5.0-M2!/app/tulz/tuplez/TupleComposition.scala
 * (project: https://github.com/tulz-app/tuplez).
 * The MIT License (MIT)
 *
 * Copyright 2020 Iurii Malchenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.tulz.tuplez

object TupleComposition {

  def compose[L, R](l: L, r: R)(using composition: Composition[L, R]): composition.Composed = composition.compose(l, r)
}

abstract class Composition[-L, -R] {
  type Composed
  def compose(a: L, b: R): Composed
}

trait Composition_Pri0 {
  given `***`[A, B]: Composition.Aux[A, B, Tuple2[A, B]] = new Composition[A, B] {

    override type Composed = Tuple2[A, B]

    def compose(l: A, r: B): Tuple2[A, B] = Tuple2(l, r)
  }
}

trait Composition_Pri10 extends Composition_Pri0 {

  given `T+Scalar`[T1 <: Tuple, T2]: Composition.Aux[T1, T2, Tuple.Append[T1, T2]] = new Composition[T1, T2] {

    override type Composed = Tuple.Append[T1, T2]

    def compose(l: T1, r: T2): Tuple.Append[T1, T2] = l :* r
  }

  given `Scalar+T`[T1, T2 <: Tuple]: Composition.Aux[T1, T2, Tuple.Concat[Tuple1[T1], T2]] = new Composition[T1, T2] {

    override type Composed = Tuple.Concat[Tuple1[T1], T2]

    def compose(l: T1, r: T2): Tuple.Concat[Tuple1[T1], T2] = l *: r
  }
}

object Composition extends Composition_Pri10 {
  type Aux[A, B, O] = Composition[A, B] { type Composed = O }

  given `unit+unit`: Composition.Aux[Unit, Unit, Unit] = new Composition[Unit, Unit] {

    override type Composed = Unit

    def compose(l: Unit, r: Unit): Unit = ()
  }

  given `unit+A`[A]: Composition.Aux[Unit, A, A] = new Composition[Unit, A] {

    override type Composed = A

    def compose(l: Unit, r: A): A = r
  }

  given `A+unit`[A]: Composition.Aux[A, Unit, A] = new Composition[A, Unit] {

    override type Composed = A

    def compose(l: A, r: Unit): A = l
  }

  given `T+T`[T1 <: Tuple, T2 <: Tuple]: Composition.Aux[T1, T2, Tuple.Concat[T1, T2]] = new Composition[T1, T2] {

    override type Composed = Tuple.Concat[T1, T2]

    def compose(l: T1, r: T2): Tuple.Concat[T1, T2] = l ++ r
  }
}
