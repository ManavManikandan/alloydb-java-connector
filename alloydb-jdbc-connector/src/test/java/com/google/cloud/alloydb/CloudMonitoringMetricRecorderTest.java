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

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Regression tests that pin the metrics exported to Cloud Monitoring. */
public class CloudMonitoringMetricRecorderTest {

  private static final Attributes CONNECTOR_TYPE_ONLY =
      Attributes.of(AttributeKey.stringKey("connector_type"), "java");

  private InMemoryMetricReader reader;
  private CloudMonitoringMetricRecorder recorder;

  @Before
  public void setUp() {
    reader = InMemoryMetricReader.create();
    recorder =
        new CloudMonitoringMetricRecorder(
            SdkMeterProvider.builder().registerMetricReader(reader).build());
  }

  private Map<String, MetricData> collect() {
    Map<String, MetricData> byName = new HashMap<>();
    for (MetricData metric : reader.collectAllMetrics()) {
      byName.put(metric.getName(), metric);
    }
    return byName;
  }

  private static Attributes onlyPointAttributes(MetricData metric) {
    Collection<? extends PointData> points = metric.getData().getPoints();
    assertThat(points).hasSize(1);
    return points.iterator().next().getAttributes();
  }

  @Test
  public void recordsEveryMetricUnderItsBuiltInName() {
    recorder.recordDialCount(
        TelemetryAttributes.forDial(true, false, TelemetryAttributes.DIAL_SUCCESS));
    recorder.recordDialLatency(12.5);
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(true));
    recorder.recordBytesRx(10);
    recorder.recordBytesTx(20);
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_LAZY_FAILED);

    Map<String, MetricData> metrics = collect();

    assertThat(metrics.keySet())
        .containsExactly(
            "dial_count",
            "dial_latencies",
            "open_connections",
            "bytes_received_count",
            "bytes_sent_count",
            "refresh_count");
    for (MetricData metric : metrics.values()) {
      assertThat(metric.getInstrumentationScopeInfo().getName())
          .isEqualTo(CloudMonitoringMetricRecorder.METER_NAME);
    }
    assertThat(metrics.get("dial_count").getType()).isEqualTo(MetricDataType.LONG_SUM);
    assertThat(metrics.get("dial_latencies").getType()).isEqualTo(MetricDataType.HISTOGRAM);
    assertThat(metrics.get("open_connections").getType()).isEqualTo(MetricDataType.LONG_SUM);
    assertThat(metrics.get("open_connections").getLongSumData().isMonotonic()).isFalse();
    assertThat(
            metrics
                .get("bytes_received_count")
                .getLongSumData()
                .getPoints()
                .iterator()
                .next()
                .getValue())
        .isEqualTo(10);
    assertThat(
            metrics
                .get("bytes_sent_count")
                .getLongSumData()
                .getPoints()
                .iterator()
                .next()
                .getValue())
        .isEqualTo(20);
  }

  @Test
  public void attachesOnlyTheBuiltInAttributes() {
    recorder.recordDialCount(
        TelemetryAttributes.forDial(true, true, TelemetryAttributes.DIAL_TCP_ERROR));
    recorder.recordDialLatency(1);
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(false));
    recorder.recordBytesRx(1);
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_AHEAD_SUCCEEDED);

    Map<String, MetricData> metrics = collect();

    assertThat(onlyPointAttributes(metrics.get("dial_count")))
        .isEqualTo(
            Attributes.builder()
                .put("connector_type", "java")
                .put("auth_type", "iam")
                .put("is_cache_hit", true)
                .put("status", "tcp_error")
                .build());
    assertThat(onlyPointAttributes(metrics.get("dial_latencies"))).isEqualTo(CONNECTOR_TYPE_ONLY);
    assertThat(onlyPointAttributes(metrics.get("open_connections")))
        .isEqualTo(
            Attributes.builder()
                .put("connector_type", "java")
                .put("auth_type", "built_in")
                .build());
    assertThat(onlyPointAttributes(metrics.get("bytes_received_count")))
        .isEqualTo(CONNECTOR_TYPE_ONLY);
    assertThat(onlyPointAttributes(metrics.get("refresh_count")))
        .isEqualTo(
            Attributes.builder()
                .put("connector_type", "java")
                .put("status", "success")
                .put("refresh_type", "refresh_ahead")
                .build());
  }

  @Test
  public void closedConnectionDecrementsOpenConnections() {
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(false));
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(false));
    recorder.recordClosedConnection(TelemetryAttributes.forConnection(false));

    MetricData openConnections = collect().get("open_connections");

    assertThat(openConnections.getLongSumData().getPoints().iterator().next().getValue())
        .isEqualTo(1);
  }

  @Test
  public void isEnabledAndShutsDownItsOwnProvider() {
    assertThat(recorder.isEnabled()).isTrue();

    recorder.shutdown();
    recorder.recordBytesTx(5);

    // A shut down SDK provider stops collecting.
    assertThat(reader.collectAllMetrics()).isEmpty();
  }
}
