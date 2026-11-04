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
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.Arrays;

/**
 * Crude timing of the hot recording path: the per-dial cost of the metric calls themselves, with a
 * no-op OpenTelemetry versus a real SDK. Not JMH, so treat the numbers as an order of magnitude.
 */
final class Bench {

  // Warm-up iterations let the JIT compile the loop before timing starts.
  private static final int WARMUP = 2_000_000;
  private static final int ITERATIONS = 5_000_000;

  private Bench() {}

  /** Runs the three comparisons and prints one line each. */
  static void run(AppTelemetry.Options opts) {
    // Baseline: what a user who never configured OpenTelemetry pays.
    time("no-op OpenTelemetry", OpenTelemetry.noop());

    AppTelemetry.Options sdkOpts = new AppTelemetry.Options();
    // A long interval keeps the exporter out of the measurement; only recording is timed.
    sdkOpts.exporters = Arrays.asList("logging");
    sdkOpts.exportIntervalSec = 3600;
    try (OpenTelemetrySdk sdk = AppTelemetry.create(sdkOpts)) {
      // A real SDK, first with one instance, then with 50 to see what more rows cost.
      time("SDK, 1 instance", sdk, 1);
      time("SDK, 50 instances", sdk, 50);
    }
  }

  private static void time(String label, OpenTelemetry otel) {
    time(label, otel, 1);
  }

  /** Warms up, then times {@link #ITERATIONS} simulated dials and prints the cost per dial. */
  private static void time(String label, OpenTelemetry otel, int instances) {
    ConnectorMetrics m = new ConnectorMetrics(otel);
    String[] names = new String[instances];
    for (int i = 0; i < instances; i++) {
      names[i] = "instance-" + i;
    }
    loop(m, names, WARMUP);
    long start = System.nanoTime();
    loop(m, names, ITERATIONS);
    double nsPerDial = (System.nanoTime() - start) / (double) ITERATIONS;
    System.out.printf("%-22s %7.1f ns per simulated dial (count + latency + open + close)%n",
        label, nsPerDial);
  }

  /** One "dial" is the four calls a successful connection makes: count, latency, open, close. */
  private static void loop(ConnectorMetrics m, String[] names, int n) {
    for (int i = 0; i < n; i++) {
      String inst = names[i % names.length];
      m.recordDial(inst, false, true, "success");
      m.recordDialLatency(inst, i % 100);
      m.recordOpen(inst, false);
      m.recordClose(inst, false);
    }
  }
}
