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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * The library side of the proposal: instruments built from an {@link OpenTelemetry} that the
 * application hands in. This class only touches the OpenTelemetry API, never the SDK, so the
 * application owns exporters, readers, views and resources.
 *
 * <p>Meter, metric and attribute names mirror {@code CloudMonitoringMetricRecorder} and {@code
 * TelemetryAttributes} in the real connector, so what you see here is what the connector would
 * emit.
 *
 * <p>Instrument types used here:
 *
 * <ul>
 *   <li><b>Counter</b>: a total that only goes up (dials, bytes, refreshes).
 *   <li><b>UpDownCounter</b>: a level that goes up and down (open connections).
 *   <li><b>Histogram</b>: a distribution of values (dial latency), exported as count, sum, min, max
 *       and bucket counts.
 * </ul>
 *
 * <p>Every measurement carries <b>attributes</b> (key/value labels). Each unique combination of
 * attribute values becomes its own row in the exported data.
 */
final class ConnectorMetrics {

  // The meter name groups these instruments; exporters show it as the instrumentation scope.
  static final String METER_NAME = "alloydb.googleapis.com/client/connector";

  // Metric names.
  static final String DIAL_COUNT = "dial_count";
  static final String DIAL_LATENCY = "dial_latencies";
  static final String OPEN_CONNECTIONS = "open_connections";
  static final String BYTES_SENT = "bytes_sent_count";
  static final String BYTES_RECEIVED = "bytes_received_count";
  static final String REFRESH_COUNT = "refresh_count";

  // Attribute keys.
  static final AttributeKey<String> CONNECTOR_TYPE = AttributeKey.stringKey("connector_type");
  static final AttributeKey<String> AUTH_TYPE = AttributeKey.stringKey("auth_type");
  static final AttributeKey<Boolean> IS_CACHE_HIT = AttributeKey.booleanKey("is_cache_hit");
  static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");
  static final AttributeKey<String> REFRESH_TYPE = AttributeKey.stringKey("refresh_type");
  // Not in the connector today: there the instance lives on the monitored resource. Once the
  // application owns the Resource, the instance has to travel as a metric attribute instead.
  static final AttributeKey<String> INSTANCE = AttributeKey.stringKey("alloydb.instance");

  private static final String JAVA = "java";

  private final LongCounter dialCount;
  private final DoubleHistogram dialLatency;
  private final LongUpDownCounter openConnections;
  private final LongCounter bytesTx;
  private final LongCounter bytesRx;
  private final LongCounter refreshCount;

  /**
   * Creates all six instruments once, up front. Recording on an existing instrument is cheap;
   * creating one is not, so this never happens per dial.
   */
  ConnectorMetrics(OpenTelemetry openTelemetry) {
    // Get a Meter from the application's provider. With a no-op OpenTelemetry, every
    // instrument below is a no-op too and recording costs almost nothing.
    Meter meter =
        openTelemetry
            .getMeterProvider()
            .meterBuilder(METER_NAME)
            .setInstrumentationVersion("toy")
            .build();
    this.dialCount =
        meter.counterBuilder(DIAL_COUNT).setDescription("Dial attempts, by outcome").build();
    this.dialLatency =
        meter
            .histogramBuilder(DIAL_LATENCY)
            .setDescription("Time to establish a connection, in milliseconds")
            .build();
    this.openConnections =
        meter.upDownCounterBuilder(OPEN_CONNECTIONS).setDescription("Open connections").build();
    this.bytesTx = meter.counterBuilder(BYTES_SENT).setDescription("Bytes sent").build();
    this.bytesRx = meter.counterBuilder(BYTES_RECEIVED).setDescription("Bytes received").build();
    this.refreshCount =
        meter.counterBuilder(REFRESH_COUNT).setDescription("Certificate refreshes").build();
  }

  /** Counts one dial attempt, labeled by auth type, cache hit or miss, and outcome. */
  void recordDial(String instance, boolean iam, boolean cacheHit, String status) {
    dialCount.add(
        1,
        Attributes.builder()
            .put(CONNECTOR_TYPE, JAVA)
            .put(AUTH_TYPE, authType(iam))
            .put(IS_CACHE_HIT, cacheHit)
            .put(STATUS, status)
            .put(INSTANCE, instance)
            .build());
  }

  /** Records how long a successful dial took, in milliseconds. */
  void recordDialLatency(String instance, double latencyMs) {
    dialLatency.record(latencyMs, Attributes.of(CONNECTOR_TYPE, JAVA, INSTANCE, instance));
  }

  /** A connection opened: open_connections goes up by one. */
  void recordOpen(String instance, boolean iam) {
    openConnections.add(1, connectionAttributes(instance, iam));
  }

  /**
   * A connection closed: open_connections goes down by one. The attributes must match {@link
   * #recordOpen} exactly, or the +1 and -1 land on different rows and never cancel out.
   */
  void recordClose(String instance, boolean iam) {
    openConnections.add(-1, connectionAttributes(instance, iam));
  }

  /** Adds bytes the application sent to the instance. */
  void recordBytesTx(String instance, long n) {
    bytesTx.add(n, Attributes.of(CONNECTOR_TYPE, JAVA, INSTANCE, instance));
  }

  /** Adds bytes the application received from the instance. */
  void recordBytesRx(String instance, long n) {
    bytesRx.add(n, Attributes.of(CONNECTOR_TYPE, JAVA, INSTANCE, instance));
  }

  /** Counts one connection-info refresh, labeled by outcome and refresh type. */
  void recordRefresh(String instance, String status, String refreshType) {
    refreshCount.add(
        1,
        Attributes.of(
            CONNECTOR_TYPE, JAVA, STATUS, status, REFRESH_TYPE, refreshType, INSTANCE, instance));
  }

  // Shared by recordOpen and recordClose so both always use the same attributes.
  private static Attributes connectionAttributes(String instance, boolean iam) {
    return Attributes.of(CONNECTOR_TYPE, JAVA, AUTH_TYPE, authType(iam), INSTANCE, instance);
  }

  private static String authType(boolean iam) {
    return iam ? "iam" : "built_in";
  }
}
