/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb.toy;

import static com.google.common.truth.Truth.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Instant;
import org.junit.Test;

public class ConsoleTableExporterTest {

  @Test
  public void formatsConnectorScopeAndHidesFixedAttributes() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    provider
        .get(ConsoleTableExporter.CONNECTOR_SCOPE)
        .counterBuilder("alloydb.connector.dials")
        .build()
        .add(
            1_500,
            Attributes.of(
                AttributeKey.stringKey("alloydb.instance_id"), "my-instance",
                AttributeKey.stringKey("alloydb.project_id"), "my-project",
                AttributeKey.stringKey("status"), "success"));

    String text = ConsoleTableExporter.format(reader.collectAllMetrics(), Instant.EPOCH);

    assertThat(text).containsMatch("instance\\s+status\\s+total");
    assertThat(text).containsMatch("my-instance\\s+success\\s+1,500");
    assertThat(text).doesNotContain("my-project");
  }

  @Test
  public void skipsMetricsFromOtherScopes() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    provider.get("other-library").counterBuilder("not.the.connector").build().add(1);

    String text = ConsoleTableExporter.format(reader.collectAllMetrics(), Instant.EPOCH);

    assertThat(text).doesNotContain("not.the.connector");
  }
}
