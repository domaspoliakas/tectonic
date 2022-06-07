/*
 * Copyright 2022 Precog Data Inc.
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

package tectonic.json

import org.openjdk.jmh.infra.Blackhole
import org.typelevel.jawn.FContext
import org.typelevel.jawn.Facade

final class BlackholeFacade(
    vectorAddCost: Long,
    vectorFinalCost: Long,
    scalarCost: Long,
    tinyScalarCost: Long,
    numericCost: Long)
    extends Facade.NoIndexFacade[Unit] {

  import Blackhole.consumeCPU

  def jfalse: Unit =
    consumeCPU(tinyScalarCost)

  def jnull: Unit =
    consumeCPU(tinyScalarCost)

  def jnum(s: CharSequence, decIndex: Int, expIndex: Int): Unit = {
    if (decIndex < 0 && expIndex < 0)
      consumeCPU(scalarCost)
    else
      consumeCPU(numericCost)
  }

  def jstring(s: CharSequence): Unit =
    consumeCPU(scalarCost)

  def jtrue: Unit =
    consumeCPU(tinyScalarCost)

  def arrayContext(): FContext[Unit] = new Context(false)
  def objectContext(): FContext[Unit] = new Context(true)
  def singleContext(): FContext[Unit] = new Context(false)

  private[this] final class Context(val isObj: Boolean) extends FContext[Unit] {

    def add(v: Unit, index: Int): Unit =
      consumeCPU(vectorAddCost)

    def add(s: CharSequence, index: Int): Unit =
      consumeCPU(vectorAddCost)

    def finish(index: Int): Unit =
      consumeCPU(vectorFinalCost)
  }
}
