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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

/**
 * Records connector metrics through the application's global OpenTelemetry {@link MeterProvider}.
 *
 * <p>The global provider describes the application, so AlloyDB target identity is attached to each
 * measurement as attributes rather than to the provider's resource. One instance of this class
 * serves a whole connector; {@link #forInstance} returns the recorder for one target.
 *
 * <p>The connector never configures, replaces, shuts down, closes, or flushes the provider: its
 * lifecycle belongs to the application.
 */
final class GlobalMetricRecorder {

  static final String INSTRUMENTATION_SCOPE = "com.google.cloud.alloydb";

  // Metric names. These are part of the documented metric contract in docs/telemetry.md.
  static final String DIALS = "alloydb.connector.dials";
  static final String DIAL_DURATION = "alloydb.connector.dial.duration";
  static final String OPEN_CONNECTIONS = "alloydb.connector.connections.open";
  static final String BYTES_SENT = "alloydb.connector.bytes.sent";
  static final String BYTES_RECEIVED = "alloydb.connector.bytes.received";
  static final String REFRESHES = "alloydb.connector.refreshes";

  static final String DIALS_UNIT = "{dial}";
  static final String DIAL_DURATION_UNIT = "ms";
  static final String OPEN_CONNECTIONS_UNIT = "{connection}";
  static final String BYTES_UNIT = "By";
  static final String REFRESHES_UNIT = "{refresh}";

  static final String DIALS_DESCRIPTION = "Number of attempts to dial an AlloyDB instance.";
  static final String DIAL_DURATION_DESCRIPTION =
      "Time taken to successfully dial an AlloyDB instance.";
  static final String OPEN_CONNECTIONS_DESCRIPTION =
      "Number of connections currently open to an AlloyDB instance.";
  static final String BYTES_SENT_DESCRIPTION =
      "Number of application bytes sent to an AlloyDB instance.";
  static final String BYTES_RECEIVED_DESCRIPTION =
      "Number of application bytes received from an AlloyDB instance.";
  static final String REFRESHES_DESCRIPTION =
      "Number of refreshes of an AlloyDB instance's connection info.";

  // Target identity attribute keys. Namespaced so they cannot collide with the application's own
  // resource attributes, such as project or region.
  static final AttributeKey<String> PROJECT_ID_KEY = AttributeKey.stringKey("alloydb.project_id");
  static final AttributeKey<String> LOCATION_KEY = AttributeKey.stringKey("alloydb.location");
  static final AttributeKey<String> CLUSTER_ID_KEY = AttributeKey.stringKey("alloydb.cluster_id");
  static final AttributeKey<String> INSTANCE_ID_KEY = AttributeKey.stringKey("alloydb.instance_id");

  // Event attribute keys, shared with the built-in metrics.
  static final AttributeKey<String> CONNECTOR_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE);
  static final AttributeKey<String> AUTH_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.AUTH_TYPE);
  static final AttributeKey<Boolean> IS_CACHE_HIT_KEY =
      AttributeKey.booleanKey(TelemetryAttributes.IS_CACHE_HIT);
  static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey(TelemetryAttributes.STATUS);
  static final AttributeKey<String> REFRESH_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.REFRESH_TYPE);

  private final LongCounter dials;
  private final DoubleHistogram dialDuration;
  private final LongUpDownCounter openConnections;
  private final LongCounter bytesSent;
  private final LongCounter bytesReceived;
  private final LongCounter refreshes;

  GlobalMetricRecorder(MeterProvider meterProvider) {
    Meter meter =
        meterProvider
            .meterBuilder(INSTRUMENTATION_SCOPE)
            .setInstrumentationVersion(Version.VERSION)
            .build();
    this.dials =
        meter.counterBuilder(DIALS).setUnit(DIALS_UNIT).setDescription(DIALS_DESCRIPTION).build();
    this.dialDuration =
        meter
            .histogramBuilder(DIAL_DURATION)
            .setUnit(DIAL_DURATION_UNIT)
            .setDescription(DIAL_DURATION_DESCRIPTION)
            .build();
    this.openConnections =
        meter
            .upDownCounterBuilder(OPEN_CONNECTIONS)
            .setUnit(OPEN_CONNECTIONS_UNIT)
            .setDescription(OPEN_CONNECTIONS_DESCRIPTION)
            .build();
    this.bytesSent =
        meter
            .counterBuilder(BYTES_SENT)
            .setUnit(BYTES_UNIT)
            .setDescription(BYTES_SENT_DESCRIPTION)
            .build();
    this.bytesReceived =
        meter
            .counterBuilder(BYTES_RECEIVED)
            .setUnit(BYTES_UNIT)
            .setDescription(BYTES_RECEIVED_DESCRIPTION)
            .build();
    this.refreshes =
        meter
            .counterBuilder(REFRESHES)
            .setUnit(REFRESHES_UNIT)
            .setDescription(REFRESHES_DESCRIPTION)
            .build();
  }

  /** Returns the recorder that attributes every measurement to the given instance. */
  MetricRecorder forInstance(InstanceName instanceName) {
    return new InstanceRecorder(instanceName);
  }

  private final class InstanceRecorder implements MetricRecorder {

    // dial.duration and the byte counters carry only the target and connector_type, so that
    // attribute set is built once per instance instead of on every read and write.
    private final Attributes connectorTypeOnly;

    InstanceRecorder(InstanceName instanceName) {
      this.connectorTypeOnly =
          Attributes.builder()
              .put(PROJECT_ID_KEY, instanceName.getProject())
              .put(LOCATION_KEY, instanceName.getLocation())
              .put(CLUSTER_ID_KEY, instanceName.getCluster())
              .put(INSTANCE_ID_KEY, instanceName.getInstance())
              .put(CONNECTOR_TYPE_KEY, CloudMonitoringMetricRecorder.CONNECTOR_TYPE)
              .build();
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void shutdown() {}

    @Override
    public void recordDialCount(TelemetryAttributes attrs) {
      dials.add(
          1,
          connectorTypeOnly.toBuilder()
              .put(AUTH_TYPE_KEY, TelemetryAttributes.authTypeValue(attrs.isIamAuthn()))
              .put(IS_CACHE_HIT_KEY, attrs.isCacheHit())
              .put(STATUS_KEY, attrs.getDialStatus())
              .build());
    }

    @Override
    public void recordDialLatency(double latencyMs) {
      dialDuration.record(latencyMs, connectorTypeOnly);
    }

    @Override
    public void recordOpenConnection(TelemetryAttributes attrs) {
      openConnections.add(1, connectionAttributes(attrs));
    }

    @Override
    public void recordClosedConnection(TelemetryAttributes attrs) {
      openConnections.add(-1, connectionAttributes(attrs));
    }

    @Override
    public void recordBytesRx(long count) {
      bytesReceived.add(count, connectorTypeOnly);
    }

    @Override
    public void recordBytesTx(long count) {
      bytesSent.add(count, connectorTypeOnly);
    }

    @Override
    public void recordRefreshCount(TelemetryAttributes attrs) {
      refreshes.add(
          1,
          connectorTypeOnly.toBuilder()
              .put(STATUS_KEY, attrs.getRefreshStatus())
              .put(REFRESH_TYPE_KEY, attrs.getRefreshType())
              .build());
    }

    private Attributes connectionAttributes(TelemetryAttributes attrs) {
      return connectorTypeOnly.toBuilder()
          .put(AUTH_TYPE_KEY, TelemetryAttributes.authTypeValue(attrs.isIamAuthn()))
          .build();
    }
  }
}
