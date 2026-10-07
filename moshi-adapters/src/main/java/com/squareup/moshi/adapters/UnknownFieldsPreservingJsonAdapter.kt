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
package com.squareup.moshi.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * Implemented by model types that want to keep top-level JSON fields their regular
 * [JsonAdapter] does not know about.
 */
public interface UnknownFieldsCarrier {
  /**
   * Top-level JSON fields that were present in the JSON but are not known to the delegate
   * adapter. A key mapped to `null` means the field was present with an explicit JSON `null`;
   * a field that was absent from the JSON has no key at all. Values keep their JSON types:
   * [String], [Double], [Boolean], [Map], [List], or `null`.
   */
  public val unknownFields: MutableMap<String, Any?>
}

/**
 * A [JsonAdapter] wrapper that preserves top-level JSON fields the [delegate] adapter does not
 * know about. Known fields are streamed to the delegate unchanged; unknown fields are decoded
 * to their JSON value types and stored on [UnknownFieldsCarrier.unknownFields] so they survive a
 * read/write round trip.
 *
 * Only the top-level object is inspected; nested objects are handled entirely by the delegate
 * (known fields) or kept as opaque values (unknown fields).
 *
 * Decoding rejects duplicate unknown field names. Encoding rejects unknown fields whose name
 * collides with a known field so a round trip can never overwrite a known field.
 */
public class UnknownFieldsPreservingJsonAdapter<T>(
  private val delegate: JsonAdapter<T>,
  knownFieldNames: Set<String>,
) : JsonAdapter<T>() where T : UnknownFieldsCarrier {
  private val knownFieldNames: Set<String> = knownFieldNames.toSet()

  override fun fromJson(reader: JsonReader): T {
    if (reader.peek() == JsonReader.Token.NULL) {
      return delegate.fromJson(reader)
    }

    // Buffer known fields as raw JSON so the delegate sees them exactly as written.
    val knownBuffer = Buffer()
    val knownWriter = JsonWriter.of(knownBuffer)
    knownWriter.beginObject()
    val unknown = LinkedHashMap<String, Any?>()
    reader.beginObject()
    while (reader.hasNext()) {
      val name = reader.nextName()
      if (name in knownFieldNames) {
        knownWriter.name(name)
        knownWriter.value(reader.nextSource())
      } else {
        if (unknown.containsKey(name)) {
          throw JsonDataException("Duplicate unknown field '$name' at path ${reader.path}")
        }
        unknown[name] = reader.readJsonValue()
      }
    }
    reader.endObject()
    knownWriter.endObject()

    val result = delegate.fromJson(JsonReader.of(knownBuffer))
    result.unknownFields.clear()
    result.unknownFields.putAll(unknown)
    return result
  }

  override fun toJson(writer: JsonWriter, value: T) {
    val unknown = value.unknownFields
    for (name in unknown.keys) {
      if (name in knownFieldNames) {
        throw JsonDataException("Unknown field '$name' would overwrite a known field")
      }
    }

    // Buffer the delegate's object, then stream its fields through so they are written exactly
    // as the delegate encoded them, and append the unknown fields after them.
    val knownBuffer = Buffer()
    delegate.toJson(JsonWriter.of(knownBuffer), value)
    val knownReader = JsonReader.of(knownBuffer)
    writer.beginObject()
    knownReader.beginObject()
    while (knownReader.hasNext()) {
      val name = knownReader.nextName()
      if (unknown.containsKey(name)) {
        throw JsonDataException("Unknown field '$name' would overwrite a known field")
      }
      writer.name(name)
      writer.value(knownReader.nextSource())
    }
    knownReader.endObject()
    // Explicit nulls in unknown fields must survive a round trip even when the caller's writer
    // is configured to skip nulls, so force null serialization for the unknown fields only.
    val serializeNulls = writer.serializeNulls
    writer.serializeNulls = true
    try {
      for ((name, unknownValue) in unknown) {
        writer.name(name)
        writeJsonValue(writer, unknownValue)
      }
    } finally {
      writer.serializeNulls = serializeNulls
    }
    writer.endObject()
  }

  override fun toString(): String = "UnknownFieldsPreservingJsonAdapter($delegate)"

  private companion object {
    @Throws(JsonDataException::class)
    private fun writeJsonValue(writer: JsonWriter, value: Any?) {
      when (value) {
        null -> writer.nullValue()
        is String -> writer.value(value)
        is Boolean -> writer.value(value)
        is Double -> writer.value(value)
        is Float -> writer.value(value)
        is Long -> writer.value(value)
        is Number -> writer.value(value)
        is Map<*, *> -> {
          writer.beginObject()
          for ((key, mapValue) in value) {
            writer.name(key as? String ?: throw JsonDataException("Unexpected map key: $key"))
            writeJsonValue(writer, mapValue)
          }
          writer.endObject()
        }
        is List<*> -> {
          writer.beginArray()
          for (element in value) {
            writeJsonValue(writer, element)
          }
          writer.endArray()
        }
        else -> throw JsonDataException("Unsupported unknown field value: $value")
      }
    }
  }
}
