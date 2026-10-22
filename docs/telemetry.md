# Connector Metrics and Telemetry

The AlloyDB Java Connector records metrics about the connections it makes:
dial attempts and their latency, open connections, bytes sent and received,
and refreshes of instance connection info. It can deliver these metrics along
two independent paths:

- **Your application's OpenTelemetry `MeterProvider` (recommended).** The
  connector records its metrics through a provider that your application
  configures and owns. You export them with whatever OpenTelemetry exporters you
  already use, such as Prometheus, OTLP, or Cloud Monitoring. This is the
  preferred integration for external observability.
- **Built-in Cloud Monitoring export.** The connector runs its own private
  OpenTelemetry pipeline that writes to Cloud Monitoring. It is off by default.

Each path can be enabled on its own, and enabling or disabling one never
affects the other. When neither is enabled, the connector does no telemetry
work at all.

## Recording metrics through your application's MeterProvider

A `MeterProvider` is a live object, so you configure it in code on a
[named connector](configuration.md#named-connectors) with
`ConnectorConfig.Builder.withMeterProvider()`. There is no JDBC connection
property for it.

```java
// Your application's OpenTelemetry setup. The connector does not create,
// configure, or own this provider.
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .setResource(Resource.getDefault().toBuilder()
            .put("service.name", "orders-service")
            .build())
        .registerMetricReader(/* your reader, see the examples below */)
        .build();

ConnectorConfig config =
    new ConnectorConfig.Builder()
        .withMeterProvider(meterProvider)
        .build();
ConnectorRegistry.register("my-connector", config);
```

Then point your connections at the named connector:

```java
config.addDataSourceProperty("alloydbNamedConnector", "my-connector");
```

If you already use the OpenTelemetry SDK autoconfiguration or the Java agent,
pass its provider, for example `GlobalOpenTelemetry.get().getMeterProvider()`.
The connector never reads `GlobalOpenTelemetry` on its own.

### Provider ownership and lifecycle

Your application owns the provider:

- The connector never calls `shutdown()`, `close()`, or `forceFlush()` on it.
  This holds for `ConnectorRegistry.close()`, `ConnectorRegistry.reset()`,
  `ConnectorRegistry.shutdown()`, and closing a named connector.
- Shut the provider down yourself when your application exits, after your
  connection pools are closed, so the final measurements are exported.
- If you shut the provider down while connectors are still running, the
  connector keeps working and its measurements are discarded.
- A provider that throws, or that cannot create the connector's instruments,
  never fails or slows a connection. The connector logs a warning once and
  continues without client metrics.

### Resource attributes and target identity

Your provider's `Resource` describes your application, so the connector never
adds anything to it. Instead, every measurement carries the identity of the
AlloyDB instance it relates to as metric attributes. This means a single
application connecting to several instances reports one time series per
instance, under your application's resource.

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

The client metrics are the same measurements as the built-in metrics, under
OpenTelemetry-style names. The built-in pipeline instead identifies the
instance through the Cloud Monitoring `alloydb.googleapis.com/InstanceClient`
monitored resource.

| Client metric | Built-in metric (`alloydb.googleapis.com/client/connector/`) |
|---------------|--------------------------------------------------------------|
| `alloydb.connector.dials` | `dial_count` |
| `alloydb.connector.dial.duration` | `dial_latencies` |
| `alloydb.connector.connections.open` | `open_connections` |
| `alloydb.connector.bytes.sent` | `bytes_sent_count` |
| `alloydb.connector.bytes.received` | `bytes_received_count` |
| `alloydb.connector.refreshes` | `refresh_count` |

## Filtering and controlling cardinality

Use OpenTelemetry SDK views on your provider to filter the connector's metrics.
Each AlloyDB instance produces its own time series. If you connect to many
instances, you can aggregate across them by dropping per-instance attributes:

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

## Export examples

### Prometheus

Add `io.opentelemetry:opentelemetry-exporter-prometheus` to your application,
then register a Prometheus reader on your provider:

```java
SdkMeterProvider meterProvider =
    SdkMeterProvider.builder()
        .registerMetricReader(PrometheusHttpServer.builder().setPort(9464).build())
        .build();

ConnectorRegistry.register(
    "my-connector",
    new ConnectorConfig.Builder().withMeterProvider(meterProvider).build());
```

Prometheus scrapes `http://<host>:9464/metrics`. The exporter converts names
to Prometheus conventions: dots become underscores, units become suffixes, and
counters get `_total`. For example, `alloydb.connector.dials` becomes
`alloydb_connector_dials_total`, and `alloydb.connector.bytes.sent` becomes
`alloydb_connector_bytes_sent_bytes_total`. The exact rendering depends on your
exporter version. Attribute dots also become underscores, for example
`alloydb_instance_id`.

### OTLP

Add `io.opentelemetry:opentelemetry-exporter-otlp` to your application, then
export periodically to an OpenTelemetry Collector or any OTLP endpoint:

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

ConnectorRegistry.register(
    "my-connector",
    new ConnectorConfig.Builder().withMeterProvider(meterProvider).build());

// On application shutdown, after closing connection pools:
meterProvider.close();
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

Built-in export and a client `MeterProvider` can be used together. Turning
built-in export off never turns off client metrics.
