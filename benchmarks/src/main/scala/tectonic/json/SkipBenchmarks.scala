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
package json

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.annotation.nowarn
import scala.collection.immutable.List

import _root_.fs2.Chunk
import _root_.fs2.io.file.Files
import _root_.fs2.io.file.Flags
import _root_.fs2.io.file.Path
import cats.effect.IO
import cats.effect.Sync
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import tectonic.fs2.StreamParser

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@State(Scope.Benchmark)
class SkipBenchmarks {

  private[this] val ChunkSize = 65536

  private[this] val ResourceDir =
    Paths.get(System.getProperty("project.resource.dir"))

  import FacadeTuningParams._

  @Param(Array("true", "false"))
  var enableSkips: Boolean = _

  // includes the cost of file IO; not sure if that's a good thing?
  @Benchmark
  @nowarn("cat=unused-params")
  def projectBarKeyFromUgh10k(bh: Blackhole): Unit = {
    val plateF = for {
      terminal <- BlackholePlate[IO](
        Tectonic.VectorCost,
        Tectonic.ScalarCost,
        Tectonic.TinyScalarCost,
        NumericCost,
        Tectonic.RowCost,
        Tectonic.BatchCost)

      back <- ProjectionPlate[IO, List[Nothing]](terminal, "bar", enableSkips)
    } yield back

    val contents = Files[IO].readAll(
      Path.fromNioPath(ResourceDir.resolve("ugh10k.json")),
      ChunkSize,
      Flags.Read)

    val parser = StreamParser(Parser(plateF, Parser.UnwrapArray))(_ => Chunk.empty[Nothing])

    contents.through(parser).compile.drain.unsafeRunSync()
  }
}

private[json] final class ProjectionPlate[A] private (
    delegate: Plate[A],
    field: String,
    enableSkips: Boolean)
    extends DelegatingPlate(delegate) {

  private[this] final var under: Int = 0

  private[this] val Continue = Signal.Continue
  private[this] val SkipColumn = if (enableSkips) Signal.SkipColumn else Signal.Continue

  override def nestMap(pathComponent: CharSequence): Signal = {
    if (under <= 0 && pathComponent.toString == field) {
      under += 1
      super.nestMap(pathComponent)
    } else if (under > 0) {
      under += 1
      super.nestMap(pathComponent)
    } else {
      SkipColumn
    }
  }

  override def unnest(): Signal = {
    if (under > 0) {
      under -= 1
      super.unnest()
    } else {
      Continue
    }
  }
}

private[json] object ProjectionPlate {
  def apply[F[_]: Sync, A](
      delegate: Plate[A],
      field: String,
      enableSkips: Boolean): F[Plate[A]] =
    Sync[F].delay(new ProjectionPlate(delegate, field, enableSkips))
}
