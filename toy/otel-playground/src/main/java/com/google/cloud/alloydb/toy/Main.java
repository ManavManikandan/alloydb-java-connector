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

import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point. See {@link AppTelemetry.Options#usage()} or run with --help.
 *
 * <p>What a run does, end to end:
 *
 * <ol>
 *   <li>Builds the application's OpenTelemetry SDK with the chosen exporters ({@link
 *       AppTelemetry}).
 *   <li>Creates a {@link ToyConnector} and hands it that SDK, the way a real application would
 *       hand its MeterProvider to the real connector.
 *   <li>Opens fake connections at a steady rate, sends fake queries on them, and closes them a few
 *       seconds later. Every step records metrics.
 *   <li>The exporters print or ship those metrics on their own schedule.
 * </ol>
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) throws Exception {
    AppTelemetry.Options opts = AppTelemetry.Options.parse(args);
    // Benchmark mode measures the cost of recording and exits; it simulates no traffic.
    if (opts.mode.equals("bench")) {
      Bench.run(opts);
      return;
    }
    // Application side: build the SDK, then configure the connector to use it. With --global the
    // connector gets nothing and falls back to GlobalOpenTelemetry, a no-op by default, so no
    // metrics appear. That shows what a user who sets nothing up would get.
    OpenTelemetrySdk sdk = AppTelemetry.create(opts);
    ToyConnectorConfig.Builder cfg =
        new ToyConnectorConfig.Builder()
            .withIamAuthn(opts.iam)
            .withFailureRate(opts.failureRate)
            .withRefreshIntervalMs(opts.refreshSec * 1000L);
    if (!opts.global) {
      cfg.withOpenTelemetry(sdk);
    } else {
      System.out.println("--global: connector falls back to GlobalOpenTelemetry (no-op).");
    }

    ToyConnector connector = new ToyConnector(cfg.build());
    // One thread pool runs everything: starting sessions, closing sockets later, and printing
    // the summary line.
    ScheduledExecutorService pool = Executors.newScheduledThreadPool(16);
    Stats stats = new Stats();

    // On Ctrl-C (or --duration ending), stop traffic, then close the SDK. Closing the SDK
    // flushes the last interval so short runs still export something.
    Runnable shutdown =
        () -> {
          pool.shutdownNow();
          connector.close();
          sdk.close();
          System.out.println("[app] final: " + stats);
        };
    Runtime.getRuntime().addShutdownHook(new Thread(shutdown));

    System.out.printf(
        "Simulating %.1f dials/s across %d instance(s), exporters=%s%n",
        opts.qps, opts.instances, opts.exporters);

    // Start one session every 1/qps seconds. Each session runs on the pool so a slow dial never
    // delays the next one.
    long periodMicros = (long) (1_000_000 / opts.qps);
    pool.scheduleAtFixedRate(
        () -> pool.execute(() -> oneSession(connector, opts, pool, stats)),
        0,
        periodMicros,
        TimeUnit.MICROSECONDS);

    // Every 5 s, print the app's own count of dials. It is kept outside OpenTelemetry, so you can
    // check it against the dial_count table from the exporter.
    pool.scheduleAtFixedRate(
        () -> System.out.println("[app] " + stats), 5, 5, TimeUnit.SECONDS);

    // Run for --duration seconds, or forever until Ctrl-C.
    if (opts.durationSec > 0) {
      Thread.sleep(opts.durationSec * 1000L);
      System.exit(0);
    } else {
      Thread.currentThread().join();
    }
  }

  /** One fake client session: connect, run a few queries, and close a little later. */
  private static void oneSession(
      ToyConnector connector,
      AppTelemetry.Options opts,
      ScheduledExecutorService pool,
      Stats stats) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    // Pick one of the --instances fake instances at random.
    String instance =
        "projects/toy-project/locations/us-central1/clusters/toy-cluster/instances/toy-"
            + r.nextInt(opts.instances);
    long start = System.nanoTime();
    try {
      ToyConnector.ToySocket socket = connector.connect(instance);
      stats.ok(System.nanoTime() - start);
      // 1 to 5 queries with random request and response sizes, so the byte counters move.
      for (int i = 0, n = 1 + r.nextInt(5); i < n; i++) {
        socket.query(64 + r.nextInt(512), 128 + r.nextInt(64 * 1024));
      }
      // Hold the connection a while so open_connections has something to show.
      pool.schedule(socket::close, 500 + r.nextInt(5_000), TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      // The connector already recorded the failure with its status; only count it here.
      stats.failed();
    }
  }

  /** Client-side view of dial latency, independent of the telemetry pipeline. */
  static final class Stats {
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    /** Counts a successful dial and adds its latency. */
    void ok(long nanos) {
      ok.incrementAndGet();
      totalNanos.addAndGet(nanos);
    }

    /** Counts a failed dial. */
    void failed() {
      failed.incrementAndGet();
    }

    @Override
    public String toString() {
      long n = ok.get();
      return String.format(
          "dials ok=%d failed=%d avg_latency=%.1fms",
          n, failed.get(), n == 0 ? 0.0 : totalNanos.get() / 1e6 / n);
    }
  }
}
