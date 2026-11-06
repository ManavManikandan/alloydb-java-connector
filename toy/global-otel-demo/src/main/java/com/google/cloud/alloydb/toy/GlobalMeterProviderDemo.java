/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb.toy;

import com.google.cloud.alloydb.ConnectorRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * Shows the real connector reporting through the application's global {@code MeterProvider},
 * against a real AlloyDB instance and a database other than {@code postgres}.
 *
 * <p>This class is the application. It installs the global provider, then uses plain JDBC. Nothing
 * passes the provider to the connector: the connector finds it on the first dial. The only SQL is
 * read-only, so the demo leaves the database unchanged.
 */
public final class GlobalMeterProviderDemo {

  // Enough rows that bytes.received clearly reflects the result sets.
  private static final String WORKLOAD_SQL =
      "SELECT md5(i::text) FROM generate_series(1, 2000) AS i";

  private GlobalMeterProviderDemo() {}

  public static void main(String[] args) throws Exception {
    Settings settings = Settings.fromEnvironment(System.getenv());
    settings.validate();

    // Step 1: the application installs the global provider, before its first connection.
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(
                PeriodicMetricReader.builder(new ConsoleTableExporter())
                    .setInterval(Duration.ofSeconds(settings.exportIntervalSeconds))
                    .build())
            .build();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();
    System.out.println("[app] global MeterProvider registered");

    // Step 2: ordinary JDBC. Each session opens a new connection, so each one is a dial. The
    // first misses the connection info cache and the rest should hit it.
    long rows = 0;
    try {
      for (int session = 1; session <= settings.sessions; session++) {
        rows += runSession(settings, session);
        Thread.sleep(settings.pauseMillis);
      }
      System.out.printf("[app] finished: sessions=%d rows=%d%n", settings.sessions, rows);
    } finally {
      // Step 3: close connections first, then the SDK. Closing the SDK runs the final export. The
      // connector never flushes or shuts down the provider because the application owns it.
      ConnectorRegistry.shutdown();
      sdk.close();
    }
  }

  /** Opens one connection, reads from the database and returns the number of rows read. */
  private static long runSession(Settings settings, int session) throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.connectionProperties())) {
      if (session == 1) {
        printDatabaseIdentity(connection);
      }
      long rows = 0;
      try (PreparedStatement statement = connection.prepareStatement(WORKLOAD_SQL);
          ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          rows++;
        }
      }
      System.out.printf("[app] session %d read %d rows%n", session, rows);
      return rows;
    }
  }

  /** Prints what the server says it is, so the output proves which database answered. */
  private static void printDatabaseIdentity(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT current_database(), current_user, version()");
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      System.out.printf(
          "[app] connected to database=%s as user=%s (%s)%n",
          resultSet.getString(1), resultSet.getString(2), resultSet.getString(3));
    }
  }

  /** The demo's configuration, read from the environment so no credential is on a command line. */
  static final class Settings {
    final String instanceName;
    final String database;
    final String user;
    final String password;
    final String ipType;
    final int sessions;
    final int exportIntervalSeconds;
    final long pauseMillis;

    private Settings(
        String instanceName,
        String database,
        String user,
        String password,
        String ipType,
        int sessions,
        int exportIntervalSeconds,
        long pauseMillis) {
      this.instanceName = instanceName;
      this.database = database;
      this.user = user;
      this.password = password;
      this.ipType = ipType;
      this.sessions = sessions;
      this.exportIntervalSeconds = exportIntervalSeconds;
      this.pauseMillis = pauseMillis;
    }

    static Settings fromEnvironment(java.util.Map<String, String> env) {
      return new Settings(
          env.get("ALLOYDB_INSTANCE_NAME"),
          env.get("ALLOYDB_DEMO_DB"),
          env.get("ALLOYDB_USER"),
          env.get("ALLOYDB_PASS"),
          env.getOrDefault("ALLOYDB_DEMO_IP_TYPE", "PUBLIC"),
          Integer.parseInt(env.getOrDefault("ALLOYDB_DEMO_SESSIONS", "20")),
          Integer.parseInt(env.getOrDefault("ALLOYDB_DEMO_EXPORT_SECONDS", "5")),
          500L);
    }

    /** Stops the demo early with a clear message rather than a confusing connection failure. */
    void validate() {
      requireSet("ALLOYDB_INSTANCE_NAME", instanceName);
      requireSet("ALLOYDB_DEMO_DB", database);
      requireSet("ALLOYDB_USER", user);
      requireSet("ALLOYDB_PASS", password);
      if (database.equalsIgnoreCase("postgres")) {
        throw new IllegalArgumentException(
            "ALLOYDB_DEMO_DB must name your own database, not the default postgres database.");
      }
    }

    private static void requireSet(String name, String value) {
      if (value == null || value.isEmpty()) {
        throw new IllegalArgumentException(name + " must be set.");
      }
    }

    String jdbcUrl() {
      return String.format("jdbc:postgresql:///%s", database);
    }

    Properties connectionProperties() {
      Properties properties = new Properties();
      properties.setProperty("user", user);
      properties.setProperty("password", password);
      properties.setProperty("socketFactory", "com.google.cloud.alloydb.SocketFactory");
      properties.setProperty("alloydbInstanceName", instanceName);
      properties.setProperty("alloydbIpType", ipType);
      // Keep Cloud Monitoring out of the picture: the tables come from the global provider alone.
      properties.setProperty("alloydbEnableBuiltInTelemetry", "false");
      return properties;
    }
  }
}
