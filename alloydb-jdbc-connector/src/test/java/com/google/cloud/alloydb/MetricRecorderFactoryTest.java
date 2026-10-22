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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class MetricRecorderFactoryTest {

  private static final InstanceName INSTANCE_NAME =
      InstanceName.parse("projects/project/locations/region/clusters/cluster/instances/instance");

  @Test
  public void testDisabledReturnsNullRecorder() {
    MetricRecorder recorder =
        MetricRecorderFactory.newBuiltInMetricRecorder(
            false, "project", "region", "cluster", "instance", "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }

  @Test
  public void testExporterFailureFallsBackToNullRecorder() {
    // A blank project id makes the Cloud Monitoring exporter fail to initialize. The factory must
    // swallow that and hand back a no-op recorder, because telemetry never blocks a connection.
    MetricRecorder recorder =
        MetricRecorderFactory.newBuiltInMetricRecorder(
            true, "", "region", "cluster", "instance", "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }

  @Test
  public void testNothingConfiguredReturnsNullRecorder() {
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(false, null, INSTANCE_NAME, "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }

  @Test
  public void testClientOnlyIsAlwaysIsolated() {
    ClientMetricRecorder client = new ClientMetricRecorder(MeterProvider.noop());

    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(false, client, INSTANCE_NAME, "uid");

    assertThat(recorder).isInstanceOf(CompositeMetricRecorder.class);
    assertThat(((CompositeMetricRecorder) recorder).getDelegates()).hasSize(1);
    assertThat(recorder.isEnabled()).isTrue();
  }

  @Test
  public void testBuiltInFailureStillLeavesClientPath() {
    ClientMetricRecorder client = new ClientMetricRecorder(MeterProvider.noop());
    // A blank project id makes the Cloud Monitoring exporter fail to initialize.
    InstanceName blankProject = InstanceName.of("", "region", "cluster", "instance");

    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(true, client, blankProject, "uid");

    assertThat(recorder).isInstanceOf(CompositeMetricRecorder.class);
    assertThat(((CompositeMetricRecorder) recorder).getDelegates()).hasSize(1);
    assertThat(recorder.isEnabled()).isTrue();
  }

  @Test
  public void testCombineEmptyReturnsNullRecorder() {
    MetricRecorder recorder = MetricRecorderFactory.combine(Collections.emptyList(), true);

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
  }

  @Test
  public void testCombineSingleReturnsDelegateWhenNotIsolated() {
    MetricRecorder only = new FakeMetricRecorder();

    MetricRecorder recorder = MetricRecorderFactory.combine(Collections.singletonList(only), false);

    assertThat(recorder).isSameInstanceAs(only);
  }

  @Test
  public void testCombineManyReturnsComposite() {
    MetricRecorder recorder =
        MetricRecorderFactory.combine(
            Arrays.asList(new FakeMetricRecorder(), new FakeMetricRecorder()), false);

    assertThat(recorder).isInstanceOf(CompositeMetricRecorder.class);
    assertThat(((CompositeMetricRecorder) recorder).getDelegates()).hasSize(2);
  }

  @Test
  public void testNoMeterProviderReturnsNoClientRecorder() {
    assertThat(MetricRecorderFactory.newClientMetricRecorder(null)).isNull();
  }

  @Test
  public void testBrokenMeterProviderReturnsNoClientRecorder() {
    MeterProvider broken =
        name -> {
          throw new IllegalStateException("broken provider");
        };

    assertThat(MetricRecorderFactory.newClientMetricRecorder(broken)).isNull();
  }
}
