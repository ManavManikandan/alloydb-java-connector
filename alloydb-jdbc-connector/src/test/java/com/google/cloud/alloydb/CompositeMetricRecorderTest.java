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

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CompositeMetricRecorderTest {

  private static final List<String> ALL_CALLS =
      Arrays.asList(
          "dialCount success",
          "dialLatency",
          "openConnection",
          "closedConnection",
          "bytesRx 7",
          "bytesTx 9",
          "refreshCount success",
          "shutdown");

  private static void recordEverything(MetricRecorder recorder) {
    recorder.recordDialCount(
        TelemetryAttributes.forDial(false, false, TelemetryAttributes.DIAL_SUCCESS));
    recorder.recordDialLatency(1.5);
    recorder.recordOpenConnection(TelemetryAttributes.forConnection(false));
    recorder.recordClosedConnection(TelemetryAttributes.forConnection(false));
    recorder.recordBytesRx(7);
    recorder.recordBytesTx(9);
    recorder.recordRefreshCount(TelemetryAttributes.REFRESH_AHEAD_SUCCEEDED);
    recorder.shutdown();
  }

  @Test
  public void deliversEveryEventToEveryDelegate() {
    FakeMetricRecorder first = new FakeMetricRecorder();
    FakeMetricRecorder second = new FakeMetricRecorder();

    recordEverything(new CompositeMetricRecorder(Arrays.asList(first, second)));

    assertThat(first.getCalls()).containsExactlyElementsIn(ALL_CALLS).inOrder();
    assertThat(second.getCalls()).containsExactlyElementsIn(ALL_CALLS).inOrder();
  }

  @Test
  public void throwingDelegateDoesNotStopTheOthers() {
    FakeMetricRecorder throwing = new FakeMetricRecorder(true, true);
    FakeMetricRecorder healthy = new FakeMetricRecorder();

    // Nothing may escape the composite, including from shutdown.
    recordEverything(new CompositeMetricRecorder(Arrays.asList(throwing, healthy)));

    assertThat(throwing.getCalls()).containsExactlyElementsIn(ALL_CALLS).inOrder();
    assertThat(healthy.getCalls()).containsExactlyElementsIn(ALL_CALLS).inOrder();
  }

  @Test
  public void isEnabledWhenAnyDelegateIsEnabled() {
    MetricRecorder recorder =
        new CompositeMetricRecorder(
            Arrays.asList(new NullMetricRecorder(), new FakeMetricRecorder()));

    assertThat(recorder.isEnabled()).isTrue();
  }

  @Test
  public void isDisabledWhenEveryDelegateIsDisabled() {
    MetricRecorder recorder =
        new CompositeMetricRecorder(
            Arrays.asList(new NullMetricRecorder(), new FakeMetricRecorder(false, false)));

    assertThat(recorder.isEnabled()).isFalse();
  }
}
