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

import scala.{Int, Product, Serializable}

import java.lang.CharSequence

sealed trait Event extends Product with Serializable

object Event {
  case object Nul extends Event
  case object Fls extends Event
  case object Tru extends Event
  case object Map extends Event
  case object Arr extends Event
  final case class Num(s: CharSequence, decIdx: Int, expIdx: Int) extends Event
  final case class Str(s: CharSequence) extends Event

  final case class NestMap(pathComponent: CharSequence) extends Event
  case object NestArr extends Event
  final case class NestMeta(pathComponent: CharSequence) extends Event

  case object Unnest extends Event

  case object FinishRow extends Event

  final case class Skipped(bytes: Int) extends Event
}
