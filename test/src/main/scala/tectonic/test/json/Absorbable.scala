/*
 * Copyright 2021 Precog Data
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

package tectonic
package test

import java.lang.String
import java.nio.ByteBuffer
import scala.Array
import scala.Byte

import cats.effect.IO

sealed trait Absorbable[A] {
  def absorb[B](p: BaseParser[IO, B], a: A): IO[ParseResult[B]]
}

object Absorbable {

  def apply[A](implicit A: Absorbable[A]): Absorbable[A] = A

  implicit object StringAbs extends Absorbable[String] {
    def absorb[B](p: BaseParser[IO, B], str: String): IO[ParseResult[B]] =
      p.absorb(str)
  }

  implicit object ByteBufferAbs extends Absorbable[ByteBuffer] {
    def absorb[B](p: BaseParser[IO, B], bytes: ByteBuffer): IO[ParseResult[B]] =
      p.absorb(bytes)
  }

  implicit object BArrayAbs extends Absorbable[Array[Byte]] {
    def absorb[B](p: BaseParser[IO, B], bytes: Array[Byte]): IO[ParseResult[B]] =
      p.absorb(bytes)
  }
}
