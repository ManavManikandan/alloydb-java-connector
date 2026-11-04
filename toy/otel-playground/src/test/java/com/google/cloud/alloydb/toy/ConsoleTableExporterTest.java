/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb.toy;

import static com.google.common.truth.Truth.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Instant;
import java.util.Collection;
import java.util.Random;
import org.junit.Test;

public class ConsoleTableExporterTest {

  private static final String INSTANCE =
      "projects/p/locations/l/clusters/c/instances/my-instance";

  @Test
  public void formatsToyConnectorMetricsAsTables() throws Exception {
    // Drive two successful dials through the toy connector and collect what it recorded.
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build();
    ToyConnectorConfig config =
        new ToyConnectorConfig.Builder().withOpenTelemetry(sdk).withFailureRate(0).build();
    try (ToyConnector connector = new ToyConnector(config, new Random(1))) {
      ToyConnector.ToySocket socket = connector.connect(INSTANCE);
      connector.connect(INSTANCE).close();
      socket.query(1_000, 2_500_000);
    }
    Collection<MetricData> metrics = reader.collectAllMetrics();

    String text = ConsoleTableExporter.format(metrics, Instant.EPOCH);

    // Every metric gets a title line with its description.
    assertThat(text).contains("dial_count - Dial attempts, by outcome");
    assertThat(text).contains("dial_latencies - Time to establish a connection");
    // The instance is shortened, and connector_type is hidden.
    assertThat(text).contains("my-instance");
    assertThat(text).doesNotContain("projects/p/");
    assertThat(text).doesNotContain("connector_type");
    // Counters show a total, the UpDownCounter a current level, and the histogram summary stats.
    assertThat(text).containsMatch("instance\\s+auth_type\\s+is_cache_hit\\s+status\\s+total");
    assertThat(text).containsMatch("my-instance\\s+built_in\\s+true\\s+success\\s+1");
    // One socket is still open, so open_connections is 1.
    assertThat(text)
        .containsMatch("instance\\s+auth_type\\s+current\n\\s+my-instance\\s+built_in\\s+1\n");
    assertThat(text).containsMatch("instance\\s+count\\s+avg\\s+min\\s+max");
    // Large numbers get thousands separators.
    assertThat(text).contains("2,500,000");
  }

  @Test
  public void skipsMetricsFromOtherMeters() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    provider.get(ConnectorMetrics.METER_NAME).counterBuilder("dial_count").build().add(1);
    provider.get("other-library").counterBuilder("not.the.connector").build().add(1);

    String text = ConsoleTableExporter.format(reader.collectAllMetrics(), Instant.EPOCH);

    assertThat(text).contains("dial_count");
    assertThat(text).doesNotContain("not.the.connector");
  }

  @Test
  public void shortInstanceKeepsLastPathSegment() {
    assertThat(ConsoleTableExporter.shortInstance(INSTANCE)).isEqualTo("my-instance");
    assertThat(ConsoleTableExporter.shortInstance("plain")).isEqualTo("plain");
  }
}
