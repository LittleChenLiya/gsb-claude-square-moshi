/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Local tests for the top-level consecutive JSON value iterator [JsonValueIterator] exposed through
 * [JsonAdapter.jsonValues].
 */
class JsonValueIteratorTest {
  /** A raw source that counts how many times it was closed. */
  private class CloseCountingSource(delegate: Source) : ForwardingSource(delegate) {
    var closeCount = 0
      private set

    override fun close() {
      closeCount++
      super.close()
    }
  }

  /** Wraps [delegate] and records the reader path at the start of each top-level read. */
  private class RecordingStartAdapter(
    private val delegate: JsonAdapter<String?>,
    val starts: MutableList<String> = mutableListOf(),
  ) : JsonAdapter<String?>() {
    override fun fromJson(reader: JsonReader): String? {
      starts += reader.path
      return delegate.fromJson(reader)
    }

    override fun toJson(writer: JsonWriter, value: String?) {
      delegate.toJson(writer, value)
    }
  }

  private val adapter: JsonAdapter<String?> =
    Moshi.Builder().build().adapter(String::class.java).nullSafe()

  private fun trackingSource(json: String): Pair<CloseCountingSource, BufferedSource> {
    val tracking = CloseCountingSource(Buffer().writeUtf8(json))
    return tracking to tracking.buffer()
  }

  /** Two small values concatenated: indexes, reading start point, and both close modes. */
  @Test
  fun twoValues() {
    val json = "\"first\" \"second\""
    val recording = RecordingStartAdapter(adapter)

    // Borrowed reader: close() must not close the source and the reader stays usable afterwards.
    run {
      val (tracking, source) = trackingSource(json)
      val reader = JsonReader.of(source)
      val values = recording.jsonValues(reader)

      assertThat(values.hasNext()).isTrue()
      // Reading the first value consumes exactly it; the second value stays buffered and is read
      // from a fresh top-level reading start point.
      assertThat(values.next()).isEqualTo("first")
      assertThat(values.hasNext()).isTrue()
      assertThat(values.next()).isEqualTo("second")
      assertThat(values.hasNext()).isFalse()
      values.close()
      // The borrowed reader is still usable after close and positioned at the document end.
      assertThat(reader.peek()).isEqualTo(JsonReader.Token.END_DOCUMENT)
      assertThat(tracking.closeCount).isEqualTo(0)
    }

    // Every top-level read starts at the root path "$", with zero-based indexes in order.
    assertThat(recording.starts).containsExactly("$", "$").inOrder()

    // Owned source: normal iteration and early close both close the source exactly once.
    run {
      val (tracking, source) = trackingSource(json)
      adapter.jsonValues(source).use { values ->
        assertThat(values.next()).isEqualTo("first")
        assertThat(values.next()).isEqualTo("second")
        assertThat(values.hasNext()).isFalse()
        assertThat(tracking.closeCount).isEqualTo(0)
      }
      assertThat(tracking.closeCount).isEqualTo(1)

      val (tracking2, source2) = trackingSource(json)
      adapter.jsonValues(source2).use { values -> assertThat(values.next()).isEqualTo("first") }
      assertThat(tracking2.closeCount).isEqualTo(1)
    }
  }

  /** A truncated second value reports its index and keeps the original cause. */
  @Test
  fun truncatedSecondValue() {
    val json = "\"first\" \"second"

    // Borrowed source is never closed, even when reading fails.
    val (tracking, source) = trackingSource(json)
    val values = adapter.jsonValues(JsonReader.of(source))
    assertThat(values.next()).isEqualTo("first")
    val error = assertThrows(JsonEncodingException::class.java) { values.next() }
    assertThat(error).hasMessageThat().contains("index 1")
    assertThat(error.cause).isInstanceOf(JsonEncodingException::class.java)
    assertThat(error.cause).hasMessageThat().contains("Unterminated string")
    values.close()
    assertThat(tracking.closeCount).isEqualTo(0)

    // Owned source is closed once after the failure as well.
    val (trackingOwned, sourceOwned) = trackingSource(json)
    val ownedValues = adapter.jsonValues(sourceOwned)
    assertThrows(JsonEncodingException::class.java) {
      while (ownedValues.hasNext()) ownedValues.next()
    }
    ownedValues.close()
    assertThat(trackingOwned.closeCount).isEqualTo(1)
  }

  /** Whitespace-only input ends normally at EOF in both modes. */
  @Test
  fun whitespaceOnly() {
    val (trackingBorrowed, borrowedSource) = trackingSource("  \n\t ")
    val reader = JsonReader.of(borrowedSource)
    adapter.jsonValues(reader).use { values ->
      assertThat(values.hasNext()).isFalse()
      assertThrows(NoSuchElementException::class.java) { values.next() }
    }
    assertThat(trackingBorrowed.closeCount).isEqualTo(0)

    val (trackingOwned, ownedSource) = trackingSource("   ")
    adapter.jsonValues(ownedSource).use { values ->
      assertThat(values.hasNext()).isFalse()
      assertThrows(NoSuchElementException::class.java) { values.next() }
    }
    assertThat(trackingOwned.closeCount).isEqualTo(1)
  }

  /** Iteration index increments per value and is zero-based. */
  @Test
  fun indexesAreZeroBasedAndIncrement() {
    val (_, source) = trackingSource("\"a\" \"b\" \"c\"")
    adapter.jsonValues(source).use { values ->
      var count = 0
      while (values.hasNext()) {
        values.next()
        count++
      }
      assertThat(count).isEqualTo(3)
    }

    // The third value (index 2) truncated is named accordingly.
    val (_, truncated) = trackingSource("\"a\" \"b\" \"c")
    val values = adapter.jsonValues(truncated)
    repeat(2) { values.next() }
    val error = assertThrows(JsonEncodingException::class.java) { values.next() }
    assertThat(error).hasMessageThat().contains("index 2")
  }
}
