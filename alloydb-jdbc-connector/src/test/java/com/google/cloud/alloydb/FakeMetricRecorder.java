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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A MetricRecorder that remembers every call, and optionally throws from each of them. */
class FakeMetricRecorder implements MetricRecorder {

  private final boolean enabled;
  private final boolean throwing;
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  FakeMetricRecorder() {
    this(true, false);
  }

  FakeMetricRecorder(boolean enabled, boolean throwing) {
    this.enabled = enabled;
    this.throwing = throwing;
  }

  /** The calls made so far, each as the method name followed by its argument. */
  List<String> getCalls() {
    synchronized (calls) {
      return new ArrayList<>(calls);
    }
  }

  private void record(String call) {
    calls.add(call);
    if (throwing) {
      throw new IllegalStateException("recorder failure: " + call);
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void shutdown() {
    record("shutdown");
  }

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {
    record("dialCount " + attrs.getDialStatus());
  }

  @Override
  public void recordDialLatency(double latencyMs) {
    record("dialLatency");
  }

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {
    record("openConnection");
  }

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {
    record("closedConnection");
  }

  @Override
  public void recordBytesRx(long count) {
    record("bytesRx " + count);
  }

  @Override
  public void recordBytesTx(long count) {
    record("bytesTx " + count);
  }

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {
    record("refreshCount " + attrs.getRefreshStatus());
  }
}
