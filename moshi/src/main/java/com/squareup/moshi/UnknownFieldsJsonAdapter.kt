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

import okio.BufferedSource

/**
 * A decoded value together with the top-level JSON fields that its adapter did not recognize.
 *
 * [value] is decoded by the wrapped adapter exactly as it would decode the document on its own.
 * [unknownFields] holds every other top-level member of the JSON object, preserving the original
 * value types read from the document. A member whose value is JSON `null` is present in the map
 * mapped to `null`; a member that was absent from the document has no map entry at all.
 *
 * On encoding, [value] is written by the wrapped adapter and [unknownFields] are appended
 * afterwards. An unknown field never replaces a field produced by the wrapped adapter.
 *
 * Only the top-level object is treated this way: fields nested inside known or unknown values are
 * handled by the wrapped adapter unchanged.
 */
public class UnknownFields<T>(
  public val value: T,
  public val unknownFields: Map<String, Any?>,
) {
  /** Convenience for callers that don't have any unknown fields to carry. */
  public constructor(value: T) : this(value, emptyMap())

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is UnknownFields<*>) return false
    return value == other.value && unknownFields == other.unknownFields
  }

  override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + unknownFields.hashCode()

  override fun toString(): String = "UnknownFields(value=$value, unknownFields=$unknownFields)"
}

/**
 * Wraps [delegate] so that top-level JSON fields the delegate doesn't recognize are captured and
 * preserved instead of being skipped.
 *
 * The wrapped adapter is invoked exactly once per read and once per write; known fields take the
 * exact same code path through [delegate] that they would without this wrapper.
 *
 * Reading rules:
 * * Unknown top-level fields are stored in [UnknownFields.unknownFields] with their original value
 *   types (strings, booleans, numbers as `Long`/`Double`, nulls, lists and maps).
 * * An explicit JSON `null` is stored as a present map entry with a `null` value; a field missing
 *   from the document simply has no entry.
 * * A top-level name that occurs more than once and is never consumed by [delegate] is rejected
 *   with a [JsonDataException]. Repeated names that the delegate handles are left to the delegate.
 * * Unknown fields nested inside another value are not treated specially.
 *
 * Writing rules:
 * * [delegate] encodes the known fields first, then the unknown fields are appended. Unknown fields
 *   never overwrite fields produced by the delegate; a name collision is rejected with a
 *   [JsonDataException].
 * * JSON `null` values in [UnknownFields.unknownFields] are always emitted.
 *
 * The document value must be a JSON object. Apply [nullSafe] if the document may be JSON `null`.
 */
public class UnknownFieldsJsonAdapter<T>(private val delegate: JsonAdapter<T>) :
  JsonAdapter<UnknownFields<T>>() {
  override fun fromJson(reader: JsonReader): UnknownFields<T> {
    val capturing = CapturingJsonReader(reader)
    val value = delegate.fromJson(capturing)
    return UnknownFields(value, capturing.unknownFields())
  }

  override fun toJson(writer: JsonWriter, value: UnknownFields<T>) {
    // Encode the value through the delegate into a value tree so that the set of names it writes
    // is known before anything reaches `writer`. `serializeNulls` is mirrored so the tree matches
    // what the delegate would have written.
    val valueWriter = `-JsonValueWriter`()
    valueWriter.serializeNulls = writer.serializeNulls
    delegate.toJson(valueWriter, value.value)
    val knownTree = valueWriter.root()

    val knownFields =
      when (knownTree) {
        is Map<*, *> ->
          LinkedHashMap<String, Any?>().apply {
            for ((key, fieldValue) in knownTree) {
              if (key is String) put(key, fieldValue)
            }
          }
        null ->
          throw IllegalStateException(
            "$delegate encoded ${value.value} as null; cannot append unknown fields to it"
          )
        else ->
          throw IllegalStateException(
            "$delegate encoded ${value.value} as $knownTree, not a JSON object; " +
              "cannot append unknown fields to it"
          )
      }
    val known = knownFields.keys.toMutableSet()

    for (name in value.unknownFields.keys) {
      if (!known.add(name)) {
        throw JsonDataException(
          "Unknown field '$name' would overwrite a field encoded by $delegate"
        )
      }
    }

    // Unknown nulls must always be emitted, so the merged object is written with null serialization
    // enabled. Names already present in the known tree are not added again, so this cannot add a
    // known null that the delegate suppressed.
    val previousSerializeNulls = writer.serializeNulls
    writer.serializeNulls = true
    try {
      writer.beginObject()
      for ((name, fieldValue) in knownFields) {
        writer.name(name)
        writer.jsonValue(fieldValue)
      }
      for ((name, fieldValue) in value.unknownFields) {
        writer.name(name)
        writer.jsonValue(fieldValue)
      }
      writer.endObject()
    } finally {
      writer.serializeNulls = previousSerializeNulls
    }
  }

  override fun toString(): String = "UnknownFieldsJsonAdapter($delegate)"

  /**
   * Forwards every call to [delegate] while observing which top-level names the wrapped adapter
   * consumes. Names the adapter skips at the top level are read as values and collected.
   */
  private class CapturingJsonReader(private val delegate: JsonReader) : JsonReader(delegate) {
    private val unknown = LinkedHashMap<String, Any?>()

    /** Depth of open objects. 1 while reading fields of the top-level object. */
    private var objectDepth = 0

    /** A top-level name that was consumed but whose value hasn't been consumed yet. */
    private var pendingName: String? = null

    fun unknownFields(): Map<String, Any?> = unknown

    /**
     * Copies the delegate's path-tracking state onto this reader so that the [path] reported in an
     * exception thrown through this reader stays meaningful. Only path reporting is mirrored; the
     * adapter behavior is entirely the delegate's.
     */
    private fun syncPath() {
      copyPathStateFrom(delegate)
    }

    private fun noteValueConsumed() {
      pendingName = null
    }

    private fun recordUnknown(name: String, value: Any?) {
      if (unknown.containsKey(name)) {
        throw JsonDataException("Unknown field '$name' appears more than once at ${delegate.path}")
      }
      unknown[name] = value
    }

    /**
     * Reads a complete JSON value from [delegate] preserving value types. Numbers are read as
     * [Long] when they fit and [Double] otherwise, matching the rest of Moshi.
     */
    private fun readValue(): Any? {
      syncPath()
      return when (delegate.peek()) {
        Token.BEGIN_ARRAY -> {
          delegate.beginArray()
          val result = ArrayList<Any?>()
          while (delegate.hasNext()) {
            result.add(readValue())
          }
          delegate.endArray()
          result
        }
        Token.BEGIN_OBJECT -> {
          delegate.beginObject()
          val result = LinkedHashMap<String, Any?>()
          while (delegate.hasNext()) {
            val nestedName = delegate.nextName()
            result[nestedName] = readValue()
          }
          delegate.endObject()
          result
        }
        Token.STRING -> delegate.nextString()
        Token.NUMBER -> {
          val number = delegate.nextString()
          number.toLongOrNull() ?: number.toDouble()
        }
        Token.BOOLEAN -> delegate.nextBoolean()
        Token.NULL -> delegate.nextNull<Any>()
        else ->
          throw IllegalStateException(
            "Expected a value but was ${delegate.peek()} at ${delegate.path}"
          )
      }
    }

    override fun beginArray() {
      noteValueConsumed()
      delegate.beginArray()
      syncPath()
    }

    override fun endArray() {
      delegate.endArray()
      syncPath()
    }

    override fun beginObject() {
      noteValueConsumed()
      delegate.beginObject()
      objectDepth++
      syncPath()
    }

    override fun endObject() {
      delegate.endObject()
      objectDepth--
      syncPath()
    }

    override fun hasNext(): Boolean = delegate.hasNext()

    override fun peek(): Token {
      syncPath()
      return delegate.peek()
    }

    override fun nextName(): String {
      val name = delegate.nextName()
      pendingName = if (objectDepth == 1) name else null
      syncPath()
      return name
    }

    override fun selectName(options: Options): Int {
      val index = delegate.selectName(options)
      if (objectDepth == 1) {
        // A selected name is a known field; a -1 leaves the name unconsumed.
        pendingName = if (index >= 0) options.strings()[index] else null
      }
      syncPath()
      return index
    }

    override fun skipName() {
      if (objectDepth == 1) {
        pendingName = delegate.nextName()
      } else {
        delegate.skipName()
      }
      syncPath()
    }

    override fun nextString(): String {
      noteValueConsumed()
      val result = delegate.nextString()
      syncPath()
      return result
    }

    override fun selectString(options: Options): Int {
      val result = delegate.selectString(options)
      noteValueConsumed()
      syncPath()
      return result
    }

    override fun nextBoolean(): Boolean {
      noteValueConsumed()
      val result = delegate.nextBoolean()
      syncPath()
      return result
    }

    override fun <T> nextNull(): T? {
      noteValueConsumed()
      val result = delegate.nextNull<T>()
      syncPath()
      return result
    }

    override fun nextDouble(): Double {
      noteValueConsumed()
      val result = delegate.nextDouble()
      syncPath()
      return result
    }

    override fun nextLong(): Long {
      noteValueConsumed()
      val result = delegate.nextLong()
      syncPath()
      return result
    }

    override fun nextInt(): Int {
      noteValueConsumed()
      val result = delegate.nextInt()
      syncPath()
      return result
    }

    override fun nextSource(): BufferedSource {
      noteValueConsumed()
      val result = delegate.nextSource()
      syncPath()
      return result
    }

    override fun skipValue() {
      val name = pendingName
      if (objectDepth == 1 && name != null) {
        recordUnknown(name, readValue())
      } else {
        delegate.skipValue()
      }
      pendingName = null
      syncPath()
    }

    override fun peekJson(): JsonReader {
      syncPath()
      return delegate.peekJson()
    }

    override fun promoteNameToValue() {
      pendingName = null
      delegate.promoteNameToValue()
      syncPath()
    }

    override fun close() {
      delegate.close()
    }
  }
}
