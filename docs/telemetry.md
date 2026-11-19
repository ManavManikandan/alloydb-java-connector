# Connector Metrics and Telemetry

The AlloyDB Java Connector records metrics about the connections it makes:
dial attempts and their latency, open connections, bytes sent and received,
and refreshes of instance connection info. It can deliver these metrics along
two independent paths:

- **Your application's global OpenTelemetry `MeterProvider`.** The connector
  records its metrics through `GlobalOpenTelemetry`, the standard OpenTelemetry
  entry point for libraries. If your application already exports metrics
  through a global provider, connector metrics show up in that pipeline with no
  connector-specific setup. This path is on by default.
- **Built-in Cloud Monitoring export.** The connector runs its own private
  OpenTelemetry pipeline that writes to Cloud Monitoring. It is off by default.

Each path can be enabled on its own, and enabling or disabling one never
affects the other.

## Recording metrics through the global provider

Configure OpenTelemetry once, during application startup, the same way you
would for any other instrumented library. Any of these sets the global
provider:

- The [OpenTelemetry Java agent][java-agent].
- [SDK autoconfiguration][autoconfigure] with
  `otel.java.global-autoconfigure.enabled=true`.
- Building an SDK yourself and calling
  `OpenTelemetrySdk.builder()...buildAndRegisterGlobal()` or
  `GlobalOpenTelemetry.set(...)`.

```java
// Application startup, before the first database connection.
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .setResource(Resource.getDefault().toBuilder()
            .put("service.name", "orders-service")
            .build())
        .registerMetricReader(/* your reader, see the examples below */)
        .build();
OpenTelemetrySdk.builder()
    .setMeterProvider(meterProvider)
    .buildAndRegisterGlobal();
```

No connector configuration is needed. Connections made through the JDBC
`SocketFactory` and through named connectors both record to the global
provider.

If your application has not set a global provider, OpenTelemetry's no-op
behavior applies: the connector records nothing externally and does no
per-byte metric work.

### When the connector reads the global provider

Set the global provider before your application opens its first connection.
The connector looks up the global provider the first time it dials each AlloyDB
instance and keeps that recorder for the life of the connector. An instance
first dialed before the global provider was set does not report through it.

The connector never forces the global provider into existence. In
OpenTelemetry Java, reading `GlobalOpenTelemetry` before it is set installs a
no-op instance and makes a later `GlobalOpenTelemetry.set(...)` fail. To avoid
breaking your startup, the connector reads the global only once it is set, or
when you have enabled `otel.java.global-autoconfigure.enabled`, where reading it
is what triggers autoconfiguration.

### Provider ownership and lifecycle

Your application owns the global provider:

- The connector never configures, replaces, or sets the global provider.
- The connector never calls `shutdown()`, `close()`, or `forceFlush()` on it.
  This holds for `ConnectorRegistry.close()`, `ConnectorRegistry.reset()`,
  `ConnectorRegistry.shutdown()`, and closing a named connector.
- Shut the provider down yourself when your application exits, after your
  connection pools are closed, so the final measurements are exported.
- A provider that throws, or that cannot create the connector's instruments,
  never fails or slows a connection. The connector logs a warning and
  continues without global-provider metrics.

### Opting out

To keep connector metrics out of your application's pipeline while still using
OpenTelemetry elsewhere, turn the global path off. On unnamed connectors, use
the connection property:

```java
config.addDataSourceProperty("alloydbEnableGlobalTelemetry", "false");
```

On a [named connector](configuration.md#named-connectors):

```java
ConnectorRegistry.register(
    "my-connector",
    new ConnectorConfig.Builder().withEnableGlobalTelemetry(false).build());
```

Opting out does not affect built-in Cloud Monitoring export. You can also drop
the connector's metrics in your SDK or Collector instead, as described in
[Filtering](#filtering-and-controlling-cardinality).

### Resource attributes and target identity

The global provider's `Resource` describes your application, so the connector
never adds anything to it. Instead, every measurement carries the identity of
the AlloyDB instance it relates to as metric attributes. A single application
connecting to several instances reports one time series per instance, under
your application's resource.

[java-agent]: https://opentelemetry.io/docs/zero-code/java/agent/
[autoconfigure]: https://opentelemetry.io/docs/languages/java/configuration/

## Metric contract

The metric names, types, units, and attributes below are a stable contract.
All metrics are recorded under the instrumentation scope
`com.google.cloud.alloydb`, with the connector's version as the scope version.

| Name | Instrument | Unit | Description |
|------|------------|------|-------------|
| `alloydb.connector.dials` | Counter | `{dial}` | Number of attempts to dial an AlloyDB instance. |
| `alloydb.connector.dial.duration` | Histogram | `ms` | Time taken to successfully dial an AlloyDB instance. |
| `alloydb.connector.connections.open` | UpDownCounter | `{connection}` | Number of connections currently open to an AlloyDB instance. |
| `alloydb.connector.bytes.sent` | Counter | `By` | Number of application bytes sent to an AlloyDB instance. |
| `alloydb.connector.bytes.received` | Counter | `By` | Number of application bytes received from an AlloyDB instance. |
| `alloydb.connector.refreshes` | Counter | `{refresh}` | Number of refreshes of an AlloyDB instance's connection info. |

Notes:

- `alloydb.connector.dial.duration` is recorded only for successful dials.
  Failed dials are counted in `alloydb.connector.dials` with their failure
  `status`.
- No per-connector or per-process identifier, such as the connector's client
  UID, is attached. Series are distinguished only by the attributes below.
- Byte counts are the PostgreSQL protocol bytes your application exchanges with
  the instance. They exclude TLS framing and the connector's metadata exchange.

### Attributes

Every metric carries the target instance attributes and `connector_type`:

| Attribute | Example | Description |
|-----------|---------|-------------|
| `alloydb.project_id` | `my-project` | Project of the AlloyDB instance. |
| `alloydb.location` | `us-central1` | Region of the AlloyDB instance. |
| `alloydb.cluster_id` | `my-cluster` | Cluster of the AlloyDB instance. |
| `alloydb.instance_id` | `my-instance` | ID of the AlloyDB instance. |
| `connector_type` | `java` | Always `java`. |

Some metrics carry additional attributes, each with a fixed set of values:

| Attribute | Values | Metrics |
|-----------|--------|---------|
| `auth_type` | `iam`, `built_in` | `dials`, `connections.open` |
| `is_cache_hit` | `true`, `false` | `dials` |
| `status` | `success`, `user_error`, `cache_error`, `tcp_error`, `tls_error`, `mdx_error` | `dials` |
| `status` | `success`, `failure` | `refreshes` |
| `refresh_type` | `refresh_ahead`, `lazy` | `refreshes` |

The connector never attaches IP addresses, database users, IAM principals,
error messages, or credentials to metrics.

### Relation to the built-in Cloud Monitoring metrics

The global-provider metrics are the same measurements as the built-in metrics, under
OpenTelemetry-style names. The built-in pipeline instead identifies the
instance through the Cloud Monitoring `alloydb.googleapis.com/InstanceClient`
monitored resource.

| Global-provider metric | Built-in metric (`alloydb.googleapis.com/client/connector/`) |
|---------------|--------------------------------------------------------------|
| `alloydb.connector.dials` | `dial_count` |
| `alloydb.connector.dial.duration` | `dial_latencies` |
| `alloydb.connector.connections.open` | `open_connections` |
| `alloydb.connector.bytes.sent` | `bytes_sent_count` |
| `alloydb.connector.bytes.received` | `bytes_received_count` |
| `alloydb.connector.refreshes` | `refresh_count` |

## Filtering and controlling cardinality

All connector metrics use the instrumentation scope (meter name)
`com.google.cloud.alloydb`, so you can select them in your SDK or in an
OpenTelemetry Collector.

### In the application SDK

Use views on your provider. Each AlloyDB instance produces its own time
series. If you connect to many instances, aggregate across them by dropping
per-instance attributes:

```java
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .registerView(
            InstrumentSelector.builder().setMeterName("com.google.cloud.alloydb").build(),
            View.builder()
                .setAttributeFilter(
                    key -> !key.equals("alloydb.instance_id")
                        && !key.equals("alloydb.cluster_id"))
                .build())
        .registerMetricReader(reader)
        .build();
```

Or drop metrics you do not need:

```java
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .registerView(
            InstrumentSelector.builder()
                .setMeterName("com.google.cloud.alloydb")
                .setName("alloydb.connector.bytes.*")
                .build(),
            View.builder().setAggregation(Aggregation.drop()).build())
        .registerMetricReader(reader)
        .build();
```

When more than one view matches an instrument, the SDK produces a separate
stream for each view, so combine your filters into a single view per
instrument.

### In an OpenTelemetry Collector

Use the `filter` processor to drop connector metrics, or the `transform`
processor to remove attributes:

```yaml
processors:
  filter/alloydb:
    metrics:
      metric:
        - 'instrumentation_scope.name == "com.google.cloud.alloydb" and IsMatch(name, "alloydb\\.connector\\.bytes\\..*")'
  transform/alloydb:
    metric_statements:
      - context: datapoint
        statements:
          - delete_key(attributes, "alloydb.instance_id") where instrumentation_scope.name == "com.google.cloud.alloydb"
```

## Export examples

### Prometheus

Add `io.opentelemetry:opentelemetry-exporter-prometheus` to your application
and register a Prometheus reader on the global provider:

```java
OpenTelemetrySdk.builder()
    .setMeterProvider(
        SdkMeterProvider.builder()
            .registerMetricReader(PrometheusHttpServer.builder().setPort(9464).build())
            .build())
    .buildAndRegisterGlobal();
```

With the Java agent, set `OTEL_METRICS_EXPORTER=prometheus` instead.

Prometheus scrapes `http://<host>:9464/metrics`. The exporter converts names
to Prometheus conventions: dots become underscores, units become suffixes, and
counters get `_total`. For example, `alloydb.connector.dials` becomes
`alloydb_connector_dials_total`, and `alloydb.connector.bytes.sent` becomes
`alloydb_connector_bytes_sent_bytes_total`. The exact rendering depends on your
exporter version. Attribute dots also become underscores, for example
`alloydb_instance_id`.

### OTLP and the OpenTelemetry Collector

Add `io.opentelemetry:opentelemetry-exporter-otlp` to your application and
export periodically to a Collector or any OTLP endpoint:

```java
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .registerMetricReader(
            PeriodicMetricReader.builder(
                    OtlpGrpcMetricExporter.builder()
                        .setEndpoint("http://localhost:4317")
                        .build())
                .setInterval(Duration.ofSeconds(60))
                .build())
        .build();
OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();

// On application shutdown, after closing connection pools:
meterProvider.close();
```

With the Java agent or autoconfiguration, set `OTEL_METRICS_EXPORTER=otlp` and
`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`.

A minimal Collector configuration that receives OTLP and exposes the metrics to
Prometheus:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
exporters:
  prometheus:
    endpoint: 0.0.0.0:8889
service:
  pipelines:
    metrics:
      receivers: [otlp]
      exporters: [prometheus]
```

## Built-in Cloud Monitoring export

The connector can also export its metrics to Cloud Monitoring through its own
pipeline, which it creates and shuts down itself. It is off by default. Turn it
on with `ConnectorConfig.Builder.withEnableBuiltInTelemetry(true)` on a named
connector, or with the `alloydbEnableBuiltInTelemetry=true` connection property
on unnamed connectors.

The built-in export writes to the AlloyDB instance's project and needs the
`monitoring.timeSeries.create` permission. If the exporter cannot start, the
connector logs a warning and connects without built-in metrics.

Built-in export and the global provider can be used together. Turning built-in
export off never turns off global-provider metrics, and opting out of the
global provider never turns off built-in export.
