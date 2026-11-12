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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the documented client metric contract in docs/telemetry.md. A failure here means a change to
 * a stable, user-facing contract.
 */
public class GlobalMetricRecorderTest {

  private static final InstanceName INSTANCE_A =
      InstanceName.of("project-a", "us-central1", "cluster-a", "instance-a");
  private static final InstanceName INSTANCE_B =
      InstanceName.of("project-a", "europe-west1", "cluster-b", "instance-b");

  private static final Resource APP_RESOURCE =
      Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "my-app"));

  private InMemoryMetricReader reader;
  private GlobalMetricRecorder globalRecorder;

  @Before
  public void setUp() {
    reader = InMemoryMetricReader.create();
    globalRecorder =
        new GlobalMetricRecorder(
            SdkMeterProvider.builder()
                .setResource(APP_RESOURCE)
                .registerMetricReader(reader)
                .build());
  }

  private Map<String, MetricData> collect() {
    Map<String, MetricData> byName = new HashMap<>();
    for (MetricData metric : reader.collectAllMetrics()) {
      byName.put(metric.getName(), metric);
    }
    return byName;
  }

  private static Attributes targetPlus(InstanceName instance, Attributes extra) {
    return Attributes.builder()
        .put("alloydb.project_id", instance.getProject())
        .put("alloydb.location", instance.getLocation())
        .put("alloydb.cluster_id", instance.getCluster())
        .put("alloydb.instance_id", instance.getInstance())
        .put("connector_type", "java")
        .putAll(extra)
        .build();
  }

  private static PointData onlyPoint(MetricData metric) {
    Collection<? extends PointData> points = metric.getData().getPoints();
    assertThat(points).hasSize(1);
    return points.iterator().next();
  }

  private static long onlyLongValue(MetricData metric) {
    return ((LongPointData) onlyPoint(metric)).getValue();
  }

  private static void recordEverything(MetricRecorder recorder) {
    recorder.recordDialCount(
        TelemetryAttributes.forDial(true, false, TelemetryAttributes.DIAL_SUCCESS));
    recorder.recordDialLatency(42.0);
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(true));
    recorder.recordBytesTx(100);
    recorder.recordBytesRx(250);
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_AHEAD_SUCCEEDED);
  }

  @Test
  public void publishesTheDocumentedMetricNamesTypesUnitsAndDescriptions() {
    recordEverything(globalRecorder.forInstance(INSTANCE_A));

    Map<String, MetricData> metrics = collect();

    assertThat(metrics.keySet())
        .containsExactly(
            "alloydb.connector.dials",
            "alloydb.connector.dial.duration",
            "alloydb.connector.connections.open",
            "alloydb.connector.bytes.sent",
            "alloydb.connector.bytes.received",
            "alloydb.connector.refreshes");

    assertMetric(
        metrics.get("alloydb.connector.dials"),
        MetricDataType.LONG_SUM,
        "{dial}",
        "Number of attempts to dial an AlloyDB instance.");
    assertThat(metrics.get("alloydb.connector.dials").getLongSumData().isMonotonic()).isTrue();

    assertMetric(
        metrics.get("alloydb.connector.dial.duration"),
        MetricDataType.HISTOGRAM,
        "ms",
        "Time taken to successfully dial an AlloyDB instance.");

    assertMetric(
        metrics.get("alloydb.connector.connections.open"),
        MetricDataType.LONG_SUM,
        "{connection}",
        "Number of connections currently open to an AlloyDB instance.");
    assertThat(metrics.get("alloydb.connector.connections.open").getLongSumData().isMonotonic())
        .isFalse();

    assertMetric(
        metrics.get("alloydb.connector.bytes.sent"),
        MetricDataType.LONG_SUM,
        "By",
        "Number of application bytes sent to an AlloyDB instance.");
    assertMetric(
        metrics.get("alloydb.connector.bytes.received"),
        MetricDataType.LONG_SUM,
        "By",
        "Number of application bytes received from an AlloyDB instance.");
    assertMetric(
        metrics.get("alloydb.connector.refreshes"),
        MetricDataType.LONG_SUM,
        "{refresh}",
        "Number of refreshes of an AlloyDB instance's connection info.");
  }

  private static void assertMetric(
      MetricData metric, MetricDataType type, String unit, String description) {
    assertThat(metric.getType()).isEqualTo(type);
    assertThat(metric.getUnit()).isEqualTo(unit);
    assertThat(metric.getDescription()).isEqualTo(description);
    assertThat(metric.getInstrumentationScopeInfo().getName())
        .isEqualTo("com.google.cloud.alloydb");
    assertThat(metric.getInstrumentationScopeInfo().getVersion()).isEqualTo(Version.VERSION);
  }

  @Test
  public void recordsTheExpectedValues() {
    MetricRecorder recorder = globalRecorder.forInstance(INSTANCE_A);
    recordEverything(recorder);
    recorder.recordBytesTx(1);
    recorder.recordDialLatency(8.0);

    Map<String, MetricData> metrics = collect();

    assertThat(onlyLongValue(metrics.get("alloydb.connector.dials"))).isEqualTo(1);
    HistogramPointData latency =
        (HistogramPointData) onlyPoint(metrics.get("alloydb.connector.dial.duration"));
    assertThat(latency.getCount()).isEqualTo(2);
    assertThat(latency.getSum()).isEqualTo(50.0);
    assertThat(onlyLongValue(metrics.get("alloydb.connector.connections.open"))).isEqualTo(1);
    assertThat(onlyLongValue(metrics.get("alloydb.connector.bytes.sent"))).isEqualTo(101);
    assertThat(onlyLongValue(metrics.get("alloydb.connector.bytes.received"))).isEqualTo(250);
    assertThat(onlyLongValue(metrics.get("alloydb.connector.refreshes"))).isEqualTo(1);
  }

  @Test
  public void attachesTargetIdentityAndEventAttributes() {
    MetricRecorder recorder = globalRecorder.forInstance(INSTANCE_A);
    recorder.recordDialCount(
        TelemetryAttributes.forDial(true, true, TelemetryAttributes.DIAL_TLS_ERROR));
    recorder.recordDialLatency(1);
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(false));
    recorder.recordBytesTx(1);
    recorder.recordBytesRx(1);
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_LAZY_FAILED);

    Map<String, MetricData> metrics = collect();

    assertThat(onlyPoint(metrics.get("alloydb.connector.dials")).getAttributes())
        .isEqualTo(
            targetPlus(
                INSTANCE_A,
                Attributes.builder()
                    .put("auth_type", "iam")
                    .put("is_cache_hit", true)
                    .put("status", "tls_error")
                    .build()));
    assertThat(onlyPoint(metrics.get("alloydb.connector.dial.duration")).getAttributes())
        .isEqualTo(targetPlus(INSTANCE_A, Attributes.empty()));
    assertThat(onlyPoint(metrics.get("alloydb.connector.connections.open")).getAttributes())
        .isEqualTo(
            targetPlus(INSTANCE_A, Attributes.of(AttributeKey.stringKey("auth_type"), "built_in")));
    assertThat(onlyPoint(metrics.get("alloydb.connector.bytes.sent")).getAttributes())
        .isEqualTo(targetPlus(INSTANCE_A, Attributes.empty()));
    assertThat(onlyPoint(metrics.get("alloydb.connector.bytes.received")).getAttributes())
        .isEqualTo(targetPlus(INSTANCE_A, Attributes.empty()));
    assertThat(onlyPoint(metrics.get("alloydb.connector.refreshes")).getAttributes())
        .isEqualTo(
            targetPlus(
                INSTANCE_A,
                Attributes.builder().put("status", "failure").put("refresh_type", "lazy").build()));
  }

  @Test
  public void distinguishesMultipleTargetInstances() {
    MetricRecorder a = globalRecorder.forInstance(INSTANCE_A);
    MetricRecorder b = globalRecorder.forInstance(INSTANCE_B);
    a.recordOpenConnection(TelemetryAttributes.forConnection(false));
    a.recordOpenConnection(TelemetryAttributes.forConnection(false));
    b.recordOpenConnection(TelemetryAttributes.forConnection(false));
    a.recordBytesRx(10);
    b.recordBytesRx(3);

    Map<String, MetricData> metrics = collect();

    Map<String, Long> openByInstance = new HashMap<>();
    for (LongPointData point :
        metrics.get("alloydb.connector.connections.open").getLongSumData().getPoints()) {
      openByInstance.put(
          point.getAttributes().get(GlobalMetricRecorder.INSTANCE_ID_KEY), point.getValue());
    }
    assertThat(openByInstance).containsExactly("instance-a", 1L + 1L, "instance-b", 1L);

    Map<String, String> locationByInstance = new HashMap<>();
    Map<String, Long> bytesByInstance = new HashMap<>();
    for (LongPointData point :
        metrics.get("alloydb.connector.bytes.received").getLongSumData().getPoints()) {
      String instance = point.getAttributes().get(GlobalMetricRecorder.INSTANCE_ID_KEY);
      bytesByInstance.put(instance, point.getValue());
      locationByInstance.put(
          instance, point.getAttributes().get(GlobalMetricRecorder.LOCATION_KEY));
    }
    assertThat(bytesByInstance).containsExactly("instance-a", 10L, "instance-b", 3L);
    assertThat(locationByInstance)
        .containsExactly("instance-a", "us-central1", "instance-b", "europe-west1");
  }

  @Test
  public void leavesTheApplicationResourceUntouched() {
    recordEverything(globalRecorder.forInstance(INSTANCE_A));
    recordEverything(globalRecorder.forInstance(INSTANCE_B));

    for (MetricData metric : reader.collectAllMetrics()) {
      assertThat(metric.getResource()).isEqualTo(APP_RESOURCE);
    }
  }

  @Test
  public void attachesOnlyDocumentedAttributeKeys() {
    MetricRecorder recorder = globalRecorder.forInstance(INSTANCE_A);
    recordEverything(recorder);
    recorder.recordDialCount(
        TelemetryAttributes.forDial(false, true, TelemetryAttributes.DIAL_MDX_ERROR));
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_LAZY_FAILED);

    Set<String> documented = new HashSet<>();
    documented.add("alloydb.project_id");
    documented.add("alloydb.location");
    documented.add("alloydb.cluster_id");
    documented.add("alloydb.instance_id");
    documented.add("connector_type");
    documented.add("auth_type");
    documented.add("is_cache_hit");
    documented.add("status");
    documented.add("refresh_type");

    Set<String> seen = new HashSet<>();
    for (MetricData metric : reader.collectAllMetrics()) {
      for (PointData point : metric.getData().getPoints()) {
        point.getAttributes().forEach((key, value) -> seen.add(key.getKey()));
      }
    }
    assertThat(seen).containsExactlyElementsIn(documented);
  }

  @Test
  public void instanceRecorderIsEnabledAndNeverShutsDownTheProvider() {
    MetricRecorder recorder = globalRecorder.forInstance(INSTANCE_A);

    recorder.shutdown();
    recorder.recordBytesTx(5);

    assertThat(recorder.isEnabled()).isTrue();
    assertThat(onlyLongValue(collect().get("alloydb.connector.bytes.sent"))).isEqualTo(5);
  }
}
