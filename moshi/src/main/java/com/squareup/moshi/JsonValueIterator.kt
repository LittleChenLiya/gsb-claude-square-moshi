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
import java.io.IOException
import okio.BufferedSource
import okio.EOFException

/**
 * Reads a sequence of consecutive top-level JSON values, such as a stream of newline-delimited JSON
 * values, from a single [JsonReader] or [BufferedSource].
 *
 * Each call to [next] delegates exactly once to the original [adapter]'s [JsonAdapter.fromJson], so
 * exactly one complete JSON value is consumed and any following values are left untouched in the
 * underlying source, ready for the next call to [hasNext] or [next].
 *
 * Whitespace before a value and between values is skipped. If the remaining input contains only
 * whitespace (or no input at all), [hasNext] returns `false` and iteration ends normally without
 * throwing.
 *
 * If a value is truncated, the [IOException] reported while reading it is wrapped in a
 * [JsonEncodingException] whose message identifies the zero-based index of the value that could not
 * be read; the original failure is retained as the exception's [cause][Throwable.getCause].
 *
 * A value iterator constructed from an existing [JsonReader] merely borrows that reader: closing
 * the iterator marks it unusable but does **not** close the reader or its underlying source, so the
 * caller keeps ownership and may continue reading from it. A value iterator constructed from a
 * [BufferedSource] owns that source and closes both its reader and the source when the iterator is
 * closed.
 *
 * Reading several values from one stream is itself a lenient mode (a strict stream may contain
 * exactly one top-level value), so the reader is switched to [JsonReader.lenient] for the lifetime
 * of the iterator.
 *
 * This class is not safe for concurrent use.
 */
public class JsonValueIterator<T>
internal constructor(
  private val adapter: JsonAdapter<T>,
  private val reader: JsonReader,
  private val closeSource: Boolean,
) : Closeable {
  /** The reader's lenient setting before this iterator switched it to lenient. */
  private val previousLenient = reader.lenient

  init {
    // A strict reader rejects a second top-level value; a value iterator exists precisely to read
    // that sequence, so the reader must accept them.
    reader.lenient = true
  }

  /** Zero-based index of the next value returned by [next]. */
  private var nextIndex = 0

  private var closed = false

  /**
   * Returns true if another top-level JSON value can be read. Trailing whitespace and an
   * end-of-file after whitespace both produce `false`.
   */
  @Throws(IOException::class)
  public fun hasNext(): Boolean {
    check(!closed) { "JsonValueIterator is closed" }
    // A fresh, empty document reports its end of input as an EOFException rather than the
    // END_DOCUMENT token; whitespace EOF is a normal end to the sequence.
    return try {
      reader.peek() != JsonReader.Token.END_DOCUMENT
    } catch (e: EOFException) {
      false
    }
  }

  /**
   * Reads the next top-level JSON value with the original adapter. Exactly one value is consumed;
   * subsequent values remain available in the underlying source.
   *
   * @throws JsonEncodingException if reading the value fails because the input ended mid-value or
   *   was otherwise malformed. The exception message identifies the zero-based index of the value
   *   and the original failure is its [cause][Throwable.getCause].
   * @throws NoSuchElementException if no more values are available.
   */
  @Throws(IOException::class)
  public fun next(): T {
    check(!closed) { "JsonValueIterator is closed" }
    if (!hasNext()) throw NoSuchElementException("No more JSON values at index $nextIndex")
    val index = nextIndex++
    try {
      return adapter.fromJson(reader)
    } catch (e: IOException) {
      throw JsonEncodingException("Could not read JSON value at index $index", e)
    }
  }

  /**
   * Closes this iterator. When the iterator owns its source (it was created from a
   * [BufferedSource]), the underlying reader and source are closed. When it borrows an existing
   * [JsonReader], that reader is left open and the caller retains responsibility for it.
   */
  override fun close() {
    if (closed) return
    closed = true
    if (closeSource) {
      reader.close()
    } else {
      // Leave the borrowed reader exactly as we borrowed it.
      reader.lenient = previousLenient
    }
  }
}
