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

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import org.junit.Assert.assertThrows
import org.junit.Test

class UnknownFieldsPreservingJsonAdapterTest {
  private val delegate = CountingWidgetAdapter()
  private val adapter = UnknownFieldsPreservingJsonAdapter(delegate, setOf("id"))

  @Test
  fun roundTripPreservesUnknownFields() {
    val json = resource("/widget-with-unknown-fields.json")

    val widget = adapter.fromJson(json)!!

    assertThat(widget.id).isEqualTo(7L)
    assertThat(widget.unknownFields.keys).containsExactly("meta", "note").inOrder()
    assertThat(widget.unknownFields["note"]).isEqualTo("hello")
    @Suppress("UNCHECKED_CAST") val meta = widget.unknownFields["meta"] as Map<String, Any?>
    assertThat(meta["tags"]).isEqualTo(listOf("a", "b"))
    assertThat(meta["active"]).isEqualTo(true)
    assertThat(meta["score"]).isEqualTo(1.5)

    assertThat(adapter.toJson(widget)).isEqualTo(json)
  }

  @Test
  fun delegateIsInvokedExactlyOncePerCall() {
    val widget = adapter.fromJson("""{"id":1,"meta":"x"}""")!!
    adapter.toJson(widget)

    assertThat(delegate.fromJsonCalls).isEqualTo(1)
    assertThat(delegate.toJsonCalls).isEqualTo(1)
  }

  @Test
  fun explicitNullUnknownFieldIsPreserved() {
    val widget = adapter.fromJson("""{"id":1,"meta":null}""")!!

    assertThat(widget.unknownFields.containsKey("meta")).isTrue()
    assertThat(widget.unknownFields["meta"]).isNull()
    assertThat(adapter.toJson(widget)).isEqualTo("""{"id":1,"meta":null}""")
  }

  @Test
  fun missingUnknownFieldHasNoKey() {
    val widget = adapter.fromJson("""{"id":1}""")!!

    assertThat(widget.unknownFields).isEmpty()
    assertThat(adapter.toJson(widget)).isEqualTo("""{"id":1}""")
  }

  @Test
  fun duplicateUnknownFieldIsRejected() {
    val exception =
      assertThrows(JsonDataException::class.java) {
        adapter.fromJson("""{"id":1,"meta":"a","meta":"b"}""")
      }
    assertThat(exception).hasMessageThat().contains("meta")
  }

  @Test
  fun unknownFieldCannotOverwriteKnownFieldOnWrite() {
    val widget = Widget(1L)
    widget.unknownFields["id"] = "oops"

    assertThrows(JsonDataException::class.java) { adapter.toJson(widget) }
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.getResource(name)) { "Missing resource $name" }.readText().trim()

  class Widget(var id: Long) : UnknownFieldsCarrier {
    override val unknownFields: MutableMap<String, Any?> = LinkedHashMap()
  }

  /** A stand-in "original" adapter that only knows the `id` field and counts its invocations. */
  class CountingWidgetAdapter : JsonAdapter<Widget>() {
    var fromJsonCalls = 0
      private set
    var toJsonCalls = 0
      private set

    override fun fromJson(reader: JsonReader): Widget {
      fromJsonCalls++
      var id = 0L
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextLong()
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      return Widget(id)
    }

    override fun toJson(writer: JsonWriter, value: Widget) {
      toJsonCalls++
      writer.beginObject()
      writer.name("id").value(value.id)
      writer.endObject()
    }
  }
}
