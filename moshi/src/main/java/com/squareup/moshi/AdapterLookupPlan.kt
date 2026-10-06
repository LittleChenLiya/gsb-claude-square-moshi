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

import java.lang.reflect.Type
import javax.annotation.CheckReturnValue

/**
 * A normalized identity for a [JsonQualifier] annotation instance.
 *
 * The identity of a qualifier is decided by its annotation type together with its attribute
 * values: two qualifier instances of the same annotation type with equal attribute values share
 * one identity, while the same annotation with different parameters yields different identities.
 * Attribute values are normalized so that arrays and nested annotations compare by content, and
 * attributes are sorted by name so that equal qualifiers always have equal [attributes] maps.
 */
public class QualifierKey
private constructor(
  public val annotationType: Class<out Annotation>,
  public val attributes: Map<String, Any?>,
) {
  override fun equals(other: Any?): Boolean =
    other is QualifierKey &&
      annotationType == other.annotationType &&
      attributes == other.attributes

  override fun hashCode(): Int = 31 * annotationType.hashCode() + attributes.hashCode()

  override fun toString(): String = buildString {
    append('@').append(annotationType.name)
    if (attributes.isNotEmpty()) {
      attributes.entries.joinTo(this, prefix = "(", postfix = ")") { (name, value) ->
        "$name=${renderValue(value)}"
      }
    }
  }

  public companion object {
    /**
     * Returns the normalized identity of [annotation].
     *
     * @throws IllegalArgumentException if [annotation]'s type is not annotated with
     *   [JsonQualifier].
     */
    @JvmStatic
    @CheckReturnValue
    public fun of(annotation: Annotation): QualifierKey {
      val key = normalize(annotation)
      require(key.annotationType.isAnnotationPresent(JsonQualifier::class.java)) {
        "${key.annotationType} must have @JsonQualifier."
      }
      return key
    }

    private fun normalize(annotation: Annotation): QualifierKey {
      val annotationType = annotation.annotationClass.java
      val attributes = sortedMapOf<String, Any?>()
      for (method in annotationType.declaredMethods) {
        attributes[method.name] = normalizeValue(method.invoke(annotation))
      }
      return QualifierKey(annotationType, attributes)
    }

    private fun normalizeValue(value: Any?): Any? =
      when (value) {
        null -> null
        is Annotation -> normalize(value)
        is BooleanArray -> value.toList()
        is ByteArray -> value.toList()
        is CharArray -> value.toList()
        is ShortArray -> value.toList()
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is FloatArray -> value.toList()
        is DoubleArray -> value.toList()
        is Array<*> -> value.map { normalizeValue(it) }
        else -> value
      }

    private fun renderValue(value: Any?): String =
      when (value) {
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
        is String -> "\"$value\""
        else -> value.toString()
      }
  }
}

/**
 * A plan describing how [Moshi] would resolve a [JsonAdapter] for [type] and [qualifiers].
 *
 * The plan is computed without asking any [JsonAdapter.Factory] to create an adapter and without
 * ever invoking [JsonAdapter.fromJson] or [JsonAdapter.toJson]. [factories] lists the candidate
 * factories in their original registration order. Use [Moshi.adapter] to create an actual
 * adapter.
 */
public class AdapterLookupPlan
internal constructor(
  public val type: Type,
  public val qualifiers: List<QualifierKey>,
  public val factories: List<JsonAdapter.Factory>,
) {
  override fun equals(other: Any?): Boolean =
    other is AdapterLookupPlan &&
      type == other.type &&
      qualifiers == other.qualifiers &&
      factories == other.factories

  override fun hashCode(): Int {
    var result = type.hashCode()
    result = 31 * result + qualifiers.hashCode()
    result = 31 * result + factories.hashCode()
    return result
  }

  override fun toString(): String =
    "AdapterLookupPlan(type=$type, qualifiers=$qualifiers, factories=${factories.size} registered)"
}
