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

import cats.effect.Sync

/**
 * Produces None until finishBatch(true) is called.
 */
final class ReplayPlate private (retainSkips: Boolean) extends Plate[Option[EventCursor]] {

  // everything fits into a word
  private[this] final val Nul = EventCursor.Nul
  private[this] final val Fls = EventCursor.Fls
  private[this] final val Tru = EventCursor.Tru
  private[this] final val Map = EventCursor.Map
  private[this] final val Arr = EventCursor.Arr
  private[this] final val Num = EventCursor.Num
  private[this] final val Str = EventCursor.Str
  private[this] final val NestMap = EventCursor.NestMap
  private[this] final val NestArr = EventCursor.NestArr
  private[this] final val NestMeta = EventCursor.NestMeta
  private[this] final val Unnest = EventCursor.Unnest
  private[this] final val FinishRow = EventCursor.FinishRow
  private[this] final val Skipped = EventCursor.Skipped
  private[this] final val EndBatch = EventCursor.EndBatch

  private[this] final val Continue = Signal.Continue

  private[this] var tagBuffer = new Array[Long](ReplayPlate.DefaultBufferSize)
  private[this] var tagPointer = 0
  private[this] var tagSubShift = 0

  private[this] var strsBuffer = new Array[CharSequence](ReplayPlate.DefaultBufferSize / 2)
  private[this] var strsPointer = 0

  private[this] var intsBuffer = new Array[Int](ReplayPlate.DefaultBufferSize / 16)
  private[this] var intsPointer = 0

  private[this] var bufferedStrsSize = 0L

  final def nul(): Signal = {
    appendTag(Nul)
    Continue
  }

  final def fls(): Signal = {
    appendTag(Fls)
    Continue
  }

  final def tru(): Signal = {
    appendTag(Tru)
    Continue
  }

  final def map(): Signal = {
    appendTag(Map)
    Continue
  }

  final def arr(): Signal = {
    appendTag(Arr)
    Continue
  }

  final def num(s: CharSequence, decIdx: Int, expIdx: Int): Signal = {
    appendTag(Num)
    appendStr(s)
    appendInt(decIdx)
    appendInt(expIdx)
    Continue
  }

  final def str(s: CharSequence): Signal = {
    appendTag(Str)
    appendStr(s)
    Continue
  }

  final def nestMap(pathComponent: CharSequence): Signal = {
    appendTag(NestMap)
    appendStr(pathComponent)
    Continue
  }

  final def nestArr(): Signal = {
    appendTag(NestArr)
    Continue
  }

  final def nestMeta(pathComponent: CharSequence): Signal = {
    appendTag(NestMeta)
    appendStr(pathComponent)
    Continue
  }

  final def unnest(): Signal = {
    appendTag(Unnest)
    Continue
  }

  final def finishRow(): Unit = appendTag(FinishRow)

  final def finishBatch(terminal: Boolean): Option[EventCursor] = {
    if (terminal)
      Some(
        EventCursor(
          tagBuffer,
          tagPointer,
          tagSubShift,
          strsBuffer,
          strsPointer,
          intsBuffer,
          intsPointer))
    else
      None
  }

  override final def skipped(bytes: Int): Unit =
    if (retainSkips) {
      appendTag(Skipped)
      appendInt(bytes)
    } else {
      ()
    }

  final def appendBatchBoundary(): Unit =
    appendTag(EndBatch)

  /**
   * Returns an estimate, in bytes, of the memory usage of the accumulated events.
   *
   * This value will not be, and is not intended to be, precise. It will be a lower bound on,
   * and the same order of magnitude as, actual memory usage.
   */
  final def residentMemoryEstimate: Long =
    (bufferedStrsSize * 3L) + // UTF-8 average of 3-bytes per char
      (tagBuffer.length * 8L) + // sizeOf(Long)
      (strsBuffer.length * (8L + 4L)) + // 8b for pointer, 4b for charseq array size
      (intsBuffer.length * 4L) // sizeOf(Int)

  ////

  private[this] final def appendTag(tag: Int): Unit = {
    checkTags()
    tagBuffer(tagPointer) |= tag.toLong << tagSubShift

    if (tagSubShift == 60) {
      tagPointer += 1
      tagSubShift = 0
    } else {
      tagSubShift += 4
    }
  }

  private[this] final def checkTags(): Unit = {
    if (tagSubShift == 0 && tagPointer >= tagBuffer.length) {
      val tagBuffer2 = new Array[Long](nextBufferSize(tagBuffer.length))
      System.arraycopy(tagBuffer, 0, tagBuffer2, 0, tagBuffer.length)
      tagBuffer = tagBuffer2
    }
  }

  private[this] final def appendStr(cs: CharSequence): Unit = {
    checkStrs()
    recordStrSize(cs)
    strsBuffer(strsPointer) = cs
    strsPointer += 1
  }

  private[this] final def recordStrSize(cs: CharSequence): Unit =
    bufferedStrsSize += cs.length

  private[this] final def checkStrs(): Unit = {
    if (strsPointer >= strsBuffer.length) {
      val strsBuffer2 = new Array[CharSequence](nextBufferSize(strsBuffer.length))
      System.arraycopy(strsBuffer, 0, strsBuffer2, 0, strsBuffer.length)
      strsBuffer = strsBuffer2
    }
  }

  private[this] final def appendInt(i: Int): Unit = {
    checkInts()
    intsBuffer(intsPointer) = i
    intsPointer += 1
  }

  private[this] final def checkInts(): Unit = {
    if (intsPointer >= intsBuffer.length) {
      val intsBuffer2 = new Array[Int](nextBufferSize(intsBuffer.length))
      System.arraycopy(intsBuffer, 0, intsBuffer2, 0, intsBuffer.length)
      intsBuffer = intsBuffer2
    }
  }

  private[this] final def nextBufferSize(current: Int): Int =
    if (current == Int.MaxValue)
      throw new IllegalStateException(s"Unable to grow EventCursor beyond ${Int.MaxValue}")
    else if (current > (Int.MaxValue / 2))
      Int.MaxValue
    else
      current * 2
}

object ReplayPlate {
  // this gives us 512 events, which is ample to start, and we'll grow to 8 kb in 7 doubles
  // generally it just makes us much more efficient in the singleton cartesian case, and not much less efficient in the massive case
  val DefaultBufferSize: Int = 32

  def apply[F[_]: Sync](retainSkips: Boolean): F[ReplayPlate] =
    Sync[F].delay(new ReplayPlate(retainSkips))
}
