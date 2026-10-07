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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;

/** Java-callable surface check for {@link JsonValueIterator}. */
public final class JsonValueIteratorJavaTest {
  private final JsonAdapter<String> adapter = new Moshi.Builder().build().adapter(String.class);

  @Test
  public void iterateOwnedSource() throws IOException {
    BufferedSource source = new Buffer().writeUtf8("\"one\" \"two\"");
    try (JsonValueIterator<String> values = adapter.jsonValues(source)) {
      assertTrue(values.hasNext());
      assertEquals("one", values.next());
      assertEquals("two", values.next());
      assertFalse(values.hasNext());
    }
  }

  @Test
  public void truncatedValueHasIndexAndCause() throws IOException {
    BufferedSource source = new Buffer().writeUtf8("\"ok\" \"broken");
    try (JsonValueIterator<String> values = adapter.jsonValues(source)) {
      assertEquals("ok", values.next());
      JsonEncodingException error = assertThrows(JsonEncodingException.class, values::next);
      assertTrue(error.getMessage(), error.getMessage().contains("index 1"));
      assertTrue(error.getCause() instanceof IOException);
    }
  }

  @Test
  public void whitespaceEndsNormally() throws IOException {
    BufferedSource source = new Buffer().writeUtf8("   ");
    try (JsonValueIterator<String> values = adapter.jsonValues(source)) {
      assertFalse(values.hasNext());
    }
  }
}
