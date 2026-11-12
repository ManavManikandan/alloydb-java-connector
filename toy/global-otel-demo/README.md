# Global MeterProvider demo

This demo runs the real AlloyDB Java Connector against a real instance and a database of
your own. It shows connector metrics arriving through the application's global
OpenTelemetry `MeterProvider`. It is not part of the root build.

The application registers the provider with `buildAndRegisterGlobal()` and then uses plain
JDBC. It never passes the provider to the connector, which finds it on the first dial.

These are **client-side connector metrics**: dials, dial latency, open connections, bytes
and refreshes for the connection to your database. They are not server metrics such as CPU
or query statistics.

The only SQL is read-only, so the demo leaves your database unchanged. The console tables
come from the global provider alone, because the demo turns built-in Cloud Monitoring
export off.

## Run

You need Application Default Credentials and a login for your database. The instance must
be reachable from where you run the demo.

```bash
# From the repository root: install the connector, then build the demo.
./mvnw -B -ntp -DskipTests -Dclirr.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true install
./mvnw -B -ntp -f toy/global-otel-demo/pom.xml package

# ALLOYDB_INSTANCE_NAME, ALLOYDB_USER and ALLOYDB_PASS come from your environment.
source .envrc
export ALLOYDB_DEMO_DB=<your database>   # must not be postgres
java -jar toy/global-otel-demo/target/global-otel-demo.jar
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `ALLOYDB_DEMO_DB` | required | Database to connect to. `postgres` is rejected. |
| `ALLOYDB_DEMO_IP_TYPE` | `PUBLIC` | `PUBLIC`, `PRIVATE` or `PSC`. |
| `ALLOYDB_DEMO_SESSIONS` | `20` | Connections to open, one after another. |
| `ALLOYDB_DEMO_EXPORT_SECONDS` | `5` | Seconds between metric tables. |

## What to look for

- `[app] connected to database=...` is the server naming your database.
- `alloydb.connector.dials` should total the session count from `[app] finished`: one
  cache miss, then cache hits.
- `alloydb.connector.bytes.received` should grow with each session.
- `alloydb.connector.connections.open` should end at 0.

## Tests

```bash
./mvnw -B -ntp -f toy/global-otel-demo/pom.xml test
```

The tests need no instance.
