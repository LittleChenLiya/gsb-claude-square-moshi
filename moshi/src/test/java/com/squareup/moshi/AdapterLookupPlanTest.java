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
import static org.junit.Assert.assertThrows;

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
  // Three qualifier examples: a String attribute, an int attribute, and an int[] attribute.
  @JsonQualifier
  @Retention(RUNTIME)
  @interface Prefix {
    String value();
  }

  @JsonQualifier
  @Retention(RUNTIME)
  @interface Length {
    int value();
  }

  @JsonQualifier
  @Retention(RUNTIME)
  @interface Sizes {
    int[] value();
  }

  @SuppressWarnings("unused") // Qualifier instances are read reflectively.
  static final class QualifiedStrings {
    @Prefix("a") String prefixA1;
    @Prefix("a") String prefixA2;
    @Prefix("b") String prefixB;
    @Length(1) String length1;
    @Length(2) String length2;
    @Sizes({1, 2}) String sizes12First;
    @Sizes({1, 2}) String sizes12Second;
    @Sizes({1, 2, 3}) String sizes123;
    @Deprecated String notAQualifier;
  }

  private static Annotation qualifier(String fieldName) throws Exception {
    return QualifiedStrings.class.getDeclaredField(fieldName).getAnnotations()[0];
  }

  private static AdapterLookupPlan plan(Moshi moshi, Annotation... annotations) {
    Set<Annotation> set = new LinkedHashSet<>(Arrays.asList(annotations));
    return moshi.adapterLookupPlan(String.class, set);
  }

  @Test
  public void sameAnnotationWithDifferentParametersHasDistinctIdentities() throws Exception {
    Moshi moshi = new Moshi.Builder().build();

    QualifierKey prefixA = QualifierKey.of(qualifier("prefixA1"));
    QualifierKey prefixB = QualifierKey.of(qualifier("prefixB"));
    assertThat(prefixA).isNotEqualTo(prefixB);
    assertThat(prefixA.hashCode()).isNotEqualTo(prefixB.hashCode());

    QualifierKey length1 = QualifierKey.of(qualifier("length1"));
    QualifierKey length2 = QualifierKey.of(qualifier("length2"));
    assertThat(length1).isNotEqualTo(length2);

    AdapterLookupPlan planA = plan(moshi, qualifier("prefixA1"));
    AdapterLookupPlan planB = plan(moshi, qualifier("prefixB"));
    assertThat(planA).isNotEqualTo(planB);
    assertThat(planA.getQualifiers()).containsExactly(prefixA);
    assertThat(planB.getQualifiers()).containsExactly(prefixB);
  }

  @Test
  public void equivalentInstancesShareOneIdentity() throws Exception {
    Moshi moshi = new Moshi.Builder().build();

    QualifierKey first = QualifierKey.of(qualifier("prefixA1"));
    QualifierKey second = QualifierKey.of(qualifier("prefixA2"));
    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
    assertThat(first.getAnnotationType()).isEqualTo(Prefix.class);
    assertThat(first.getAttributes()).containsExactly("value", "a");

    AdapterLookupPlan firstPlan = plan(moshi, qualifier("prefixA1"));
    AdapterLookupPlan secondPlan = plan(moshi, qualifier("prefixA2"));
    assertThat(firstPlan).isEqualTo(secondPlan);
    assertThat(firstPlan.hashCode()).isEqualTo(secondPlan.hashCode());
  }

  @Test
  public void differentAnnotationTypesHaveDistinctIdentities() throws Exception {
    QualifierKey prefix = QualifierKey.of(qualifier("prefixA1"));
    QualifierKey length = QualifierKey.of(qualifier("length1"));
    QualifierKey sizes = QualifierKey.of(qualifier("sizes12First"));
    assertThat(prefix).isNotEqualTo(length);
    assertThat(prefix).isNotEqualTo(sizes);
    assertThat(length).isNotEqualTo(sizes);
  }

  @Test
  public void arrayAttributesAreNormalizedByContent() throws Exception {
    QualifierKey first = QualifierKey.of(qualifier("sizes12First"));
    QualifierKey second = QualifierKey.of(qualifier("sizes12Second"));
    QualifierKey different = QualifierKey.of(qualifier("sizes123"));

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
    assertThat(first).isNotEqualTo(different);
    assertThat(first.getAttributes()).containsExactly("value", Arrays.asList(1, 2));
    assertThat(different.getAttributes()).containsExactly("value", Arrays.asList(1, 2, 3));
  }

  @Test
  public void equivalentQualifiersCollapseToOneIdentityInPlan() throws Exception {
    Moshi moshi = new Moshi.Builder().build();

    AdapterLookupPlan plan =
        plan(moshi, qualifier("prefixA1"), qualifier("prefixA2"), qualifier("length1"));

    assertThat(plan.getQualifiers())
        .containsExactly(
            QualifierKey.of(qualifier("prefixA1")), QualifierKey.of(qualifier("length1")))
        .inOrder();
  }

  @Test
  public void lookupPlanPreservesFactoryRegistrationOrder() throws Exception {
    JsonAdapter.Factory first = new CountingFactory();
    JsonAdapter.Factory second = new CountingFactory();
    Moshi moshi = new Moshi.Builder().add(first).add(second).build();

    AdapterLookupPlan plan = plan(moshi, qualifier("prefixA1"));

    List<JsonAdapter.Factory> factories = plan.getFactories();
    assertThat(factories.get(0)).isSameInstanceAs(first);
    assertThat(factories.get(1)).isSameInstanceAs(second);
    // Built-in factories follow the registered ones.
    assertThat(factories.size()).isGreaterThan(2);
    assertThat(plan.getType()).isEqualTo((Type) String.class);
  }

  @Test
  public void lookupPlanOnlyPlansWithoutConverting() throws Exception {
    CountingFactory factory = new CountingFactory();
    Moshi moshi = new Moshi.Builder().add(factory).build();

    plan(moshi, qualifier("prefixA1"));
    plan(moshi, qualifier("prefixB"));
    plan(moshi, qualifier("length1"), qualifier("sizes123"));

    // Building lookup plans never creates adapters and never converts.
    assertThat(factory.createCount).isEqualTo(0);
    assertThat(factory.adapter.fromJsonCount).isEqualTo(0);
    assertThat(factory.adapter.toJsonCount).isEqualTo(0);

    // The regular Moshi.adapter path is unaffected.
    JsonAdapter<String> adapter = moshi.adapter(String.class);
    assertThat(adapter.toJson("hello")).isEqualTo("\"hello\"");
    assertThat(adapter.fromJson("\"hello\"")).isEqualTo("hello");
    assertThat(factory.createCount).isEqualTo(1);
    assertThat(factory.adapter.fromJsonCount).isEqualTo(1);
    assertThat(factory.adapter.toJsonCount).isEqualTo(1);
  }

  @Test
  public void nonQualifierAnnotationsAreRejected() throws Exception {
    Annotation notAQualifier = qualifier("notAQualifier");
    assertThrows(IllegalArgumentException.class, () -> QualifierKey.of(notAQualifier));

    Moshi moshi = new Moshi.Builder().build();
    assertThrows(IllegalArgumentException.class, () -> plan(moshi, notAQualifier));
  }

  static final class CountingAdapter extends JsonAdapter<Object> {
    int fromJsonCount;
    int toJsonCount;

    @Override
    public Object fromJson(JsonReader reader) throws IOException {
      fromJsonCount++;
      return reader.readJsonValue();
    }

    @Override
    public void toJson(JsonWriter writer, Object value) throws IOException {
      toJsonCount++;
      writer.value(String.valueOf(value));
    }
  }

  static final class CountingFactory implements JsonAdapter.Factory {
    int createCount;
    final CountingAdapter adapter = new CountingAdapter();

    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
      createCount++;
      if (type == String.class) {
        return adapter;
      }
      return null;
    }
  }
}
