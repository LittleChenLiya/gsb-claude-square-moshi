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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import okio.Buffer
import okio.EOFException
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Test

class JsonValueIteratorTest {
  private val adapter = Moshi.Builder().build().adapter(Any::class.java)

  @Test
  fun twoConcatenatedValues() {
    val iterator = adapter.values(Buffer().writeUtf8("""{"a":1}{"b":2}"""))

    // The first value starts at index 0.
    assertThat(iterator.nextIndex).isEqualTo(0)
    assertThat(iterator.hasNext()).isTrue()
    assertThat(iterator.next()).isEqualTo(mapOf("a" to 1.0))

    // The second value is read starting exactly after the first one.
    assertThat(iterator.nextIndex).isEqualTo(1)
    assertThat(iterator.hasNext()).isTrue()
    assertThat(iterator.next()).isEqualTo(mapOf("b" to 2.0))

    // The stream is exhausted after both values.
    assertThat(iterator.nextIndex).isEqualTo(2)
    assertThat(iterator.hasNext()).isFalse()
  }

  @Test
  fun truncatedSecondValue() {
    val iterator = adapter.values(Buffer().writeUtf8("{\"a\":1}\n{\"a\":"))

    assertThat(iterator.next()).isEqualTo(mapOf("a" to 1.0))
    assertThat(iterator.nextIndex).isEqualTo(1)

    // The truncated value fails with its index and the original cause.
    val thrown =
      try {
        iterator.next()
        throw AssertionError("expected JsonDataException")
      } catch (e: JsonDataException) {
        e
      }
    assertThat(thrown.message).isEqualTo("Truncated JSON at value index 1, path $.a")
    assertThat(thrown.cause).isNotNull().isInstanceOf(EOFException::class)

    // The failed value was not consumed and the iterator is finished.
    assertThat(iterator.nextIndex).isEqualTo(1)
    assertThat(iterator.hasNext()).isFalse()
  }

  @Test
  fun whitespaceOnlyStreamEndsNormally() {
    val iterator = adapter.values(Buffer().writeUtf8("  \n\t "))

    assertThat(iterator.nextIndex).isEqualTo(0)
    assertThat(iterator.hasNext()).isFalse()
    assertThat(iterator.nextIndex).isEqualTo(0)

    val failure = assertFailure { iterator.next() }
    failure.isInstanceOf(NoSuchElementException::class)
    failure.hasMessage("No more JSON values at index 0")
  }

  @Test
  fun closeDoesNotCloseBorrowedSource() {
    val source = CountingSource(Buffer().writeUtf8("""{"a":1}"""))
    val iterator = adapter.values(source.buffer())

    assertThat(iterator.next()).isEqualTo(mapOf("a" to 1.0))
    iterator.close()

    // Borrowed mode: the caller keeps ownership of the source.
    assertThat(source.closeCount).isEqualTo(0)
  }

  @Test
  fun closeClosesOwnedSource() {
    val source = CountingSource(Buffer().writeUtf8("""{"a":1}"""))
    val iterator = adapter.values(source)

    assertThat(iterator.next()).isEqualTo(mapOf("a" to 1.0))
    assertThat(iterator.hasNext()).isFalse()
    iterator.close()

    // Owned mode: closing the iterator closes the source it took ownership of.
    assertThat(source.closeCount).isEqualTo(1)
  }

  private class CountingSource(delegate: Source) : ForwardingSource(delegate) {
    var closeCount = 0
      private set

    override fun close() {
      closeCount++
      super.close()
    }
  }
}
