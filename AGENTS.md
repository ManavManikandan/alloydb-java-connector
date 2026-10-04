# AGENTS.md

Instructions for coding agents working in this repository. Human-facing docs live in `README.md`, `CONTRIBUTING.md`, `docs/jdbc.md`, and `docs/configuration.md`.

## Project overview

The AlloyDB Java Connector (`com.google.cloud:alloydb-jdbc-connector`) is a JDBC `SocketFactory` for the PostgreSQL driver. It opens an mTLS 1.3 connection to an AlloyDB instance using IAM credentials. Callers do not configure TLS certificates.

Library code is in `alloydb-jdbc-connector/`. Package: `com.google.cloud.alloydb`. The parent build has that one module. `samples/` are standalone examples with their own POMs and are not built by `./mvnw` from the repository root. Do not add them as a module.

Connection path:

1. `SocketFactory` reads JDBC `Properties` and builds a package-private `ConnectionConfig`.
2. `InternalConnectorRegistry.INSTANCE.connect` picks a named connector (`alloydbNamedConnector`) or an unnamed one keyed by `ConnectorConfig`.
3. `Connector` gets cached instance metadata, then `ConnectionSocket` performs the TLS 1.3 handshake and the connector metadata exchange.

JDBC property names are constants on `ConnectionConfig`. Add a property there, cover it in `ConnectionConfigTest`, and document it in `docs/configuration.md` and `docs/jdbc.md`. Leave the `{x-version-update-start/end}` blocks in `docs/jdbc.md` unchanged.

`ConnectorRegistry` and `ConnectorConfig` are the supported way to register a named connector. `InternalConnectorRegistry` is the internal facade. Reach it through existing package-private code. Do not make it public to call it from a new type.

Versions are owned by release-please (`java-yoshi`). Do not edit version numbers, `{x-version-update:...}` markers, `Version.java`, or `CHANGELOG.md` by hand. Dependency bumps are owned by Renovate. Do not change dependency versions as part of an unrelated change.

## Setup

- Use the repo Maven wrapper (`./mvnw`) from the repository root. Do not require a preinstalled `mvn`. `./mvnw` downloads the pinned Maven.
- JDK 8 is the language level. CI also builds on 11, 17, 21, and 25.
- These compile on JDK 11+ and fail the JDK 8 job: `var`, records, text blocks, switch expressions, `List.of` / `Set.of` / `Map.of`, `Stream.toList()`, `String.isBlank` / `strip` / `repeat`, `Optional.isEmpty`, `Objects.requireNonNullElse`. Use `Arrays.asList`, `Collections.emptyList()`, and Guava only where the surrounding file already does.

## Build and test

Run these from the repository root. The scripts `cd` to the root themselves.

```bash
./mvnw -B -ntp -DskipTests -Dclirr.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true install
scripts/format.sh            # google-java-format
scripts/lint.sh              # fmt check only
scripts/test_units.sh        # unit tests plus 75% JaCoCo line coverage
scripts/check_dependencies.sh
scripts/check_clirr.sh       # binary compatibility of the public API
```

For a single unit test:

```bash
./mvnw -B -ntp -Dclirr.skip=true -Denforcer.skip=true -Dtest=ConnectorConfigTest test
```

What CI runs on a pull request (`.github/workflows/ci.yaml`):

- `scripts/test_units.sh` on Linux for JDK 8, 11, 17, 21, and 25, and on Windows for JDK 8 (`scripts/test_units.bat` calls the same shell script).
- `scripts/check_dependencies.sh` on those same JDK versions. It runs `dependency:analyze -DfailOnWarning=true`. An unused or undeclared dependency fails the build.
- `scripts/lint.sh` on JDK 21. This is the formatter check, not Error Prone.
- `scripts/check_clirr.sh` on JDK 8.

Error Prone is the parent `java21` profile (`failOnWarning`). It runs only when the compiler JDK is 21 or newer, including the JDK 21 and 25 unit-test jobs. A clean JDK 8 compile and a green `scripts/lint.sh` do not exercise it. Compile once on JDK 21 or newer before finishing.

`scripts/test_system.sh` and `scripts/test_graalvm.sh` need a live AlloyDB instance. CI runs them on self-hosted runners and skips them for pull requests from forks. Do not run them unless Application Default Credentials and the variables in `.envrc.example` are already set:

- `ALLOYDB_DB`, `ALLOYDB_USER`, `ALLOYDB_PASS`
- `ALLOYDB_INSTANCE_NAME`, `ALLOYDB_INSTANCE_IP`
- `ALLOYDB_IAM_USER`, `ALLOYDB_IMPERSONATED_USER`, `ALLOYDB_PSC_INSTANCE_URI`

## Code style

- Format with `scripts/format.sh`. The check is `com.spotify.fmt:fmt-maven-plugin` (Google Java Style). Do not hand-format or introduce a second formatter.
- Every new Java, shell, XML, and YAML source file needs the Apache 2.0 header used by neighboring files of that type (`Copyright <year> Google LLC`).
- Match the file you are editing: immutable value objects, private constructors, and `Builder` with `with*` methods. Use `com.google.common.base.Objects` or `java.util.Objects` for `equals` and `hashCode`, whichever that class already uses.
- Tests use JUnit 4 (`org.junit.Test`) and Truth (`assertThat`). Do not add JUnit 5 or another assertion library.
- Log with SLF4J (`LoggerFactory.getLogger`). Do not use `System.out` or `java.util.logging`.
- Keep the PostgreSQL JDBC driver `provided`. Applications supply it. It is an `ignoredDependency` for `dependency:analyze`. Do not move it to `compile`.
- New helpers are package-private. The public types today are `SocketFactory`, `ConnectorConfig`, `ConnectorRegistry`, `AuthType`, `IpType`, `RefreshStrategy`, `ConnectionInfoRepositoryFactory`, and `LazyConnectionInfoCache`. Do not add another public type to reach internal code.

## Testing

- Unit tests sit next to the class they cover and are named `*Test`. They must not contact AlloyDB, the metadata server, or the network.
- Reuse the existing fakes: `StubCredentialFactory`, `StubConnectionInfoCache`, `StubConnectionInfoRepositoryFactory`, `InMemoryConnectionInfoRepo`, `MockAlloyDBAdminGrpc`, and `FakeSslServer`.
- `InternalConnectorRegistry` is a process-wide enum. Tests replace collaborators through its `@VisibleForTesting` methods. Do not widen visibility so a test can reach a field.
- Integration tests are named `IT*`. They run only under `-Penable-integration-tests`.
- Add or update a unit test for every behavior change, including bug fixes.
- `scripts/test_units.sh` fails when JaCoCo line coverage drops below 75%. Do not lower that threshold.
- Native-image code under `com.google.cloud.alloydb.nativeimage` is excluded from the coverage run. Do not remove that exclude.

## Public API and compatibility

Clirr checks only these types. Treat them as stable binary API:

- `com.google.cloud.alloydb.ConnectorConfig`
- `com.google.cloud.alloydb.ConnectorRegistry`
- `com.google.cloud.alloydb.SocketFactory`

Do not remove methods, change signatures, or narrow visibility on those types in a non-major change. Run `scripts/check_clirr.sh` after any edit to them.

The other public types above are still user-facing even though Clirr does not scan them. A signature change there is a breaking change for anyone who compiled against it.

JDBC property names and `ConnectorConfig.Builder` methods are user-facing. Document new ones in `docs/configuration.md` and `docs/jdbc.md`.

## Security

- Do not commit credentials, `.envrc`, service-account JSON, or real instance URIs. `.envrc.example` stays placeholder-only.
- Do not log access tokens, passwords, private keys, or certificate private material.
- TLS is `TLSv1.3` inside `ConnectionSocket`, with the client certificate from the connector key pair. Do not weaken TLS, disable certificate checks, or accept plaintext connections.
- Metrics must not block a connection. `MetricRecorderFactory` returns `NullMetricRecorder` when metrics are disabled or when the Cloud Monitoring exporter fails to initialize, and it catches `Throwable` so a missing class on a shaded classpath does not fail the dial. Keep that fallback.

## Pull requests

- All changes go through a GitHub pull request. A Contributor License Agreement is required (https://cla.developers.google.com/).
- Commit subjects follow the existing Conventional Commits style (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`). Release-please reads those subjects.
- Describe the user-visible behavior change. Link the issue when there is one.
- Do not commit generated `target/` output or Maven wrapper downloads.
