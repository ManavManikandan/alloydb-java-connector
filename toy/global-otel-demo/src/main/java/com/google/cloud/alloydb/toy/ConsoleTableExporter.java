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

package com.google.cloud.alloydb.toy;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A console exporter meant for humans.
 *
 * <p>The SDK's {@code LoggingMetricExporter} prints each metric as one very long {@code toString()}
 * line. This exporter prints the same data as one small table per metric instead:
 *
 * <pre>
 * === metrics at 14:31:13 (running totals since start) ===
 *
 * alloydb.connector.dials [{dial}] - Number of attempts to dial an AlloyDB instance.
 *   instance     auth_type  is_cache_hit  status   total
 *   my-instance  password   false         success      1
 *   my-instance  password   true          success     19
 * </pre>
 *
 * <p>Like every OpenTelemetry exporter, it never sees the connector directly. A {@link
 * io.opentelemetry.sdk.metrics.export.PeriodicMetricReader} wakes up on a timer, collects whatever
 * the instruments recorded, and hands that batch to {@link #export}.
 *
 * <p>Only the connector's metrics are printed. Metrics from any other meter, such as the SDK's own
 * {@code otel.sdk.*} metrics, are skipped.
 */
final class ConsoleTableExporter implements MetricExporter {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  // The real connector's instrumentation scope. Anything else, such as otel.sdk.*, is skipped.
  static final String CONNECTOR_SCOPE = "com.google.cloud.alloydb";

  // These do not vary within one demo run, so a column for each would only add noise.
  private static final Set<String> HIDDEN_KEYS =
      new HashSet<>(
          Arrays.asList(
              "connector_type", "alloydb.project_id", "alloydb.location", "alloydb.cluster_id"));
  private static final String INSTANCE_KEY = "alloydb.instance_id";

  private final PrintStream out;

  ConsoleTableExporter() {
    this(System.out);
  }

  ConsoleTableExporter(PrintStream out) {
    this.out = out;
  }

  /** Called by the reader once per interval with every metric collected in that interval. */
  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {
    out.print(format(metrics, Instant.now()));
    out.flush();
    return CompletableResultCode.ofSuccess();
  }

  /** Renders one export batch as text. Kept separate from {@link #export} so tests can call it. */
  static String format(Collection<MetricData> metrics, Instant now) {
    // Sort by name so the metrics appear in the same order on every export.
    List<MetricData> sorted = new ArrayList<>(metrics);
    sorted.sort(Comparator.comparing(MetricData::getName));

    StringBuilder sb = new StringBuilder();
    sb.append("\n=== metrics at ")
        .append(TIME.format(now))
        .append(" (running totals since start) ===\n");
    for (MetricData metric : sorted) {
      if (!metric.getInstrumentationScopeInfo().getName().equals(CONNECTOR_SCOPE)) {
        continue;
      }
      // Title line: the metric name, its unit if it has one, and its description.
      sb.append('\n').append(metric.getName());
      if (!metric.getUnit().isEmpty()) {
        sb.append(" [").append(metric.getUnit()).append(']');
      }
      if (!metric.getDescription().isEmpty()) {
        sb.append(" - ").append(metric.getDescription());
      }
      sb.append('\n');
      appendTable(sb, metric);
    }
    return sb.toString();
  }

  /**
   * Prints one row per data point. A data point is one unique combination of attribute values, for
   * example (instance=my-instance, status=success, is_cache_hit=true).
   */
  private static void appendTable(StringBuilder sb, MetricData metric) {
    // Step 1: pull each point's attributes and value cells out of the metric. Each metric type
    // stores its points differently, so there is one case per type.
    List<String> valueHeaders = new ArrayList<>();
    List<Attributes> pointAttributes = new ArrayList<>();
    List<List<String>> pointValues = new ArrayList<>();
    switch (metric.getType()) {
      case LONG_SUM:
        // Monotonic sums only go up (counters). Non-monotonic ones go up and down
        // (UpDownCounters, such as open connections), so the value is a current level.
        valueHeaders.add(metric.getLongSumData().isMonotonic() ? "total" : "current");
        for (LongPointData p : metric.getLongSumData().getPoints()) {
          pointAttributes.add(p.getAttributes());
          pointValues.add(Collections.singletonList(formatLong(p.getValue())));
        }
        break;
      case DOUBLE_SUM:
        valueHeaders.add(metric.getDoubleSumData().isMonotonic() ? "total" : "current");
        for (DoublePointData p : metric.getDoubleSumData().getPoints()) {
          pointAttributes.add(p.getAttributes());
          pointValues.add(Collections.singletonList(formatDouble(p.getValue())));
        }
        break;
      case LONG_GAUGE:
        valueHeaders.add("value");
        for (LongPointData p : metric.getLongGaugeData().getPoints()) {
          pointAttributes.add(p.getAttributes());
          pointValues.add(Collections.singletonList(formatLong(p.getValue())));
        }
        break;
      case DOUBLE_GAUGE:
        valueHeaders.add("value");
        for (DoublePointData p : metric.getDoubleGaugeData().getPoints()) {
          pointAttributes.add(p.getAttributes());
          pointValues.add(Collections.singletonList(formatDouble(p.getValue())));
        }
        break;
      case HISTOGRAM:
        // A histogram keeps a count, a sum, a min, a max and bucket counts. The summary
        // statistics are enough to read at a glance, so the buckets are left out.
        valueHeaders.addAll(Arrays.asList("count", "avg", "min", "max"));
        for (HistogramPointData p : metric.getHistogramData().getPoints()) {
          pointAttributes.add(p.getAttributes());
          long count = p.getCount();
          pointValues.add(
              Arrays.asList(
                  formatLong(count),
                  count == 0 ? "-" : formatDouble(p.getSum() / count),
                  p.hasMin() ? formatDouble(p.getMin()) : "-",
                  p.hasMax() ? formatDouble(p.getMax()) : "-"));
        }
        break;
      default:
        sb.append("  (").append(metric.getType()).append(" metrics are not shown)\n");
        return;
    }
    if (pointAttributes.isEmpty()) {
      sb.append("  (no data yet)\n");
      return;
    }

    // Step 2: decide the attribute columns. Use every attribute key seen on any point, in
    // alphabetical order, except that the instance goes first because it is what you scan by.
    TreeSet<String> keys = new TreeSet<>();
    for (Attributes attrs : pointAttributes) {
      attrs.forEach((k, v) -> keys.add(k.getKey()));
    }
    keys.removeAll(HIDDEN_KEYS);
    List<String> attributeKeys = new ArrayList<>();
    if (keys.remove(INSTANCE_KEY)) {
      attributeKeys.add(INSTANCE_KEY);
    }
    attributeKeys.addAll(keys);

    // Step 3: build the header row and one text row per point.
    List<String> header = new ArrayList<>();
    for (String key : attributeKeys) {
      header.add(key.equals(INSTANCE_KEY) ? "instance" : key);
    }
    header.addAll(valueHeaders);

    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < pointAttributes.size(); i++) {
      Map<String, String> byKey = new LinkedHashMap<>();
      pointAttributes.get(i).forEach((k, v) -> byKey.put(k.getKey(), String.valueOf(v)));
      List<String> row = new ArrayList<>();
      for (String key : attributeKeys) {
        row.add(byKey.getOrDefault(key, "-"));
      }
      row.addAll(pointValues.get(i));
      rows.add(row);
    }
    // Sort rows by their attribute cells so each instance's rows sit together.
    int attributeColumns = attributeKeys.size();
    rows.sort(
        (a, b) ->
            String.join(" ", a.subList(0, attributeColumns))
                .compareTo(String.join(" ", b.subList(0, attributeColumns))));

    // Step 4: size each column to its widest cell, then print. Text is left-aligned and
    // numbers are right-aligned so the digits line up.
    int[] widths = new int[header.size()];
    for (int c = 0; c < header.size(); c++) {
      widths[c] = header.get(c).length();
      for (List<String> row : rows) {
        widths[c] = Math.max(widths[c], row.get(c).length());
      }
    }
    appendRow(sb, header, widths, attributeColumns);
    for (List<String> row : rows) {
      appendRow(sb, row, widths, attributeColumns);
    }
  }

  /** Prints one padded row. Columns at or after {@code firstNumberColumn} are right-aligned. */
  private static void appendRow(
      StringBuilder sb, List<String> cells, int[] widths, int firstNumberColumn) {
    StringBuilder line = new StringBuilder("  ");
    for (int c = 0; c < cells.size(); c++) {
      String cell = cells.get(c);
      String padding = repeat(' ', widths[c] - cell.length());
      line.append(c < firstNumberColumn ? cell + padding : padding + cell);
      line.append("  ");
    }
    // Trim the padding after the last column so lines don't end in spaces.
    sb.append(line.toString().replaceAll("\\s+$", "")).append('\n');
  }

  // Thousands separators make byte counts such as 91,513,895 readable.
  private static String formatLong(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  private static String formatDouble(double value) {
    return String.format(Locale.ROOT, "%,.1f", value);
  }

  private static String repeat(char c, int n) {
    char[] chars = new char[Math.max(0, n)];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  /** Nothing is buffered between exports, so there is nothing to flush. */
  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  /** Nothing to release: the exporter only writes to a stream it does not own. */
  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  /**
   * CUMULATIVE means every export shows totals since the app started, which matches the session
   * count the app prints. DELTA would show only what happened since the last export.
   */
  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return AggregationTemporality.CUMULATIVE;
  }
}
