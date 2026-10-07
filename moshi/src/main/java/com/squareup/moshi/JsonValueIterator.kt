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

import java.io.Closeable
import okio.EOFException
import okio.IOException

/**
 * Iterates over a stream of consecutive top-level JSON values, decoding each value with a
 * [JsonAdapter].
 *
 * Each call to [next] delegates to the adapter to decode exactly one value from the underlying
 * reader, leaving any subsequent values untouched. Iteration ends normally when only whitespace
 * remains before end-of-input. If a value is truncated or malformed, [next] throws a
 * [JsonDataException] whose message carries the failing value's index and whose cause is the
 * original failure.
 *
 * Closing this iterator only closes the underlying source if it was explicitly created in an owning
 * mode (see [JsonAdapter.values]); iterators over a borrowed source never close it.
 *
 * Create instances with [JsonAdapter.values].
 */
public class JsonValueIterator<T>
internal constructor(
  private val adapter: JsonAdapter<T>,
  private val reader: JsonReader,
  private val ownsSource: Boolean,
) : Iterator<T>, Closeable {
  private var index = 0
  private var exhausted = false
  private var closed = false

  /**
   * The index of the value that the next call to [next] will decode. When this iterator is
   * exhausted this is the total number of values decoded.
   */
  public val nextIndex: Int
    get() = index

  /**
   * Returns true if another top-level JSON value remains in the stream. A stream that contains only
   * whitespace before end-of-input is exhausted and does not fail.
   */
  override fun hasNext(): Boolean {
    if (exhausted) return false
    check(!closed) { "JsonValueIterator is closed" }
    if (peekDocumentBoundary() == JsonReader.Token.END_DOCUMENT) {
      exhausted = true
      return false
    }
    return true
  }

  /**
   * Decodes and returns the next top-level JSON value.
   *
   * @throws JsonDataException if the value at index [nextIndex] is truncated or malformed. The
   *   exception message carries the value index and the cause is the original failure.
   * @throws NoSuchElementException if the stream is exhausted.
   */
  override fun next(): T {
    if (!hasNext()) {
      throw NoSuchElementException("No more JSON values at index $index")
    }
    val valueIndex = index
    try {
      val value = adapter.fromJson(reader)
      index = valueIndex + 1
      return value
    } catch (e: JsonDataException) {
      exhausted = true
      throw JsonDataException("Malformed JSON at value index $valueIndex, path ${reader.path}", e)
    } catch (e: IOException) {
      exhausted = true
      throw JsonDataException("Truncated JSON at value index $valueIndex, path ${reader.path}", e)
    }
  }

  /**
   * Peeks the next token at a document boundary. Consecutive top-level values require lenient
   * document boundaries, so leniency is granted only for the duration of this peek; the values
   * themselves are decoded with the reader's original strictness. An empty or whitespace-only
   * remainder is reported as [JsonReader.Token.END_DOCUMENT].
   */
  private fun peekDocumentBoundary(): JsonReader.Token {
    val wasLenient = reader.lenient
    reader.lenient = true
    return try {
      reader.peek()
    } catch (e: EOFException) {
      JsonReader.Token.END_DOCUMENT
    } finally {
      reader.lenient = wasLenient
    }
  }

  /**
   * Marks this iterator as closed. If this iterator owns its source, the source is closed; borrowed
   * sources are left open for their owner to close.
   */
  override fun close() {
    if (closed) return
    closed = true
    if (ownsSource) {
      reader.close()
    }
  }

  override fun toString(): String = "JsonValueIterator($adapter, index=$index)"
}
