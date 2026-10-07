# Development Guide

This guide applies to the entire repository. It is the starting point for human contributors and for coding agents. Usage lives in `README.md`, `docs/jdbc.md`,
and `docs/configuration.md`. The contribution process is also described in
`CONTRIBUTING.md`.

## Background

The AlloyDB Java Connector (`com.google.cloud:alloydb-jdbc-connector`) is a JDBC
`SocketFactory` for the PostgreSQL driver. It opens an mTLS 1.3 connection to an
AlloyDB instance using IAM credentials. Callers do not configure TLS
certificates.

A standard PostgreSQL driver can encrypt a connection and, when configured,
verify a server certificate. It does not manage AlloyDB's IAM integration or
client certificate lifecycle. The connector verifies the AlloyDB server
certificate, presents and rotates a short-lived client certificate, and
authorizes the caller with Cloud IAM. That combination is aimed at high-security
environments that need a verified server, a verified client, centrally
controlled access, and certificates that operators do not manage by hand.

Library code is in `alloydb-jdbc-connector/`. Package: `com.google.cloud.alloydb`.
The parent build has that one module. `samples/` are standalone examples with
their own POMs and are not built by `./mvnw` from the repository root. Do not
add them as a module.

Use the repo Maven wrapper (`./mvnw`) from the repository root. Do not require a
preinstalled `mvn`. `./mvnw` downloads the pinned Maven.

JDK 8 is the language level. CI also builds on 11, 17, 21, and 25. These compile
on JDK 11+ and fail the JDK 8 job: `var`, records, text blocks, switch
expressions, `List.of` / `Set.of` / `Map.of`, `Stream.toList()`,
`String.isBlank` / `strip` / `repeat`, `Optional.isEmpty`,
`Objects.requireNonNullElse`. Use `Arrays.asList`, `Collections.emptyList()`,
and Guava only where the surrounding file already does.

Versions are owned by release-please (`java-yoshi`). Do not edit version
numbers, `{x-version-update:...}` markers, `Version.java`, or `CHANGELOG.md` by
hand. Dependency bumps are owned by Renovate. Do not change dependency versions
as part of an unrelated change.

## Architecture

| Location | Responsibility |
| --- | --- |
| `SocketFactory` | JDBC entry point. Reads `Properties` and builds a `ConnectionConfig`. |
| `ConnectionConfig` | JDBC property names and the parsed connection settings. |
| `ConnectorConfig`, `ConnectorRegistry` | Supported way to register a named connector. |
| `InternalConnectorRegistry` | Process-wide facade. Picks a named connector (`alloydbNamedConnector`) or an unnamed one keyed by `ConnectorConfig`. |
| `Connector` | Loads cached instance metadata and dials. |
| `DefaultConnectionInfoRepository`, `AlloyDBAdminClientFactory` | Control plane. Call the AlloyDB Admin API for IP addresses and a signed client certificate. |
| `RefreshAheadConnectionInfoCache`, `LazyConnectionInfoCache`, `Refresher` | Cache that metadata and refresh it. |
| `ConnectionSocket` | Data plane. TLS 1.3 handshake to the instance, then the connector metadata exchange. |
| `MetricRecorder`, `CloudMonitoringMetricRecorder`, `MetricRecorderFactory`, `NullMetricRecorder`, `TelemetryAttributes` | OpenTelemetry metrics, exported to Cloud Monitoring. |
| `com.google.cloud.alloydb.nativeimage` | Native-image hints. Excluded from the JaCoCo coverage run. |
| `samples/` | Standalone examples. Not a module of this build. |

A connection follows this sequence:

1. `SocketFactory` reads JDBC `Properties` and builds a package-private
   `ConnectionConfig`.
2. `InternalConnectorRegistry.INSTANCE.connect` picks a named connector
   (`alloydbNamedConnector`) or an unnamed one keyed by `ConnectorConfig`.
3. The control plane fills the cache. `DefaultConnectionInfoRepository` calls
   the AlloyDB Admin API for the instance IP addresses and a signed client
   certificate. `RefreshAheadConnectionInfoCache` or `LazyConnectionInfoCache`
   stores that metadata and refreshes it.
4. The data plane is `ConnectionSocket`. It opens a TLS 1.3 connection to the
   instance with the client certificate from that cache, then performs the
   connector metadata exchange.

Telemetry is OpenTelemetry. `CloudMonitoringMetricRecorder` records dial,
refresh, connection, and byte metrics on the meter
`alloydb.googleapis.com/client/connector` and exports them with the Cloud
Monitoring exporter as the `alloydb.googleapis.com/InstanceClient` monitored
resource. `MetricRecorderFactory` is the construction site. `Connector` holds
one recorder per instance.

JDBC property names are constants on `ConnectionConfig`. `ConnectorRegistry`
and `ConnectorConfig` are the supported way to register a named connector.
`InternalConnectorRegistry` is the internal facade.

The public types today are `SocketFactory`, `ConnectorConfig`,
`ConnectorRegistry`, `AuthType`, `IpType`, `RefreshStrategy`,
`ConnectionInfoRepositoryFactory`, and `LazyConnectionInfoCache`.

## Testing

Run these from the repository root. The scripts `cd` to the root themselves.

```bash
./mvnw -B -ntp -DskipTests -Dclirr.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true install
scripts/format.sh              # google-java-format
scripts/lint.sh                # fmt check only
scripts/test_units.sh          # unit tests plus 75% JaCoCo line coverage
scripts/check_dependencies.sh
scripts/check_clirr.sh         # binary compatibility of the public API

# Integration tests. They need a live AlloyDB instance and only work from
# inside that instance's VPC. CI runs them on self-hosted runners and skips
# them for pull requests from forks.
scripts/test_system.sh
scripts/test_graalvm.sh
```

### Unit tests

Unit tests sit next to the class they cover and are named `*Test`. They must
not contact AlloyDB, the metadata server, or the network. Reuse the existing
fakes: `StubCredentialFactory`, `StubConnectionInfoCache`,
`StubConnectionInfoRepositoryFactory`, `InMemoryConnectionInfoRepo`,
`MockAlloyDBAdminGrpc`, and `FakeSslServer`.

`InternalConnectorRegistry` is a process-wide enum. Tests replace collaborators
through its `@VisibleForTesting` methods. Do not widen visibility so a test can
reach a field.

Add or update a unit test for every behavior change, including bug fixes.
`scripts/test_units.sh` fails when JaCoCo line coverage drops below 75%. Do not
lower that threshold. Native-image code under
`com.google.cloud.alloydb.nativeimage` is excluded from the coverage run. Do not
remove that exclude.

For a single unit test:

```bash
./mvnw -B -ntp -Dclirr.skip=true -Denforcer.skip=true -Dtest=ConnectorConfigTest test
```

What CI runs on a pull request (`.github/workflows/ci.yaml`):

- `scripts/test_units.sh` on Linux for JDK 8, 11, 17, 21, and 25, and on
  Windows for JDK 8 (`scripts/test_units.bat` calls the same shell script).
- `scripts/check_dependencies.sh` on those same JDK versions. It runs
  `dependency:analyze -DfailOnWarning=true`. An unused or undeclared dependency
  fails the build.
- `scripts/lint.sh` on JDK 21. This is the formatter check, not Error Prone.
- `scripts/check_clirr.sh` on JDK 8.

Error Prone is the parent `java21` profile (`failOnWarning`). It runs only when
the compiler JDK is 21 or newer, including the JDK 21 and 25 unit-test jobs. A
clean JDK 8 compile and a green `scripts/lint.sh` do not exercise it. Compile
once on JDK 21 or newer before finishing.

### Integration tests

Integration tests are named `IT*`. They run only under
`-Penable-integration-tests`, which `scripts/test_system.sh` enables.
`scripts/test_graalvm.sh` runs the native-image test profile against the same
live instance.

Both scripts need Application Default Credentials
(`gcloud auth application-default login`) and the variables in `.envrc.example`:

- `ALLOYDB_DB`, `ALLOYDB_USER`, `ALLOYDB_PASS`
- `ALLOYDB_INSTANCE_NAME`, `ALLOYDB_INSTANCE_IP`
- `ALLOYDB_IAM_USER`, `ALLOYDB_IMPERSONATED_USER`, `ALLOYDB_PSC_INSTANCE_URI`

Run them from inside the instance's VPC. CI runs them on self-hosted runners
and skips them for pull requests from forks.

## Contribution Guidelines

- Search existing issues and pull requests before starting work. Open an issue
  first for a new feature, a public API change, a substantial refactor, or a
  bug whose fix needs discussion. Describe the use case or reproduction and the
  proposed approach. Small, well-understood bug fixes, documentation
  corrections, and test improvements can go straight to a pull request. Link
  any related issue. Report vulnerabilities through `SECURITY.md` rather than a
  public issue.
- Keep each pull request small and atomic: one behavior change, with the tests
  and docs that belong to it.
- All changes go through a GitHub pull request. A Contributor License Agreement
  is required (https://cla.developers.google.com/).
- Commit subjects follow the existing Conventional Commits style (`feat:`,
  `fix:`, `docs:`, `refactor:`, `chore:`). Release-please reads those subjects.
  Use the same style for the pull request title.
- In the pull request description, describe the user-visible behavior change,
  link the issue when there is one, and report which checks were run. Say so
  when integration tests could not be run, including a fork pull request where
  CI skips them, or a local checkout without VPC access to the instance.
- Do not commit generated `target/` output or Maven wrapper downloads. Do not
  commit credentials, `.envrc`, service-account JSON, or real instance URIs.
  `.envrc.example` stays placeholder-only.
- Format with `scripts/format.sh`. The check is `com.spotify.fmt:fmt-maven-plugin`
  (Google Java Style). Do not hand-format or introduce a second formatter.
- Every new Java, shell, XML, and YAML source file needs the Apache 2.0 header
  used by neighboring files of that type (`Copyright <year> Google LLC`).
- Match the file you are editing: immutable value objects, private constructors,
  and `Builder` with `with*` methods. Use `com.google.common.base.Objects` or
  `java.util.Objects` for `equals` and `hashCode`, whichever that class already
  uses.
- Tests use JUnit 4 (`org.junit.Test`) and Truth (`assertThat`). Do not add
  JUnit 5 or another assertion library.
- Log with SLF4J (`LoggerFactory.getLogger`). Do not use `System.out` or
  `java.util.logging`. Do not log access tokens, passwords, private keys, or
  certificate private material.
- Keep the PostgreSQL JDBC driver `provided`. Applications supply it. It is an
  `ignoredDependency` for `dependency:analyze`. Do not move it to `compile`.
- New helpers are package-private. Do not add another public type to reach
  internal code. Reach `InternalConnectorRegistry` through existing
  package-private code. Do not make it public to call it from a new type.
- Clirr checks `ConnectorConfig`, `ConnectorRegistry`, and `SocketFactory`.
  Treat them as stable binary API. Do not remove methods, change signatures, or
  narrow visibility on those types in a non-major change. Run
  `scripts/check_clirr.sh` after any edit to them. The other public types are
  still user-facing. A signature change there is a breaking change for anyone
  who compiled against it.
- JDBC property names and `ConnectorConfig.Builder` methods are user-facing.
  Add a property as a constant on `ConnectionConfig`, cover it in
  `ConnectionConfigTest`, and document it in `docs/configuration.md` and
  `docs/jdbc.md`. Leave the `{x-version-update-start/end}` blocks in
  `docs/jdbc.md` unchanged.
- TLS is `TLSv1.3` inside `ConnectionSocket`, with the client certificate from
  the connector key pair. Do not weaken TLS, disable certificate checks, or
  accept plaintext connections.
- Metrics must not block a connection. `MetricRecorderFactory` returns
  `NullMetricRecorder` when metrics are disabled or when the Cloud Monitoring
  exporter fails to initialize, and it catches `Throwable` so a missing class
  on a shaded classpath does not fail the dial. Keep that fallback.
