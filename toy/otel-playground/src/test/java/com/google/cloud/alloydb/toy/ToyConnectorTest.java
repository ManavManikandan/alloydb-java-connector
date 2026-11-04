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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.Random;
import org.junit.Test;

public class ToyConnectorTest {

  private static final String INSTANCE = "projects/p/locations/l/clusters/c/instances/i";

  @Test
  public void userSuppliedOpenTelemetryReceivesConnectorMetrics() throws Exception {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build();
    ToyConnectorConfig config =
        new ToyConnectorConfig.Builder().withOpenTelemetry(sdk).withFailureRate(0).build();

    try (ToyConnector connector = new ToyConnector(config, new Random(1))) {
      ToyConnector.ToySocket first = connector.connect(INSTANCE);
      ToyConnector.ToySocket second = connector.connect(INSTANCE);
      first.query(10, 100);
      first.close();
      second.close();
    }

    Collection<MetricData> metrics = reader.collectAllMetrics();
    assertThat(metricNames(metrics))
        .containsAtLeast(
            ConnectorMetrics.DIAL_COUNT,
            ConnectorMetrics.DIAL_LATENCY,
            ConnectorMetrics.OPEN_CONNECTIONS,
            ConnectorMetrics.BYTES_SENT,
            ConnectorMetrics.BYTES_RECEIVED,
            ConnectorMetrics.REFRESH_COUNT);

    MetricData dials = find(metrics, ConnectorMetrics.DIAL_COUNT);
    assertThat(dials.getInstrumentationScopeInfo().getName())
        .isEqualTo(ConnectorMetrics.METER_NAME);
    // One miss (first dial) and one hit (second dial).
    assertThat(dials.getLongSumData().getPoints()).hasSize(2);
    Attributes hit =
        dials.getLongSumData().getPoints().stream()
            .filter(p -> Boolean.TRUE.equals(p.getAttributes().get(ConnectorMetrics.IS_CACHE_HIT)))
            .findFirst()
            .get()
            .getAttributes();
    assertThat(hit.get(ConnectorMetrics.STATUS)).isEqualTo("success");
    assertThat(hit.get(ConnectorMetrics.CONNECTOR_TYPE)).isEqualTo("java");

    long open =
        find(metrics, ConnectorMetrics.OPEN_CONNECTIONS).getLongSumData().getPoints().stream()
            .mapToLong(p -> p.getValue())
            .sum();
    assertThat(open).isEqualTo(0);
  }

  @Test
  public void noOpenTelemetryFallsBackToGlobalNoop() throws Exception {
    ToyConnectorConfig config = new ToyConnectorConfig.Builder().withFailureRate(0).build();
    try (ToyConnector connector = new ToyConnector(config, new Random(1))) {
      connector.connect(INSTANCE).close();
    }
  }

  private static java.util.List<String> metricNames(Collection<MetricData> metrics) {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (MetricData m : metrics) {
      names.add(m.getName());
    }
    return names;
  }

  private static MetricData find(Collection<MetricData> metrics, String name) {
    for (MetricData m : metrics) {
      if (m.getName().equals(name)) {
        return m;
      }
    }
    throw new AssertionError("missing metric " + name);
  }
}
