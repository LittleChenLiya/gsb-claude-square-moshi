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
package com.squareup.moshi;

import static com.google.common.truth.Truth.assertThat;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class AdapterLookupPlanTest {
  /** Qualifier example 1: a marker qualifier without attributes. */
  @JsonQualifier
  @Retention(RUNTIME)
  @interface HexColor {}

  /** Qualifier example 2: a qualifier with a single string attribute. */
  @JsonQualifier
  @Retention(RUNTIME)
  @interface Prefix {
    String value();
  }

  /** Qualifier example 3: a qualifier with multiple attributes. */
  @JsonQualifier
  @Retention(RUNTIME)
  @interface Bounded {
    int min();

    int max();
  }

  @HexColor String hexColor;
  @Prefix("a") String prefixA1;
  @Prefix("a") String prefixA2;
  @Prefix("b") String prefixB;
  @Bounded(min = 0, max = 10) String boundedLow1;
  @Bounded(min = 0, max = 10) String boundedLow2;
  @Bounded(min = 0, max = 99) String boundedHigh;

  @Test
  public void sameAnnotationWithDifferentParametersHasDistinctIdentities() throws Exception {
    QualifierIdentity prefixA = QualifierIdentity.of(qualifier("prefixA1", Prefix.class));
    QualifierIdentity prefixB = QualifierIdentity.of(qualifier("prefixB", Prefix.class));
    assertThat(prefixA).isNotEqualTo(prefixB);

    QualifierIdentity boundedLow = QualifierIdentity.of(qualifier("boundedLow1", Bounded.class));
    QualifierIdentity boundedHigh = QualifierIdentity.of(qualifier("boundedHigh", Bounded.class));
    assertThat(boundedLow).isNotEqualTo(boundedHigh);

    Moshi moshi = new Moshi.Builder().build();
    AdapterLookupPlan planA =
        moshi.lookupPlan(String.class, annotations(qualifier("prefixA1", Prefix.class)));
    AdapterLookupPlan planB =
        moshi.lookupPlan(String.class, annotations(qualifier("prefixB", Prefix.class)));
    assertThat(planA.getQualifiers()).containsExactly(prefixA);
    assertThat(planB.getQualifiers()).containsExactly(prefixB);
    assertThat(planA).isNotEqualTo(planB);
  }

  @Test
  public void equivalentInstancesShareIdentity() throws Exception {
    // Two distinct proxy instances with equal attribute values.
    QualifierIdentity first = QualifierIdentity.of(qualifier("prefixA1", Prefix.class));
    QualifierIdentity second = QualifierIdentity.of(qualifier("prefixA2", Prefix.class));
    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());

    QualifierIdentity boundedFirst =
        QualifierIdentity.of(qualifier("boundedLow1", Bounded.class));
    QualifierIdentity boundedSecond =
        QualifierIdentity.of(qualifier("boundedLow2", Bounded.class));
    assertThat(boundedFirst).isEqualTo(boundedSecond);
    assertThat(boundedFirst.hashCode()).isEqualTo(boundedSecond.hashCode());

    // A hand-written (non-proxy) implementation normalizes to the same identity.
    assertThat(QualifierIdentity.of(newPrefix("a"))).isEqualTo(first);
    assertThat(QualifierIdentity.of(newPrefix("b"))).isNotEqualTo(first);

    // Marker qualifiers have no attributes, so all instances share one identity.
    QualifierIdentity hexColor = QualifierIdentity.of(qualifier("hexColor", HexColor.class));
    assertThat(hexColor.getAttributes()).isEmpty();
    assertThat(hexColor.getAnnotationType()).isEqualTo(HexColor.class);

    // Plans built from equivalent instances are equal.
    Moshi moshi = new Moshi.Builder().build();
    AdapterLookupPlan planFromProxy =
        moshi.lookupPlan(String.class, annotations(qualifier("prefixA1", Prefix.class)));
    AdapterLookupPlan planFromManual =
        moshi.lookupPlan(String.class, annotations(newPrefix("a")));
    assertThat(planFromManual).isEqualTo(planFromProxy);
    assertThat(planFromProxy.qualifierIdentity(newPrefix("a"))).isEqualTo(first);
    assertThat(planFromProxy.qualifierIdentity(newPrefix("b"))).isNull();
  }

  @Test
  public void buildingPlanNeverInvokesConversionCallbacks() throws Exception {
    CountingAdapter countingAdapter = new CountingAdapter();
    Moshi moshi =
        new Moshi.Builder()
            .add(
                new JsonAdapter.Factory() {
                  @Override
                  public JsonAdapter<?> create(
                      Type type, Set<? extends Annotation> annotations, Moshi moshi) {
                    for (Annotation annotation : annotations) {
                      if (annotation instanceof Prefix) {
                        return countingAdapter;
                      }
                    }
                    return null;
                  }
                })
            .build();

    Set<Annotation> qualifiers = annotations(qualifier("prefixA1", Prefix.class));
    AdapterLookupPlan plan = moshi.lookupPlan(String.class, qualifiers);
    assertThat(plan.getQualifiers()).hasSize(1);
    assertThat(countingAdapter.fromJsonCount).isEqualTo(0);
    assertThat(countingAdapter.toJsonCount).isEqualTo(0);

    // Repeated planning, with and without qualifiers, still performs no conversion.
    moshi.lookupPlan(String.class, qualifiers);
    moshi.lookupPlan(String.class);
    moshi.lookupPlan(String.class, annotations(qualifier("boundedLow1", Bounded.class)));
    assertThat(countingAdapter.fromJsonCount).isEqualTo(0);
    assertThat(countingAdapter.toJsonCount).isEqualTo(0);

    // Creating an adapter is not a conversion either.
    JsonAdapter<String> adapter = moshi.adapter(String.class, qualifiers);
    assertThat(countingAdapter.fromJsonCount).isEqualTo(0);
    assertThat(countingAdapter.toJsonCount).isEqualTo(0);

    // Sanity check that the counters actually observe real conversions.
    assertThat(adapter.toJson("hello")).isEqualTo("\"hello\"");
    assertThat(adapter.fromJson("\"world\"")).isEqualTo("world");
    assertThat(countingAdapter.fromJsonCount).isEqualTo(1);
    assertThat(countingAdapter.toJsonCount).isEqualTo(1);
  }

  @Test
  public void planPreservesFactoryRegistrationOrder() {
    JsonAdapter.Factory first = namedFactory("first");
    JsonAdapter.Factory second = namedFactory("second");
    JsonAdapter.Factory last = namedFactory("last");
    Moshi moshi = new Moshi.Builder().add(first).add(second).addLast(last).build();

    AdapterLookupPlan plan = moshi.lookupPlan(String.class);
    List<JsonAdapter.Factory> factories = plan.getFactories();
    assertThat(factories.get(0)).isSameInstanceAs(first);
    assertThat(factories.get(1)).isSameInstanceAs(second);
    assertThat(factories.get(2)).isSameInstanceAs(last);
    // Built-in factories trail the custom ones.
    assertThat(factories).hasSize(3 + Moshi.BUILT_IN_FACTORIES.size());
  }

  @Test
  public void regularAdapterLookupIsUnaffected() throws Exception {
    JsonAdapter<String> prefixAdapter =
        new JsonAdapter<String>() {
          @Override
          public String fromJson(JsonReader reader) throws IOException {
            return "prefix:" + reader.nextString();
          }

          @Override
          public void toJson(JsonWriter writer, String value) throws IOException {
            writer.value("prefix:" + value);
          }
        };
    Moshi moshi =
        new Moshi.Builder()
            .add(
                new JsonAdapter.Factory() {
                  @Override
                  public JsonAdapter<?> create(
                      Type type, Set<? extends Annotation> annotations, Moshi moshi) {
                    for (Annotation annotation : annotations) {
                      if (annotation instanceof Prefix) {
                        return prefixAdapter;
                      }
                    }
                    return null;
                  }
                })
            .build();

    JsonAdapter<String> qualified =
        moshi.adapter(String.class, annotations(qualifier("prefixA1", Prefix.class)));
    assertThat(qualified.toJson("a")).isEqualTo("\"prefix:a\"");
    assertThat(qualified.fromJson("\"b\"")).isEqualTo("prefix:b");

    JsonAdapter<String> plain = moshi.adapter(String.class);
    assertThat(plain.toJson("a")).isEqualTo("\"a\"");
  }

  private static JsonAdapter.Factory namedFactory(String name) {
    return new JsonAdapter.Factory() {
      @Override
      public JsonAdapter<?> create(
          Type type, Set<? extends Annotation> annotations, Moshi moshi) {
        return null;
      }

      @Override
      public String toString() {
        return name;
      }
    };
  }

  private static Prefix newPrefix(String value) {
    return new Prefix() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return Prefix.class;
      }

      @Override
      public String value() {
        return value;
      }
    };
  }

  private static Set<Annotation> annotations(Annotation... annotations) {
    return new LinkedHashSet<>(Arrays.asList(annotations));
  }

  private static <A extends Annotation> A qualifier(String fieldName, Class<A> type)
      throws NoSuchFieldException {
    return AdapterLookupPlanTest.class.getDeclaredField(fieldName).getAnnotation(type);
  }

  static final class CountingAdapter extends JsonAdapter<String> {
    int fromJsonCount;
    int toJsonCount;

    @Override
    public String fromJson(JsonReader reader) throws IOException {
      fromJsonCount++;
      return reader.nextString();
    }

    @Override
    public void toJson(JsonWriter writer, String value) throws IOException {
      toJsonCount++;
      writer.value(value);
    }
  }
}
