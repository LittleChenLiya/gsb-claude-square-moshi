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

/**
 * The normalized identity of a qualifier annotation.
 *
 * Two qualifiers share an identity if and only if they have the same annotation type and equal
 * attribute values, no matter how the annotation instances were created. For example, two
 * separate `@Prefix("a")` instances have the same identity, while `@Prefix("a")` and
 * `@Prefix("b")` do not.
 */
public class QualifierIdentity
private constructor(
  public val annotationType: Class<out Annotation>,
  attributes: Map<String, Any?>,
) {
  /**
   * The qualifier's attribute values keyed by attribute name, sorted by name. Array-typed
   * attribute values are stored as lists so that equality and hash codes are value-based.
   */
  public val attributes: Map<String, Any?> = attributes

  override fun equals(other: Any?): Boolean =
    other is QualifierIdentity &&
      annotationType == other.annotationType &&
      attributes == other.attributes

  override fun hashCode(): Int = 31 * annotationType.hashCode() + attributes.hashCode()

  override fun toString(): String = buildString {
    append('@').append(annotationType.name)
    if (attributes.isNotEmpty()) {
      attributes.entries.joinTo(this, prefix = "(", postfix = ")") { (name, value) ->
        "$name=$value"
      }
    }
  }

  public companion object {
    /** Returns the normalized identity of [annotation]. */
    @JvmStatic
    public fun of(annotation: Annotation): QualifierIdentity {
      @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
      val annotationType =
        (annotation as java.lang.annotation.Annotation).annotationType()
          as Class<out Annotation>
      val attributes = sortedMapOf<String, Any?>()
      for (method in annotationType.declaredMethods) {
        method.isAccessible = true
        attributes[method.name] = canonicalizeAttributeValue(method.invoke(annotation))
      }
      return QualifierIdentity(annotationType, attributes)
    }

    private fun canonicalizeAttributeValue(value: Any?): Any? =
      when (value) {
        is BooleanArray -> value.toList()
        is ByteArray -> value.toList()
        is CharArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is ShortArray -> value.toList()
        is Array<*> -> value.map(::canonicalizeAttributeValue)
        else -> value
      }
  }
}

/**
 * A plan describing how [Moshi.adapter] would resolve a JSON adapter for [type] with its
 * qualifiers: the normalized [qualifiers] identities plus the candidate [factories] in their
 * original registration order.
 *
 * Building a plan never creates adapters and never calls [JsonAdapter.fromJson] or
 * [JsonAdapter.toJson].
 */
public class AdapterLookupPlan(
  /** The canonicalized type to look up. */
  public val type: Type,
  /** The normalized qualifier identities, in encounter order with duplicates removed. */
  public val qualifiers: List<QualifierIdentity>,
  /** The candidate factories that would be consulted, in original registration order. */
  public val factories: List<JsonAdapter.Factory>,
) {
  /** Returns the identity of [annotation] as it appears in this plan, or null if it is absent. */
  public fun qualifierIdentity(annotation: Annotation): QualifierIdentity? {
    val identity = QualifierIdentity.of(annotation)
    return if (identity in qualifiers) identity else null
  }

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
    "AdapterLookupPlan(type=$type, qualifiers=$qualifiers, factories=${factories.size})"
}
