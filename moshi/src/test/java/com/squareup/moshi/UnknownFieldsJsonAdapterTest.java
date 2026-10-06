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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class UnknownFieldsJsonAdapterTest {

  /** Simple known-value type: only {@code id} is a known field. */
  public static final class Record {
    final int id;

    public Record() {
      this(0);
    }

    Record(int id) {
      this.id = id;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Record && ((Record) other).id == id;
    }

    @Override
    public int hashCode() {
      return id;
    }

    @Override
    public String toString() {
      return "Record(id=" + id + ")";
    }
  }

  private final Moshi moshi = new Moshi.Builder().build();

  @SuppressWarnings("unchecked")
  private final JsonAdapter<Record> recordAdapter =
      (JsonAdapter<Record>) moshi.adapter(Record.class);

  private final JsonAdapter<UnknownFields<Record>> adapter =
      new UnknownFieldsJsonAdapter<>(recordAdapter);

  private static String fixture(String name) throws IOException {
    return new String(
        Files.readAllBytes(Paths.get("src/test/resources/unknown-fields", name)),
        StandardCharsets.UTF_8);
  }

  @Test
  public void idWithUnknownMetaRoundTrips() throws Exception {
    String json = fixture("meta.json");

    UnknownFields<Record> result = adapter.fromJson(json);

    assertThat(result.getValue().id).isEqualTo(1);
    assertThat(result.getUnknownFields()).containsKey("meta");

    Object meta = result.getUnknownFields().get("meta");
    assertThat(meta).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> metaMap = (Map<String, Object>) meta;
    assertThat(metaMap).hasSize(4);
    assertThat(metaMap.get("source")).isEqualTo("x");
    assertThat((Iterable<?>) metaMap.get("tags")).containsExactly("a", "b").inOrder();
    // Original value types are preserved: integers stay integers.
    assertThat(metaMap.get("n")).isEqualTo(42L);
    assertThat(metaMap.get("active")).isEqualTo(true);

    // Round-trip: known field first, unknown meta appended with its original structure.
    assertThat(adapter.toJson(result)).isEqualTo(json);
  }

  @Test
  public void explicitNullIsPreservedAsPresentNull() throws Exception {
    UnknownFields<Record> result = adapter.fromJson(fixture("meta-null.json"));

    assertThat(result.getValue().id).isEqualTo(2);
    assertThat(result.getUnknownFields()).containsKey("meta");
    assertThat(result.getUnknownFields().get("meta")).isNull();

    assertThat(adapter.toJson(result)).isEqualTo("{\"id\":2,\"meta\":null}");
  }

  @Test
  public void absentFieldHasNoKey() throws Exception {
    UnknownFields<Record> result = adapter.fromJson(fixture("meta-absent.json"));

    assertThat(result.getValue().id).isEqualTo(3);
    assertThat(result.getUnknownFields()).doesNotContainKey("meta");
    assertThat(result.getUnknownFields()).isEmpty();

    assertThat(adapter.toJson(result)).isEqualTo("{\"id\":3}");
  }

  @Test
  public void duplicateUnknownNameIsRejected() throws Exception {
    try {
      adapter.fromJson(fixture("meta-duplicate.json"));
      fail();
    } catch (JsonDataException expected) {
      assertThat(expected).hasMessageThat().contains("meta");
    }
  }

  @Test
  public void primitiveUnknownValueTypesArePreserved() throws Exception {
    UnknownFields<Record> result =
        adapter.fromJson("{\"id\":7,\"s\":\"hi\",\"b\":false,\"i\":42,\"f\":1.5,\"n\":null}");

    Map<String, Object> unknown = result.getUnknownFields();
    assertThat(unknown.get("s")).isEqualTo("hi");
    assertThat(unknown.get("b")).isEqualTo(false);
    assertThat(unknown.get("i")).isEqualTo(42L);
    assertThat(unknown.get("f")).isEqualTo(1.5);
    assertThat(unknown).containsKey("n");
    assertThat(unknown.get("n")).isNull();
  }

  @Test
  public void unknownFieldCannotOverwriteKnownFieldOnWrite() {
    Map<String, Object> unknown = new LinkedHashMap<>();
    unknown.put("id", 99);
    UnknownFields<Record> value = new UnknownFields<>(new Record(5), unknown);

    try {
      adapter.toJson(value);
      fail();
    } catch (JsonDataException expected) {
      assertThat(expected).hasMessageThat().contains("id");
    }
  }

  @Test
  public void delegateIsInvokedOncePerReadAndWrite() throws Exception {
    final int[] reads = {0};
    final int[] writes = {0};
    JsonAdapter<Record> counting =
        new JsonAdapter<Record>() {
          @Override
          public Record fromJson(JsonReader reader) throws IOException {
            reads[0]++;
            return recordAdapter.fromJson(reader);
          }

          @Override
          public void toJson(JsonWriter writer, Record value) throws IOException {
            writes[0]++;
            recordAdapter.toJson(writer, value);
          }
        };
    JsonAdapter<UnknownFields<Record>> countingWrapper = new UnknownFieldsJsonAdapter<>(counting);

    UnknownFields<Record> result = countingWrapper.fromJson("{\"id\":8,\"meta\":{\"a\":1}}");
    countingWrapper.toJson(result);

    assertThat(reads[0]).isEqualTo(1);
    assertThat(writes[0]).isEqualTo(1);
  }

  @Test
  public void knownFieldsStillHandledByDelegate() throws Exception {
    UnknownFields<Record> result = adapter.fromJson("{\"id\":11,\"extra\":\"kept\"}");

    // The known field flows through the original adapter and is decoded normally.
    assertThat(result.getValue().id).isEqualTo(11);
    assertThat(result.getUnknownFields().get("extra")).isEqualTo("kept");
  }

  @Test
  public void nestedUnknownFieldsAreNotExtractedToTopLevel() throws Exception {
    UnknownFields<Record> result =
        adapter.fromJson("{\"id\":12,\"meta\":{\"inner\":{\"deep\":true}}}");

    Object meta = result.getUnknownFields().get("meta");
    assertThat(meta).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> metaMap = (Map<String, Object>) meta;
    assertThat(metaMap.keySet()).containsExactly("inner");
    assertThat(result.getUnknownFields().keySet()).containsExactly("meta");
  }

  @Test
  public void nullSafeAdapterHandlesJsonNullDocument() throws Exception {
    JsonAdapter<UnknownFields<Record>> nullSafeAdapter = adapter.nullSafe();

    assertThat(nullSafeAdapter.fromJson("null")).isNull();
    assertThat(nullSafeAdapter.toJson(null)).isEqualTo("null");
  }

  @Test
  public void worksWithValueTreeReadersAndWriters() {
    Map<String, Object> tree = new LinkedHashMap<>();
    tree.put("id", 13);
    tree.put("meta", null);

    UnknownFields<Record> result = adapter.fromJsonValue(tree);

    assertThat(result.getValue().id).isEqualTo(13);
    assertThat(result.getUnknownFields()).containsKey("meta");
    assertThat(result.getUnknownFields().get("meta")).isNull();

    Object written = adapter.toJsonValue(result);
    assertThat(written).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> writtenMap = (Map<String, Object>) written;
    assertThat(writtenMap.keySet()).containsExactly("id", "meta").inOrder();
    assertThat(writtenMap.get("id")).isEqualTo(13);
    assertThat(writtenMap).containsKey("meta");
    assertThat(writtenMap.get("meta")).isNull();
  }
}
