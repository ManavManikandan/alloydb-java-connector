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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Sketch of the public surface the project would add to {@code ConnectorConfig.Builder}: a single
 * {@code withOpenTelemetry} hook. If the caller passes nothing, the connector falls back to {@link
 * GlobalOpenTelemetry}, which is a no-op unless the application (or the Java agent) installed one.
 *
 * <p>Like the real {@code ConnectorConfig}, this is an immutable value built with a {@link
 * Builder}: set options with {@code with*} methods, then call {@link Builder#build()}.
 */
final class ToyConnectorConfig {

  private final OpenTelemetry openTelemetry;
  private final boolean iamAuthn;
  private final double failureRate;
  private final long refreshIntervalMs;

  /** Copies the builder's settings, filling in the GlobalOpenTelemetry fallback if needed. */
  private ToyConnectorConfig(Builder b) {
    this.openTelemetry = b.openTelemetry != null ? b.openTelemetry : GlobalOpenTelemetry.get();
    this.iamAuthn = b.iamAuthn;
    this.failureRate = b.failureRate;
    this.refreshIntervalMs = b.refreshIntervalMs;
  }

  /** The OpenTelemetry the connector creates its instruments from. */
  OpenTelemetry getOpenTelemetry() {
    return openTelemetry;
  }

  /** Whether dials are tagged auth_type=iam instead of built_in. */
  boolean isIamAuthn() {
    return iamAuthn;
  }

  /** Chance from 0 to 1 that a dial or refresh fails. */
  double getFailureRate() {
    return failureRate;
  }

  /** How often refresh-ahead re-fetches each instance's connection info. */
  long getRefreshIntervalMs() {
    return refreshIntervalMs;
  }

  /** Collects settings; the field initializers are the defaults. */
  static final class Builder {
    private OpenTelemetry openTelemetry;
    private boolean iamAuthn;
    private double failureRate = 0.05;
    private long refreshIntervalMs = 15_000;

    /** The proposed public hook: bring your own OpenTelemetry (and therefore your own exporter). */
    Builder withOpenTelemetry(OpenTelemetry openTelemetry) {
      this.openTelemetry = openTelemetry;
      return this;
    }

    /** Pretend connections use IAM database authentication. */
    Builder withIamAuthn(boolean iamAuthn) {
      this.iamAuthn = iamAuthn;
      return this;
    }

    /** Fraction of dials and refreshes that fail on purpose. */
    Builder withFailureRate(double failureRate) {
      this.failureRate = failureRate;
      return this;
    }

    /** Refresh-ahead interval in milliseconds. */
    Builder withRefreshIntervalMs(long refreshIntervalMs) {
      this.refreshIntervalMs = refreshIntervalMs;
      return this;
    }

    /** Produces the immutable config. */
    ToyConnectorConfig build() {
      return new ToyConnectorConfig(this);
    }
  }
}
