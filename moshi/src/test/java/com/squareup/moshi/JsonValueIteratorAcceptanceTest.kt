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
import java.nio.file.Files
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance for consecutive top-level JSON value iteration. It uses only an in-memory [Buffer] and
 * a small file fixture in a JUnit [TemporaryFolder], and verifies iteration index, reading start
 * point and source close counts for both ownership modes.
 */
class JsonValueIteratorAcceptanceTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /** Counts closes on a raw source. */
  private class CountingSource(delegate: Source) : ForwardingSource(delegate) {
    var closes = 0
      private set

    override fun close() {
      closes++
      super.close()
    }
  }

  private val moshi = Moshi.Builder().build()
  private val adapter: JsonAdapter<Map<String, Int>?> = moshi.adapter()

  private fun countingSource(json: String): Pair<CountingSource, BufferedSource> {
    val raw = CountingSource(Buffer().writeUtf8(json))
    return raw to raw.buffer()
  }

  private fun readAll(source: BufferedSource): List<Map<String, Int>?> {
    val out = mutableListOf<Map<String, Int>?>()
    adapter.jsonValues(source).use { values ->
      while (values.hasNext()) out += values.next()
    }
    return out
  }

  /** Case 1: two small values concatenated in one stream, with whitespace between them. */
  @Test
  fun twoSmallValues() {
    val json = "{\"a\":1}\n{\"b\":2}"

    // Borrowed reader: indexes are zero-based and the source is never closed.
    val (raw, source) = countingSource(json)
    val reader = JsonReader.of(source)
    adapter.jsonValues(reader).use { values ->
      assertThat(values.hasNext()).isTrue()
      assertThat(values.next()).containsExactly("a", 1)
      // Second value starts at a fresh root reading point: path is "$".
      assertThat(reader.path).isEqualTo("$")
      assertThat(values.next()).containsExactly("b", 2)
      assertThat(values.hasNext()).isFalse()
    }
    assertThat(raw.closes).isEqualTo(0)
    reader.close()
    assertThat(raw.closes).isEqualTo(1)

    // Owned source from a tiny temp file fixture: closes exactly once.
    val file = tempFolder.newFile("values.json")
    Files.writeString(file.toPath(), json)
    val owned = CountingSource(file.source())
    val values = adapter.jsonValues(owned.buffer())
    val result = mutableListOf<Map<String, Int>?>()
    while (values.hasNext()) result += values.next()
    values.close()
    assertThat(result).containsExactly(mapOf("a" to 1), mapOf("b" to 2))
    assertThat(owned.closes).isEqualTo(1)
  }

  /** Case 2: the second value is truncated; index and original cause are reported. */
  @Test
  fun truncatedSecondValue() {
    val json = "{\"a\":1} {\"b\":"

    val (rawBorrowed, borrowedSource) = countingSource(json)
    val borrowed = adapter.jsonValues(JsonReader.of(borrowedSource))
    assertThat(borrowed.next()).containsExactly("a", 1)
    val failure = assertIOException { borrowed.next() }
    assertThat(failure).hasMessageThat().contains("index 1")
    assertThat(failure.cause).isNotNull()
    assertThat(failure).hasCauseThat().isInstanceOf(okio.IOException::class.java)
    borrowed.close()
    assertThat(rawBorrowed.closes).isEqualTo(0)

    val (rawOwned, ownedSource) = countingSource(json)
    val owned = adapter.jsonValues(ownedSource)
    assertThat(owned.next()).containsExactly("a", 1)
    assertIOException { owned.next() }
    owned.close()
    assertThat(rawOwned.closes).isEqualTo(1)
  }

  /** Case 3: whitespace-only input ends normally and still closes an owned source. */
  @Test
  fun whitespaceOnly() {
    val (rawBorrowed, borrowedSource) = countingSource("   \n\t  ")
    adapter.jsonValues(JsonReader.of(borrowedSource)).use { values ->
      assertThat(values.hasNext()).isFalse()
    }
    assertThat(rawBorrowed.closes).isEqualTo(0)

    val (rawOwned, ownedSource) = countingSource("  \n ")
    adapter.jsonValues(ownedSource).use { values ->
      assertThat(values.hasNext()).isFalse()
    }
    assertThat(rawOwned.closes).isEqualTo(1)
  }

  /** Small in-memory and temp-file fixtures yield identical decoded results. */
  @Test
  fun memoryAndFileFixturesAgree() {
    val json = "{\"x\":10} {\"y\":20}"
    val fromMemory = readAll(Buffer().writeUtf8(json))

    val file = tempFolder.newFile("pairs.ndjson")
    Files.writeString(file.toPath(), json)
    val fromFile = file.source().buffer().use(::readAll)

    val expected = listOf(mapOf("x" to 10), mapOf("y" to 20))
    assertThat(fromMemory).isEqualTo(expected)
    assertThat(fromFile).isEqualTo(expected)
  }

  private inline fun assertIOException(block: () -> Unit): JsonEncodingException {
    try {
      block()
    } catch (e: JsonEncodingException) {
      return e
    }
    throw AssertionError("Expected JsonEncodingException")
  }
}
