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

package tectonic
package csv

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import _root_.fs2.Chunk
import _root_.fs2.io.file.Files
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.instances.int._
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import tectonic.fs2.StreamParser

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@State(Scope.Benchmark)
class ParserBenchmarks {
  val TectonicFramework = "tectonic"
  val JacksonFramework = "jackson"

  private[this] val ChunkSize = 65536

  private[this] val ResourceDir =
    Paths.get(System.getProperty("project.managed.resource.dir"))

  // params

  @Param(Array("tectonic" /*, "jackson"*/ ))
  var framework: String = _

  // benchmarks

  // includes the cost of file IO; not sure if that's a good thing?
  @Benchmark
  def parseThroughFs2(): Unit = {
    val inputFile = "worldcitiespop.txt"

    val countingPlate = IO {
      new Plate[Int] {
        private[this] final var count = 0
        private[this] final val c = Signal.Continue

        def nul(): Signal = c
        def fls(): Signal = c
        def tru(): Signal = c
        def map(): Signal = c
        def arr(): Signal = c
        def num(s: CharSequence, decIdx: Int, expIdx: Int): Signal = c
        def str(s: CharSequence): Signal = c

        def nestMap(pathComponent: CharSequence): Signal = c
        def nestArr(): Signal = c
        def nestMeta(pathComponent: CharSequence): Signal = c

        def unnest(): Signal = c

        def finishRow(): Unit = count += 1

        def finishBatch(terminal: Boolean): Int = {
          val back = count
          count = 0
          back
        }

        def skipped(bytes: Int) = ()
      }
    }

    val contents = Files[IO].readAll(ResourceDir.resolve(inputFile), ChunkSize)

    val processed = if (framework == TectonicFramework) {
      val parser =
        StreamParser[IO, Int, Int](
          Parser(countingPlate, Parser.Config().copy(row1 = '\n', row2 = 0)))(
          Chunk.singleton(_))

      contents.through(parser).foldMonoid
    } else {
      ???
    }

    processed.compile.drain.unsafeRunSync()
  }

  @Benchmark
  def lineCountThroughFs2(): Unit = {
    val inputFile = "worldcitiespop.txt"

    val contents = Files[IO].readAll(ResourceDir.resolve(inputFile), ChunkSize)

    val counts = contents.chunks map { bytes =>
      val buf = bytes.toByteBuffer
      val buflen = buf.limit() - buf.position()
      val temp: Array[Byte] = new Array[Byte](buflen)
      buf.get(temp, 0, buflen)

      var i = 0
      var c = 0
      while (i < temp.length) {
        if ((temp(i) & 0xff) == '\n') {
          c += 1
        }
        i += 1
      }

      c
    }

    counts.foldMonoid.compile.drain.unsafeRunSync()
  }
}
