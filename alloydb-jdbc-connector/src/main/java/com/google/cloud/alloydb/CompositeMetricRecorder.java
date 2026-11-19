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

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers every connector event to each of its delegates.
 *
 * <p>Delegates are isolated from one another: a delegate that throws is logged and skipped, so a
 * misbehaving export path can neither fail a connection nor stop the other paths from recording.
 */
class CompositeMetricRecorder implements MetricRecorder {

  private static final Logger logger = LoggerFactory.getLogger(CompositeMetricRecorder.class);

  private final ImmutableList<MetricRecorder> delegates;
  private final boolean enabled;

  CompositeMetricRecorder(List<MetricRecorder> delegates) {
    this.delegates = ImmutableList.copyOf(delegates);
    boolean anyEnabled = false;
    for (MetricRecorder delegate : this.delegates) {
      anyEnabled |= delegate.isEnabled();
    }
    this.enabled = anyEnabled;
  }

  ImmutableList<MetricRecorder> getDelegates() {
    return delegates;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void shutdown() {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.shutdown();
      } catch (RuntimeException e) {
        logFailure("shutdown", e);
      }
    }
  }

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordDialCount(attrs);
      } catch (RuntimeException e) {
        logFailure("recordDialCount", e);
      }
    }
  }

  @Override
  public void recordDialLatency(double latencyMs) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordDialLatency(latencyMs);
      } catch (RuntimeException e) {
        logFailure("recordDialLatency", e);
      }
    }
  }

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordOpenConnection(attrs);
      } catch (RuntimeException e) {
        logFailure("recordOpenConnection", e);
      }
    }
  }

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordClosedConnection(attrs);
      } catch (RuntimeException e) {
        logFailure("recordClosedConnection", e);
      }
    }
  }

  @Override
  public void recordBytesRx(long count) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordBytesRx(count);
      } catch (RuntimeException e) {
        logFailure("recordBytesRx", e);
      }
    }
  }

  @Override
  public void recordBytesTx(long count) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordBytesTx(count);
      } catch (RuntimeException e) {
        logFailure("recordBytesTx", e);
      }
    }
  }

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {
    for (MetricRecorder delegate : delegates) {
      try {
        delegate.recordRefreshCount(attrs);
      } catch (RuntimeException e) {
        logFailure("recordRefreshCount", e);
      }
    }
  }

  private static void logFailure(String operation, RuntimeException e) {
    logger.debug(String.format("Metric recorder failed during %s.", operation), e);
  }
}
