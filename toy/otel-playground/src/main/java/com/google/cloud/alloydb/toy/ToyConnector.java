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

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A fake connector with the same metric-recording shape as {@code Connector}, {@code Refresher} and
 * {@code ConnectionSocket}, but no network. The first dial to an instance is a cache miss and pays
 * for a simulated certificate fetch; later dials are cache hits. A background "refresh ahead" task
 * renews each instance on a timer. Failures are injected at random.
 *
 * <p>How this maps to the real connector:
 *
 * <ul>
 *   <li>{@link #connect}: {@code Connector.connect}, which gets connection info from a cache and
 *       opens a TLS socket.
 *   <li>{@link #refresh}: a call to the AlloyDB Admin API for the instance's IP and a fresh client
 *       certificate.
 *   <li>{@link ToySocket}: {@code ConnectionSocket}, which counts bytes and tracks open
 *       connections.
 * </ul>
 */
final class ToyConnector implements AutoCloseable {

  /** The failure statuses a dial can end with after the connection info is cached. */
  static final String[] DIAL_ERRORS = {"tcp_error", "tls_error", "mdx_error", "user_error"};

  private final ToyConnectorConfig config;
  private final ConnectorMetrics metrics;
  // Instances whose connection info is "cached". Present means the next dial is a cache hit.
  private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
  // Background thread that re-fetches connection info before it would expire.
  private final ScheduledExecutorService refresher;
  private final Random random;

  ToyConnector(ToyConnectorConfig config) {
    this(config, new Random());
  }

  /** Tests pass a seeded {@link Random} so runs are repeatable. */
  ToyConnector(ToyConnectorConfig config, Random random) {
    this.config = config;
    // The instruments are created from whatever OpenTelemetry the application configured.
    this.metrics = new ConnectorMetrics(config.getOpenTelemetry());
    this.random = random;
    // Daemon thread, so a forgotten close() can't keep the JVM alive.
    this.refresher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "toy-refresher");
              t.setDaemon(true);
              return t;
            });
  }

  /** Simulates {@code Connector.connect}: records dial_count, dial_latencies, open_connections. */
  ToySocket connect(String instance) throws IOException {
    long start = System.nanoTime();
    boolean cacheHit = cache.containsKey(instance);
    boolean iam = config.isIamAuthn();

    // Cache miss: fetch metadata and a client certificate from the "Admin API", then keep it
    // fresh in the background. A failure here is a cache_error.
    if (!cacheHit) {
      if (!refresh(instance, "refresh_ahead", 80, 250)) {
        metrics.recordDial(instance, iam, false, "cache_error");
        throw new IOException("cache_error: could not fetch connection info for " + instance);
      }
      scheduleRefresh(instance);
    }

    // TCP connect + TLS 1.3 handshake + metadata exchange, faked with a short sleep.
    sleep(3 + random.nextInt(25));

    // Inject a random failure at --failure-rate, with a random status.
    if (random.nextDouble() < config.getFailureRate()) {
      String status = DIAL_ERRORS[random.nextInt(DIAL_ERRORS.length)];
      metrics.recordDial(instance, iam, cacheHit, status);
      throw new IOException(status + " while dialing " + instance);
    }

    // Success: count the dial, record how long it took (successes only), and mark the
    // connection open.
    metrics.recordDial(instance, iam, cacheHit, "success");
    metrics.recordDialLatency(instance, (System.nanoTime() - start) / 1_000_000.0);
    metrics.recordOpen(instance, iam);
    return new ToySocket(instance, iam);
  }

  /**
   * Fakes one Admin API round trip taking {@code minMs} to {@code maxMs}, records refresh_count,
   * and caches the instance on success. Returns whether it succeeded.
   */
  private boolean refresh(String instance, String type, int minMs, int maxMs) {
    sleep(minMs + random.nextInt(maxMs - minMs));
    boolean ok = random.nextDouble() >= config.getFailureRate();
    metrics.recordRefresh(instance, ok ? "success" : "failure", type);
    if (ok) {
      cache.put(instance, Boolean.TRUE);
    }
    return ok;
  }

  /** Re-runs {@link #refresh} for this instance every --refresh seconds, like refresh-ahead. */
  private void scheduleRefresh(String instance) {
    long interval = config.getRefreshIntervalMs();
    refresher.scheduleWithFixedDelay(
        () -> refresh(instance, "refresh_ahead", 50, 150), interval, interval, TimeUnit.MILLISECONDS);
  }

  /** Stops background refreshes. Does not touch the application's OpenTelemetry. */
  @Override
  public void close() {
    refresher.shutdownNow();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Simulates {@code ConnectionSocket}: byte counters and the close side of open_connections. */
  final class ToySocket implements AutoCloseable {
    private final String instance;
    private final boolean iam;
    private boolean closed;

    private ToySocket(String instance, boolean iam) {
      this.instance = instance;
      this.iam = iam;
    }

    /** Pretends to send a request and read a response, counting the bytes each way. */
    void query(int requestBytes, int responseBytes) {
      metrics.recordBytesTx(instance, requestBytes);
      metrics.recordBytesRx(instance, responseBytes);
    }

    /**
     * Marks the connection closed. The flag makes a second close() a no-op, so open_connections
     * can't go negative.
     */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        metrics.recordClose(instance, iam);
      }
    }
  }
}
