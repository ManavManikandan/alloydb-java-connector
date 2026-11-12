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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A socket that records the bytes it carries and the end of its connection.
 *
 * <p>Every call is delegated to the wrapped, already connected socket. Byte counts are of the
 * application data above TLS. Closing the socket, or either of its streams, records the closed
 * connection exactly once.
 */
class MeteredSocket extends Socket {

  private final Socket delegate;
  private final MetricRecorder metricRecorder;
  private final TelemetryAttributes connectionAttributes;
  private final AtomicBoolean closeRecorded = new AtomicBoolean();

  MeteredSocket(
      Socket delegate, MetricRecorder metricRecorder, TelemetryAttributes connectionAttributes) {
    this.delegate = delegate;
    this.metricRecorder = metricRecorder;
    this.connectionAttributes = connectionAttributes;
  }

  private void recordClosed() {
    if (closeRecorded.compareAndSet(false, true)) {
      metricRecorder.recordClosedConnection(connectionAttributes);
    }
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new CountingInputStream(delegate.getInputStream());
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return new CountingOutputStream(delegate.getOutputStream());
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      delegate.close();
    } finally {
      recordClosed();
    }
  }

  @Override
  public void connect(SocketAddress endpoint) throws IOException {
    delegate.connect(endpoint);
  }

  @Override
  public void connect(SocketAddress endpoint, int timeout) throws IOException {
    delegate.connect(endpoint, timeout);
  }

  @Override
  public void bind(SocketAddress bindpoint) throws IOException {
    delegate.bind(bindpoint);
  }

  @Override
  public InetAddress getInetAddress() {
    return delegate.getInetAddress();
  }

  @Override
  public InetAddress getLocalAddress() {
    return delegate.getLocalAddress();
  }

  @Override
  public int getPort() {
    return delegate.getPort();
  }

  @Override
  public int getLocalPort() {
    return delegate.getLocalPort();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return delegate.getRemoteSocketAddress();
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return delegate.getLocalSocketAddress();
  }

  @Override
  public SocketChannel getChannel() {
    return delegate.getChannel();
  }

  @Override
  public void setTcpNoDelay(boolean on) throws SocketException {
    delegate.setTcpNoDelay(on);
  }

  @Override
  public boolean getTcpNoDelay() throws SocketException {
    return delegate.getTcpNoDelay();
  }

  @Override
  public void setSoLinger(boolean on, int linger) throws SocketException {
    delegate.setSoLinger(on, linger);
  }

  @Override
  public int getSoLinger() throws SocketException {
    return delegate.getSoLinger();
  }

  @Override
  public void sendUrgentData(int data) throws IOException {
    delegate.sendUrgentData(data);
  }

  @Override
  public void setOOBInline(boolean on) throws SocketException {
    delegate.setOOBInline(on);
  }

  @Override
  public boolean getOOBInline() throws SocketException {
    return delegate.getOOBInline();
  }

  @Override
  public synchronized void setSoTimeout(int timeout) throws SocketException {
    delegate.setSoTimeout(timeout);
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    return delegate.getSoTimeout();
  }

  @Override
  public synchronized void setSendBufferSize(int size) throws SocketException {
    delegate.setSendBufferSize(size);
  }

  @Override
  public synchronized int getSendBufferSize() throws SocketException {
    return delegate.getSendBufferSize();
  }

  @Override
  public synchronized void setReceiveBufferSize(int size) throws SocketException {
    delegate.setReceiveBufferSize(size);
  }

  @Override
  public synchronized int getReceiveBufferSize() throws SocketException {
    return delegate.getReceiveBufferSize();
  }

  @Override
  public void setKeepAlive(boolean on) throws SocketException {
    delegate.setKeepAlive(on);
  }

  @Override
  public boolean getKeepAlive() throws SocketException {
    return delegate.getKeepAlive();
  }

  @Override
  public void setTrafficClass(int tc) throws SocketException {
    delegate.setTrafficClass(tc);
  }

  @Override
  public int getTrafficClass() throws SocketException {
    return delegate.getTrafficClass();
  }

  @Override
  public void setReuseAddress(boolean on) throws SocketException {
    delegate.setReuseAddress(on);
  }

  @Override
  public boolean getReuseAddress() throws SocketException {
    return delegate.getReuseAddress();
  }

  @Override
  public void shutdownInput() throws IOException {
    delegate.shutdownInput();
  }

  @Override
  public void shutdownOutput() throws IOException {
    delegate.shutdownOutput();
  }

  @Override
  public boolean isConnected() {
    return delegate.isConnected();
  }

  @Override
  public boolean isBound() {
    return delegate.isBound();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public boolean isInputShutdown() {
    return delegate.isInputShutdown();
  }

  @Override
  public boolean isOutputShutdown() {
    return delegate.isOutputShutdown();
  }

  @Override
  public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

  private final class CountingInputStream extends FilterInputStream {

    CountingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b != -1) {
        metricRecorder.recordBytesRx(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      if (n > 0) {
        metricRecorder.recordBytesRx(n);
      }
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = in.skip(n);
      if (skipped > 0) {
        metricRecorder.recordBytesRx(skipped);
      }
      return skipped;
    }

    @Override
    public void close() throws IOException {
      try {
        in.close();
      } finally {
        if (delegate.isClosed()) {
          recordClosed();
        }
      }
    }
  }

  private final class CountingOutputStream extends FilterOutputStream {

    CountingOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      metricRecorder.recordBytesTx(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      if (len > 0) {
        metricRecorder.recordBytesTx(len);
      }
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        if (delegate.isClosed()) {
          recordClosed();
        }
      }
    }
  }
}
