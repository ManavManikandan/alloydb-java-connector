/*
 * Copyright 2024 Google LLC
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

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.rpc.Code;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConnectorTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";
  private static final String SERVER_MESSAGE = "HELLO";
  private static final String ERROR_MESSAGE_NOT_FOUND = "Resource 'instance' was not found";
  private static final String USER_AGENT = "unit tests";
  private static final String ERROR_MESSAGE_MDX_FAILED = "metadata exchange rejected";

  static ListeningScheduledExecutorService defaultExecutor;
  private static FakeSslServer sslServer;

  @BeforeClass
  public static void beforeClass() throws Exception {
    defaultExecutor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
    sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start("127.0.0.1");
  }

  @After
  public void after() {
    // The SSL server is shared by every test in this class, so undo any per-test configuration.
    sslServer.succeedMetadataExchange();
  }

  @AfterClass
  public static void afterClass() {
    defaultExecutor.shutdownNow();
    sslServer.stop();
  }

  @Test
  public void create_successfulPrivateConnection() throws IOException {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_throwsTerminalException() {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.NOT_FOUND.getNumber(), ERROR_MESSAGE_NOT_FOUND);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    TerminalException ex = assertThrows(TerminalException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_NOT_FOUND);
  }

  @Test
  public void connect_forcesRefresh_whenMetadataExchangeFails() throws Exception {
    sslServer.failMetadataExchange(ERROR_MESSAGE_MDX_FAILED);
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    StubConnectionInfoCache stubConnectionInfoCache = newStubConnectionInfoCache("127.0.0.1");
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache));

    MetadataExchangeException ex =
        assertThrows(MetadataExchangeException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_MDX_FAILED);
    // Stale connection info is a likely cause of a failed metadata exchange, so the next
    // attempt should use refreshed connection info.
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isTrue();
  }

  @Test
  public void connect_doesNotForceRefresh_whenInstanceHasNoMatchingAddress() throws Exception {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    // An instance with no private IP address, when the config asks for one.
    StubConnectionInfoCache stubConnectionInfoCache = newStubConnectionInfoCache("");
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache));

    UserConfigException ex =
        assertThrows(UserConfigException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains("does not have an address matching type");
    // A misconfigured connection fails the same way every time, so a refresh would not help.
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isFalse();
  }

  @Test
  public void connect_withoutTelemetry_returnsUnwrappedSocket() throws IOException {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    assertThat(socket).isNotInstanceOf(MeteredSocket.class);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void connect_withMeterProvider_recordsToClientProvider() throws IOException {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectorConfig connectorConfig =
        new ConnectorConfig.Builder().withMeterProvider(meterProvider).build();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME))
            .withConnectorConfig(connectorConfig)
            .build();
    Connector connector = newConnector(connectorConfig, mock);

    Socket socket = connector.connect(config);
    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);

    assertThat(socket).isInstanceOf(MeteredSocket.class);
    Map<String, MetricData> metrics = collectByName(reader);
    assertThat(metrics.keySet())
        .containsAtLeast(
            ClientMetricRecorder.DIALS,
            ClientMetricRecorder.DIAL_DURATION,
            ClientMetricRecorder.OPEN_CONNECTIONS,
            ClientMetricRecorder.BYTES_RECEIVED);
    LongPointData dial = onlyLongPoint(metrics.get(ClientMetricRecorder.DIALS));
    assertThat(dial.getValue()).isEqualTo(1);
    assertThat(dial.getAttributes().get(ClientMetricRecorder.STATUS_KEY))
        .isEqualTo(TelemetryAttributes.DIAL_SUCCESS);
    assertThat(dial.getAttributes().get(ClientMetricRecorder.INSTANCE_ID_KEY))
        .isEqualTo("<INSTANCE>");
    assertThat(onlyLongPoint(metrics.get(ClientMetricRecorder.OPEN_CONNECTIONS)).getValue())
        .isEqualTo(1);
    assertThat(onlyLongPoint(metrics.get(ClientMetricRecorder.BYTES_RECEIVED)).getValue())
        .isAtLeast(SERVER_MESSAGE.length());

    socket.close();

    assertThat(
            onlyLongPoint(collectByName(reader).get(ClientMetricRecorder.OPEN_CONNECTIONS))
                .getValue())
        .isEqualTo(0);
  }

  @Test
  public void connect_withMeterProvider_recordsFailedDial() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.NOT_FOUND.getNumber(), ERROR_MESSAGE_NOT_FOUND);
    ConnectorConfig connectorConfig =
        new ConnectorConfig.Builder().withMeterProvider(meterProvider).build();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME))
            .withConnectorConfig(connectorConfig)
            .build();
    Connector connector = newConnector(connectorConfig, mock);

    assertThrows(TerminalException.class, () -> connector.connect(config));

    Map<String, MetricData> metrics = collectByName(reader);
    LongPointData dial = onlyLongPoint(metrics.get(ClientMetricRecorder.DIALS));
    assertThat(dial.getAttributes().get(ClientMetricRecorder.STATUS_KEY))
        .isEqualTo(TelemetryAttributes.DIAL_CACHE_ERROR);
    assertThat(metrics).doesNotContainKey(ClientMetricRecorder.DIAL_DURATION);
    assertThat(metrics).doesNotContainKey(ClientMetricRecorder.OPEN_CONNECTIONS);
  }

  @Test
  public void close_doesNotShutDownClientProvider() throws IOException {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectorConfig connectorConfig =
        new ConnectorConfig.Builder().withMeterProvider(meterProvider).build();
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME))
            .withConnectorConfig(connectorConfig)
            .build();
    Connector connector = newConnector(connectorConfig, mock);
    connector.connect(config).close();

    connector.close();

    // The application's provider must still be collecting after the connector is gone.
    meterProvider.get("application").counterBuilder("app.requests").build().add(1);
    assertThat(collectByName(reader)).containsKey("app.requests");
  }

  private static Map<String, MetricData> collectByName(InMemoryMetricReader reader) {
    Map<String, MetricData> byName = new HashMap<>();
    for (MetricData metric : reader.collectAllMetrics()) {
      byName.put(metric.getName(), metric);
    }
    return byName;
  }

  private static LongPointData onlyLongPoint(MetricData metric) {
    assertThat(metric.getLongSumData().getPoints()).hasSize(1);
    return metric.getLongSumData().getPoints().iterator().next();
  }

  private StubConnectionInfoCache newStubConnectionInfoCache(String ipAddress) throws Exception {
    KeyPair clientConnectorKeyPair = TestCertificates.INSTANCE.getClientKey();
    X509Certificate clientCertificate =
        TestCertificates.INSTANCE.getEphemeralCertificate(
            clientConnectorKeyPair.getPublic(), Instant.now().plus(1, ChronoUnit.HOURS));
    StubConnectionInfoCache stubConnectionInfoCache = new StubConnectionInfoCache();
    stubConnectionInfoCache.setConnectionInfo(
        new ConnectionInfo(
            ipAddress,
            ipAddress,
            "abcde.12345.us-central1.alloydb.goog",
            "some-instance",
            clientCertificate,
            // The ephemeral certificate leads the chain, as it does in a real connection.
            Arrays.asList(
                clientCertificate,
                TestCertificates.INSTANCE.getIntermediateCertificate(),
                TestCertificates.INSTANCE.getRootCertificate()),
            TestCertificates.INSTANCE.getRootCertificate()));
    return stubConnectionInfoCache;
  }

  private Connector newConnector(ConnectorConfig config, MockAlloyDBAdminGrpc mock) {
    return newConnector(
        config, mock, new DefaultConnectionInfoCacheFactory(RefreshStrategy.REFRESH_AHEAD));
  }

  private Connector newConnector(
      ConnectorConfig config,
      MockAlloyDBAdminGrpc mock,
      ConnectionInfoCacheFactory connectionInfoCacheFactory) {
    CredentialFactoryProvider stubCredentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory());
    CredentialFactory instanceCredentialFactory =
        stubCredentialFactoryProvider.getInstanceCredentialFactory(config);
    ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory =
        new StubConnectionInfoRepositoryFactory(defaultExecutor, mock);
    ConnectionInfoRepository connectionInfoRepository =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
    AccessTokenSupplier accessTokenSupplier =
        new DefaultAccessTokenSupplier(instanceCredentialFactory);

    return new Connector(
        config,
        defaultExecutor,
        connectionInfoRepository,
        TestCertificates.INSTANCE.getClientKey(),
        connectionInfoCacheFactory,
        new ConcurrentHashMap<>(),
        accessTokenSupplier,
        USER_AGENT);
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }
}
