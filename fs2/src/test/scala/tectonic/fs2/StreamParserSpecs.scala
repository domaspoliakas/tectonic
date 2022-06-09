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
package fs2

import java.nio.ByteBuffer

import _root_.fs2.Chunk
import _root_.fs2.Pipe
import _root_.fs2.Stream
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.instances.list._
import org.specs2.mutable.Specification
import scodec.bits.ByteVector
import tectonic.json.Parser
import tectonic.test.Event
import tectonic.test.ReifiedTerminalPlate

class StreamParserSpecsResetSize1 extends StreamParserSpecs(1)

class StreamParserSpecsResetSize4 extends StreamParserSpecs(4)

class StreamParserSpecsResetSize1M extends StreamParserSpecs(1048576)

abstract class StreamParserSpecs(val resetSize: Int) extends Specification {
  import Event._

  val parserF: IO[BaseParser[IO, List[Event]]] =
    Parser(ReifiedTerminalPlate[IO](), Parser.ValueStream, resetSize)

  val parser: Pipe[IO, Byte, Event] =
    StreamParser.foldable(parserF)

  def plateParser(f: Plate[List[Event]] => Plate[List[Event]]): Pipe[IO, Byte, Event] =
    StreamParser.foldable(
      Parser(ReifiedTerminalPlate[IO]().map(f), Parser.ValueStream, resetSize))

  "stream parser transduction" should {
    "parse a single value" in {
      val results = Stream.chunk(Chunk.array("42".getBytes)).through(parser)
      results.compile.toList.unsafeRunSync() mustEqual List(Num("42", -1, -1), FinishRow)
    }

    "parse two values from a single chunk" in {
      val results = Stream.chunk(Chunk.array("16 true".getBytes)).through(parser)
      val expected = List(Num("16", -1, -1), FinishRow, Tru, FinishRow)

      results.compile.toList.unsafeRunSync() mustEqual expected
    }

    "parse a value split across two chunks" in {
      val input = Stream.chunk(Chunk.array("7".getBytes)) ++
        Stream.chunk(Chunk.array("9".getBytes))

      val results = input.through(parser)
      val expected = List(Num("79", -1, -1), FinishRow)

      results.compile.toList.unsafeRunSync() mustEqual expected
    }

    def targetMask[A](target: Either[Int, String])(delegate: Plate[A]): Plate[A] =
      new DelegatingPlate[A](delegate) {
        private[this] var depth = 0
        private[this] var index = 0

        override def nestMap(pathComponent: CharSequence): Signal = {
          if (Right(pathComponent.toString) == target && depth == 0) {
            super.nestMap(pathComponent)
          } else {
            depth += 1
            Signal.SkipColumn
          }
        }

        override def nestArr(): Signal = {
          if (depth == 0) {
            index += 1
            if (Left(index - 1) == target) {
              super.nestArr()
            } else {
              depth += 1
              Signal.SkipColumn
            }
          } else {
            depth += 1
            Signal.SkipColumn
          }
        }

        override def unnest(): Signal = {
          if (depth == 0) {
            super.unnest()
          } else {
            depth -= 1
            Signal.Continue
          }
        }
      }

    """parse json with \" """ in {
      val str = """{"b": " \"q "}"""
      val chunks = str.toCharArray.map(c => Chunk.array(c.toString.getBytes))
      val stream = chunks.map(Stream.chunk(_)).fold(Stream.empty)(_ ++ _)
      val res = stream.through(plateParser(targetMask[List[Event]](Right("b"))))
      res.compile.toList.unsafeRunSync() mustEqual List(
        NestMap("b"),
        Str(""" "q """),
        Unnest,
        FinishRow)
    }

    "repro - 198" in {
      val str = """{"a": "\"q "}"""
      val chunks = str.toCharArray.map(c => Chunk.array(c.toString.getBytes))
      val stream = chunks.map(Stream.chunk(_)).fold(Stream.empty)(_ ++ _)
      val res = stream.through(plateParser(targetMask[List[Event]](Right("b"))))
      res.compile.toList.unsafeRunSync() mustEqual
        List(
          Skipped(1),
          Skipped(1),
          Skipped(1),
          Skipped(1),
          Skipped(1),
          Skipped(1),
          Skipped(1),
          Skipped(1),
          FinishRow)
    }

    "parse two values from two chunks" in {
      val input = Stream.chunk(Chunk.array("321 ".getBytes)) ++
        Stream.chunk(Chunk.array("true".getBytes))

      val results = input.through(parser)
      val expected = List(Num("321", -1, -1), FinishRow, Tru, FinishRow)

      results.compile.toList.unsafeRunSync() mustEqual expected
    }

    "parse a value from a bytebuffer chunk" in {
      val input = Stream.chunk(Chunk.ByteBuffer(ByteBuffer.wrap("123".getBytes)))

      val results = input.through(parser)
      val expected = List(Num("123", -1, -1), FinishRow)

      results.compile.toList.unsafeRunSync() mustEqual expected
    }

    "parse two values from a split bytevector chunk" in {
      val input =
        Stream.chunk(
          Chunk.byteVector(ByteVector.view(ByteBuffer.wrap("456 ".getBytes)) ++
            ByteVector.view(ByteBuffer.wrap("true".getBytes))))

      val results = input.through(parser)
      val expected = List(Num("456", -1, -1), FinishRow, Tru, FinishRow)

      results.compile.toList.unsafeRunSync() mustEqual expected
    }

    // this test also tests the json parser
    "parse two values with partial batch consumption" in {
      val input = Stream.chunk(Chunk.array("[123, false]".getBytes))

      val plateF = ReifiedTerminalPlate[IO]() map { delegate =>
        new Plate[List[Event]] {
          import Signal.BreakBatch

          def nul(): Signal = {
            delegate.nul()
            BreakBatch
          }

          def fls(): Signal = {
            delegate.fls()
            BreakBatch
          }

          def tru(): Signal = {
            delegate.tru()
            BreakBatch
          }

          def map(): Signal = {
            delegate.map()
            BreakBatch
          }

          def arr(): Signal = {
            delegate.arr()
            BreakBatch
          }

          def num(s: CharSequence, decIdx: Int, expIdx: Int): Signal = {
            delegate.num(s, decIdx, expIdx)
            BreakBatch
          }

          def str(s: CharSequence): Signal = {
            delegate.str(s)
            BreakBatch
          }

          def nestMap(pathComponent: CharSequence): Signal = {
            delegate.nestMap(pathComponent)
            BreakBatch
          }

          def nestArr(): Signal = {
            delegate.nestArr()
            BreakBatch
          }

          def nestMeta(pathComponent: CharSequence): Signal = {
            delegate.nestMeta(pathComponent)
            BreakBatch
          }

          def unnest(): Signal = {
            delegate.unnest()
            BreakBatch
          }

          def finishRow(): Unit =
            delegate.finishRow()

          def finishBatch(terminal: Boolean): List[Event] =
            delegate.finishBatch(terminal)

          def skipped(bytes: Int): Unit =
            delegate.skipped(bytes)
        }
      }

      val results = input.through(StreamParser.foldable(Parser(plateF, Parser.ValueStream)))

      // note that we're getting visibility into the laziness by exposing chunk boundaries
      results.chunks.compile.toList.unsafeRunSync().map(_.toList) mustEqual
        List(
          List(NestArr),
          List(Num("123", -1, -1)),
          List(Unnest, NestArr),
          List(Fls),
          List(Unnest, FinishRow))
    }
  }
}
