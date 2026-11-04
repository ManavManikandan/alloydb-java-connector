# OpenTelemetry playground for the AlloyDB Java Connector

This is a standalone toy for testing ideas before they go into the connector. It is not
part of the root build, it needs no AlloyDB instance, and it does not touch the network
except to reach the exporters you turn on.

It covers three things:

1. **The library side** ([`ConnectorMetrics`](src/main/java/com/google/cloud/alloydb/toy/ConnectorMetrics.java)).
   The instruments use the same meter, metric names and attributes as the real
   `CloudMonitoringMetricRecorder`, but they are built from an `OpenTelemetry` that the
   *application* hands in. This file uses only the OTel API.
2. **The proposed public hook** ([`ToyConnectorConfig.Builder.withOpenTelemetry`](src/main/java/com/google/cloud/alloydb/toy/ToyConnectorConfig.java)).
   If no `OpenTelemetry` is passed in, it falls back to `GlobalOpenTelemetry`, which is a
   no-op unless the app or the Java agent installed one.
3. **The application side** ([`AppTelemetry`](src/main/java/com/google/cloud/alloydb/toy/AppTelemetry.java)).
   The application owns the SDK, the exporters, the Resource and a View with custom
   latency buckets.

[`ToyConnector`](src/main/java/com/google/cloud/alloydb/toy/ToyConnector.java) fakes
dials, cache hits and misses, refresh-ahead, byte counts and random `tcp_error`,
`tls_error`, `mdx_error`, `user_error` and `cache_error` failures.

[`ConsoleTableExporter`](src/main/java/com/google/cloud/alloydb/toy/ConsoleTableExporter.java)
is the default exporter. It prints each metric as a small table, one row per combination
of attribute values, with instance names shortened to their last path segment. Metrics
from any other meter, such as the SDK's own, are skipped.

## Build

From the repository root:

```bash
./mvnw -f toy/otel-playground/pom.xml package
```

## Run

```bash
cd toy/otel-playground

# 1. Console only. Every 10 s, one readable table per metric on stdout.
java -jar target/otel-playground.jar
#    The SDK's raw LoggingMetricExporter (one long line per metric, on stderr) is still
#    available, and is what OTEL_METRICS_EXPORTER=console gives a real application:
java -jar target/otel-playground.jar --exporters=logging

# 2. Full stack: OTLP -> Collector -> Prometheus -> Grafana
docker compose -f observability/docker-compose.yaml up -d
java -jar target/otel-playground.jar --exporters=otlp --interval=5 --qps=8
#   Grafana:    http://localhost:3001  (dashboard "AlloyDB > AlloyDB Connector (playground)")
#   Prometheus: http://localhost:9090

# 3. Pull model: the app serves /metrics and Prometheus scrapes it directly
java -jar target/otel-playground.jar --exporters=prometheus
#   then pick "otel-playground-direct" in the dashboard's Export path dropdown

# Tear down
docker compose -f observability/docker-compose.yaml down
```

`--help` lists every flag.

## Experiments worth running

| Try | Question it answers |
| --- | --- |
| `--exporters=slow` | A 5-second blocking exporter. Does `avg_latency` on the dial path move? (It shouldn't: export runs on the reader thread.) |
| `--exporters=broken` | An exporter that throws on every export. Do dials keep succeeding? |
| `--global` | What does a user get by default with no OTel configured? (A no-op.) |
| `--mode=bench` | How much do the metric calls cost per dial with a no-op OTel versus the SDK? |
| `--instances=50` | Cardinality: what does one series per instance do to memory and cost? |
| `--failure-rate=0.3` | Watch the error statuses and `refresh_count{status="failure"}` in Grafana. |
| `--exporters=otlp,prometheus` | Two readers on one MeterProvider. Compare the two `job`s in Prometheus. |

On a laptop, `--mode=bench` shows about **0.6 µs per dial even with a no-op OTel**. Almost
all of that is building `Attributes` on every call, not the SDK. The real connector
avoids this by hoisting attribute sets into constants and by checking
`MetricRecorder.isEnabled()` so it skips the work when metrics are off. Keep both habits
in the real implementation.

## Open design questions this surfaces

- **Where does the instance identity go?** The connector puts project, location,
  cluster, instance and client UID on the monitored *Resource*. When the user owns the
  Resource, the instance has to become a metric attribute (`alloydb.instance` here),
  or the connector needs its own `MeterProvider` per instance.
- **Should the built-in export to Cloud Monitoring stay alongside the user's export?**
  Probably yes: two independent pipelines, each with its own on/off switch.
- **Which OTel object should the public type be?** `OpenTelemetry` or `MeterProvider`?
  Passing `OpenTelemetry` leaves room for traces later and matches other OTel
  instrumentation libraries.
- **Should the API be pinned to stable OTel?** It should depend on `opentelemetry-api`
  only, never on the SDK. The application chooses the SDK version.

## Tests

```bash
./mvnw -f toy/otel-playground/pom.xml test
```

The tests use `InMemoryMetricReader` to check that a user-supplied `OpenTelemetry`
receives every connector metric under the connector's meter name, and that the console
exporter turns them into tables.
