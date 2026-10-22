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
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating MetricRecorder instances. */
class MetricRecorderFactory {

  private static final Logger logger = LoggerFactory.getLogger(MetricRecorderFactory.class);

  /**
   * Creates the recorder for one AlloyDB instance, fanning out to every configured export path.
   *
   * @param builtInEnabled whether to export to Cloud Monitoring
   * @param clientRecorder the recorder for the application's own provider, or null if none
   */
  static MetricRecorder newMetricRecorder(
      boolean builtInEnabled,
      ClientMetricRecorder clientRecorder,
      InstanceName instanceName,
      String clientUid) {
    List<MetricRecorder> recorders = new ArrayList<>(2);
    MetricRecorder builtIn =
        newBuiltInMetricRecorder(
            builtInEnabled,
            instanceName.getProject(),
            instanceName.getLocation(),
            instanceName.getCluster(),
            instanceName.getInstance(),
            clientUid);
    if (builtIn.isEnabled()) {
      recorders.add(builtIn);
    }
    if (clientRecorder != null) {
      recorders.add(clientRecorder.forInstance(instanceName));
    }
    return combine(recorders, clientRecorder != null);
  }

  /**
   * Combines recorders into one. With no recorders this is a no-op recorder, and a lone recorder is
   * returned as is unless {@code isolate} is set.
   *
   * @param isolate whether to always wrap in a {@link CompositeMetricRecorder}, so that a recorder
   *     backed by code outside the connector can never throw into a connection
   */
  static MetricRecorder combine(List<MetricRecorder> recorders, boolean isolate) {
    if (recorders.isEmpty()) {
      return new NullMetricRecorder();
    }
    if (recorders.size() == 1 && !isolate) {
      return recorders.get(0);
    }
    return new CompositeMetricRecorder(recorders);
  }

  static MetricRecorder newBuiltInMetricRecorder(
      boolean enabled,
      String projectId,
      String location,
      String cluster,
      String instance,
      String clientUid) {
    if (!enabled) {
      logger.debug("Disabling built-in metrics");
      return new NullMetricRecorder();
    }
    try {
      return new CloudMonitoringMetricRecorder(projectId, location, cluster, instance, clientUid);
    } catch (Throwable t) {
      // Telemetry must never stop a connection from being established. Catch Throwable rather than
      // Exception so that a missing OpenTelemetry class (NoClassDefFoundError on a minimized or
      // shaded classpath) degrades to no metrics instead of failing the dial.
      // Log the summary at warn and keep the stack trace for debug: this is expected whenever the
      // caller's credentials lack monitoring.timeSeries.create, and it is not worth a stack trace
      // in every such application's logs.
      logger.warn(
          "Built-in metrics are disabled: the Cloud Monitoring exporter failed to initialize ({}). "
              + "Connections are unaffected.",
          t.toString());
      logger.debug("Built-in metrics exporter failed to initialize.", t);
      return new NullMetricRecorder();
    }
  }

  /**
   * Creates the recorder for an application-provided {@link MeterProvider}, or returns null when
   * none is configured or its instruments cannot be created.
   */
  static ClientMetricRecorder newClientMetricRecorder(MeterProvider meterProvider) {
    if (meterProvider == null) {
      return null;
    }
    try {
      return new ClientMetricRecorder(meterProvider);
    } catch (Throwable t) {
      // As with the built-in exporter, a broken provider must never stop a connection.
      logger.warn(
          "Client metrics are disabled: instruments could not be created on the configured "
              + "MeterProvider ({}). Connections are unaffected.",
          t.toString());
      logger.debug("Client metrics failed to initialize.", t);
      return null;
    }
  }

  private MetricRecorderFactory() {}
}
