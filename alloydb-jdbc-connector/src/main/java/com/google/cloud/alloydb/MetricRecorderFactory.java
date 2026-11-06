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
import io.opentelemetry.api.GlobalOpenTelemetry;
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
   * @param globalRecorder the recorder for the global OpenTelemetry provider, or null if none
   */
  static MetricRecorder newMetricRecorder(
      boolean builtInEnabled,
      GlobalMetricRecorder globalRecorder,
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
    if (globalRecorder != null) {
      recorders.add(globalRecorder.forInstance(instanceName));
    }
    return combine(recorders, globalRecorder != null);
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

  // The system property and environment variable that opt an application into OpenTelemetry SDK
  // autoconfiguration on first access to the global instance.
  static final String GLOBAL_AUTOCONFIGURE_PROPERTY = "otel.java.global-autoconfigure.enabled";
  static final String GLOBAL_AUTOCONFIGURE_ENV = "OTEL_JAVA_GLOBAL_AUTOCONFIGURE_ENABLED";

  /**
   * Creates the recorder for the global OpenTelemetry provider, or returns null when the
   * application has not installed one yet or its instruments cannot be created.
   */
  static GlobalMetricRecorder newGlobalMetricRecorder() {
    MeterProvider meterProvider;
    try {
      meterProvider = globalMeterProvider();
    } catch (Throwable t) {
      logger.warn(
          "Global OpenTelemetry metrics are disabled: the global MeterProvider could not be "
              + "obtained ({}). Connections are unaffected.",
          t.toString());
      logger.debug("Global OpenTelemetry metrics failed to initialize.", t);
      return null;
    }
    return newGlobalMetricRecorder(meterProvider);
  }

  /**
   * Creates the recorder for the given provider, or returns null when it is null or its instruments
   * cannot be created.
   */
  static GlobalMetricRecorder newGlobalMetricRecorder(MeterProvider meterProvider) {
    if (meterProvider == null) {
      return null;
    }
    try {
      return new GlobalMetricRecorder(meterProvider);
    } catch (Throwable t) {
      // As with the built-in exporter, a broken provider must never stop a connection.
      logger.warn(
          "Global OpenTelemetry metrics are disabled: instruments could not be created on the "
              + "global MeterProvider ({}). Connections are unaffected.",
          t.toString());
      logger.debug("Global OpenTelemetry metrics failed to initialize.", t);
      return null;
    }
  }

  /**
   * Returns the application's global {@link MeterProvider}, or null when the application has not
   * installed one.
   *
   * <p>{@link GlobalOpenTelemetry#get} installs a no-op instance when nothing is set yet, after
   * which the application's own {@code GlobalOpenTelemetry.set} call fails. So the global is read
   * only once the application has set it, or when the application opted into autoconfiguration,
   * which runs on first access by design.
   */
  static MeterProvider globalMeterProvider() {
    boolean isSet;
    try {
      isSet = GlobalOpenTelemetry.isSet();
    } catch (NoSuchMethodError e) {
      // Older opentelemetry-api versions lack isSet(). Fall back to the standard lookup.
      return GlobalOpenTelemetry.getMeterProvider();
    }
    if (isSet || isGlobalAutoconfigureEnabled()) {
      return GlobalOpenTelemetry.getMeterProvider();
    }
    return null;
  }

  private static boolean isGlobalAutoconfigureEnabled() {
    String value = System.getProperty(GLOBAL_AUTOCONFIGURE_PROPERTY);
    if (value == null) {
      value = System.getenv(GLOBAL_AUTOCONFIGURE_ENV);
    }
    return Boolean.parseBoolean(value);
  }

  private MetricRecorderFactory() {}
}
