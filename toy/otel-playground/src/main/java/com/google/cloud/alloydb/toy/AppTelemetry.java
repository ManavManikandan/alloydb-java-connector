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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * The application side: everything a user of the connector would own. It builds an SDK with the
 * exporters named on the command line and hands the result to the connector through {@code
 * withOpenTelemetry}.
 *
 * <p>How the OpenTelemetry pieces fit together:
 *
 * <ul>
 *   <li>The <b>connector</b> records measurements on instruments (counters, histograms).
 *   <li>The <b>MeterProvider</b> owns those instruments and aggregates the measurements.
 *   <li>A <b>MetricReader</b> decides when to collect. A {@link PeriodicMetricReader} collects on a
 *       timer and pushes to an exporter; Prometheus instead collects whenever it is scraped.
 *   <li>An <b>exporter</b> decides where the collected data goes: the console, a Collector, etc.
 * </ul>
 */
final class AppTelemetry {

  private AppTelemetry() {}

  /** Builds the application's OpenTelemetry SDK from the command-line options. */
  static OpenTelemetrySdk create(Options opts) {
    // The Resource describes *this application*. Every exported metric carries it, so a backend
    // can tell which service the numbers came from.
    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("service.name"), "otel-playground",
                        AttributeKey.stringKey("deployment.environment"), "local")));

    SdkMeterProviderBuilder meters = SdkMeterProvider.builder().setResource(resource);

    // A view is an application-side choice the library never sees: here, latency buckets tuned
    // for connection setup instead of the SDK's general-purpose defaults.
    meters.registerView(
        InstrumentSelector.builder()
            .setType(InstrumentType.HISTOGRAM)
            .setName(ConnectorMetrics.DIAL_LATENCY)
            .build(),
        View.builder()
            .setAggregation(
                Aggregation.explicitBucketHistogram(
                    Arrays.asList(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)))
            .build());

    // Register one reader per exporter named in --exporters. Several can run side by side; each
    // gets its own copy of the same data.
    Duration interval = Duration.ofSeconds(opts.exportIntervalSec);
    for (String name : opts.exporters) {
      switch (name) {
        case "none":
          // Metrics are still recorded, just never exported. Useful for overhead experiments.
          break;
        case "console":
          // Readable tables on stdout, every --interval seconds.
          meters.registerMetricReader(
              PeriodicMetricReader.builder(new ConsoleTableExporter())
                  .setInterval(interval)
                  .build());
          break;
        case "logging":
          // The SDK's own console exporter: raw toString() lines on stderr via
          // java.util.logging. This is what OTEL_METRICS_EXPORTER=console gives you.
          meters.registerMetricReader(
              PeriodicMetricReader.builder(LoggingMetricExporter.create())
                  .setInterval(interval)
                  .build());
          break;
        case "otlp":
          // Pushes to an OpenTelemetry Collector over gRPC (see observability/).
          meters.registerMetricReader(
              PeriodicMetricReader.builder(
                      OtlpGrpcMetricExporter.builder().setEndpoint(opts.otlpEndpoint).build())
                  .setInterval(interval)
                  .build());
          break;
        case "prometheus":
          // Pull model: no timer here. The app serves /metrics and Prometheus scrapes it.
          meters.registerMetricReader(
              PrometheusHttpServer.builder().setPort(opts.prometheusPort).build());
          System.out.printf("Prometheus scrape endpoint: http://localhost:%d/metrics%n",
              opts.prometheusPort);
          break;
        case "slow":
          // Fault injection: an exporter that blocks. Dials should not slow down.
          meters.registerMetricReader(
              PeriodicMetricReader.builder(new SlowExporter(5_000)).setInterval(interval).build());
          break;
        case "broken":
          // Fault injection: an exporter that throws. Dials should keep succeeding.
          meters.registerMetricReader(
              PeriodicMetricReader.builder(new BrokenExporter()).setInterval(interval).build());
          break;
        default:
          throw new IllegalArgumentException("Unknown exporter: " + name);
      }
    }
    // OpenTelemetrySdk bundles the meter provider (and, in a real app, tracing and logs) into
    // the single object the connector accepts.
    return OpenTelemetrySdk.builder().setMeterProvider(meters.build()).build();
  }

  /** Blocks for a long time on every export. Dial latency should not move. */
  static final class SlowExporter implements MetricExporter {
    private final long delayMs;

    SlowExporter(long delayMs) {
      this.delayMs = delayMs;
    }

    // Sleeps on the reader's own thread. If dial latency moved, recording would be coupled to
    // exporting, which would be a design bug.
    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      System.out.printf("[slow exporter] sitting on %d metrics for %d ms%n", metrics.size(), delayMs);
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(
        io.opentelemetry.sdk.metrics.InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public MemoryMode getMemoryMode() {
      return MemoryMode.IMMUTABLE_DATA;
    }
  }

  /** Throws on every export. Dials should keep succeeding. */
  static final class BrokenExporter implements MetricExporter {
    // The reader catches this and logs it; the connector never finds out.
    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      throw new IllegalStateException("[broken exporter] backend is down");
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(
        io.opentelemetry.sdk.metrics.InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }
  }

  /** Command-line options. Each field's initial value is the default. */
  static final class Options {
    String mode = "run";
    List<String> exporters = Arrays.asList("console");
    String otlpEndpoint = "http://localhost:4317";
    int prometheusPort = 9464;
    int exportIntervalSec = 10;
    int durationSec = 0;
    double qps = 5;
    double failureRate = 0.05;
    int instances = 2;
    int refreshSec = 15;
    boolean iam = false;
    boolean global = false;

    /** Parses "--key=value" flags. A bare "--flag" means "--flag=true". */
    static Options parse(String[] args) {
      Options o = new Options();
      for (String arg : args) {
        if (arg.equals("--help") || arg.equals("-h")) {
          usage();
          System.exit(0);
        }
        // Split "--key=value" into key and value.
        String[] kv = arg.replaceFirst("^--", "").split("=", 2);
        String k = kv[0];
        String v = kv.length > 1 ? kv[1] : "true";
        switch (k) {
          case "mode": o.mode = v; break;
          case "exporters": o.exporters = Arrays.asList(v.split(",")); break;
          case "otlp-endpoint": o.otlpEndpoint = v; break;
          case "prometheus-port": o.prometheusPort = Integer.parseInt(v); break;
          case "interval": o.exportIntervalSec = Integer.parseInt(v); break;
          case "duration": o.durationSec = Integer.parseInt(v); break;
          case "qps": o.qps = Double.parseDouble(v); break;
          case "failure-rate": o.failureRate = Double.parseDouble(v); break;
          case "instances": o.instances = Integer.parseInt(v); break;
          case "refresh": o.refreshSec = Integer.parseInt(v); break;
          case "iam": o.iam = Boolean.parseBoolean(v); break;
          case "global": o.global = Boolean.parseBoolean(v); break;
          default:
            System.err.println("Unknown option: " + arg);
            usage();
            System.exit(2);
        }
      }
      return o;
    }

    /** Prints the --help text. */
    static void usage() {
      System.out.println(
          String.join(
              "\n",
              "Usage: java -jar target/otel-playground.jar [options]",
              "  --mode=run|bench            simulate traffic, or time the recording path",
              "  --exporters=a,b             console (default), logging, otlp, prometheus,",
              "                              slow, broken, none",
              "  --otlp-endpoint=URL         default http://localhost:4317",
              "  --prometheus-port=N         default 9464",
              "  --interval=SEC              push-exporter interval, default 10",
              "  --duration=SEC              0 = run until Ctrl-C",
              "  --qps=N                     dials per second, default 5",
              "  --failure-rate=F            0..1, default 0.05",
              "  --instances=N               simulated instances, default 2",
              "  --refresh=SEC               refresh-ahead interval, default 15",
              "  --iam                       record auth_type=iam",
              "  --global                    don't pass an OpenTelemetry; use GlobalOpenTelemetry"));
    }
  }
}
