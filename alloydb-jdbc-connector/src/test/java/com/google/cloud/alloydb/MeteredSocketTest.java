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

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MeteredSocketTest {

  private static final byte[] SERVER_GREETING = "HELLO".getBytes(UTF_8);

  private ExecutorService executor;
  private ServerSocket serverSocket;
  private Future<Socket> accepted;
  private FakeMetricRecorder recorder;
  private MeteredSocket socket;

  @Before
  public void setUp() throws IOException {
    executor = Executors.newSingleThreadExecutor();
    serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    accepted =
        executor.submit(
            () -> {
              Socket s = serverSocket.accept();
              s.getOutputStream().write(SERVER_GREETING);
              s.getOutputStream().flush();
              return s;
            });
    Socket client = new Socket();
    client.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort()));
    recorder = new FakeMetricRecorder();
    socket = new MeteredSocket(client, recorder, TelemetryAttributes.forConnection(false));
  }

  @After
  public void tearDown() throws Exception {
    socket.close();
    accepted.get(5, TimeUnit.SECONDS).close();
    serverSocket.close();
    executor.shutdownNow();
  }

  @Test
  public void countsBytesReadAndWritten() throws Exception {
    InputStream in = socket.getInputStream();
    byte[] buf = new byte[SERVER_GREETING.length];
    int total = 0;
    while (total < buf.length) {
      total += in.read(buf, total, buf.length - total);
    }
    OutputStream out = socket.getOutputStream();
    out.write("abc".getBytes(UTF_8));
    out.write('d');
    out.flush();

    byte[] received = new byte[4];
    InputStream serverIn = accepted.get(5, TimeUnit.SECONDS).getInputStream();
    int got = 0;
    while (got < received.length) {
      got += serverIn.read(received, got, received.length - got);
    }

    assertThat(new String(buf, UTF_8)).isEqualTo("HELLO");
    assertThat(new String(received, UTF_8)).isEqualTo("abcd");
    long rx = 0;
    long tx = 0;
    for (String call : recorder.getCalls()) {
      if (call.startsWith("bytesRx ")) {
        rx += Long.parseLong(call.substring("bytesRx ".length()));
      } else if (call.startsWith("bytesTx ")) {
        tx += Long.parseLong(call.substring("bytesTx ".length()));
      }
    }
    assertThat(rx).isEqualTo(SERVER_GREETING.length);
    assertThat(tx).isEqualTo(4);
  }

  @Test
  public void singleByteReadIsCounted() throws Exception {
    int b = socket.getInputStream().read();

    assertThat(b).isEqualTo('H');
    assertThat(recorder.getCalls()).contains("bytesRx 1");
  }

  @Test
  public void recordsClosedConnectionExactlyOnce() throws IOException {
    socket.close();
    socket.close();

    assertThat(socket.isClosed()).isTrue();
    assertThat(Collections.frequency(recorder.getCalls(), "closedConnection")).isEqualTo(1);
  }

  @Test
  public void closingAStreamThatClosesTheSocketRecordsClosedConnection() throws IOException {
    // Closing a socket's stream closes the socket itself.
    socket.getOutputStream().close();
    socket.close();

    assertThat(socket.isClosed()).isTrue();
    assertThat(Collections.frequency(recorder.getCalls(), "closedConnection")).isEqualTo(1);
  }

  @Test
  public void closingTheInputStreamRecordsClosedConnection() throws IOException {
    socket.getInputStream().close();

    assertThat(socket.isClosed()).isTrue();
    assertThat(recorder.getCalls()).contains("closedConnection");
  }

  @Test
  public void delegatesSocketOptionsAndState() throws IOException {
    socket.setSoTimeout(1234);
    socket.setTcpNoDelay(true);
    socket.setKeepAlive(true);
    socket.setReceiveBufferSize(8192);
    socket.setSendBufferSize(8192);

    assertThat(socket.getSoTimeout()).isEqualTo(1234);
    assertThat(socket.getTcpNoDelay()).isTrue();
    assertThat(socket.getKeepAlive()).isTrue();
    assertThat(socket.getReceiveBufferSize()).isGreaterThan(0);
    assertThat(socket.getSendBufferSize()).isGreaterThan(0);
    assertThat(socket.isConnected()).isTrue();
    assertThat(socket.isBound()).isTrue();
    assertThat(socket.isClosed()).isFalse();
    assertThat(socket.isInputShutdown()).isFalse();
    assertThat(socket.isOutputShutdown()).isFalse();
    assertThat(socket.getPort()).isEqualTo(serverSocket.getLocalPort());
    assertThat(socket.getInetAddress()).isEqualTo(InetAddress.getLoopbackAddress());
    assertThat(socket.getLocalPort()).isGreaterThan(0);
    assertThat(socket.getLocalAddress()).isNotNull();
    assertThat(socket.getRemoteSocketAddress()).isNotNull();
    assertThat(socket.getLocalSocketAddress()).isNotNull();
    assertThat(socket.toString()).contains(String.valueOf(serverSocket.getLocalPort()));

    socket.shutdownOutput();
    assertThat(socket.isOutputShutdown()).isTrue();
    socket.shutdownInput();
    assertThat(socket.isInputShutdown()).isTrue();
    // Shutting down either direction does not close the connection.
    assertThat(recorder.getCalls()).doesNotContain("closedConnection");
  }
}
