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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.toy.GlobalMeterProviderDemo.Settings;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;

public class GlobalMeterProviderDemoTest {

  private static Map<String, String> environment(String database) {
    Map<String, String> env = new HashMap<>();
    env.put("ALLOYDB_INSTANCE_NAME", "projects/p/locations/l/clusters/c/instances/i");
    env.put("ALLOYDB_DEMO_DB", database);
    env.put("ALLOYDB_USER", "user");
    env.put("ALLOYDB_PASS", "password");
    return env;
  }

  @Test
  public void rejectsTheDefaultPostgresDatabase() {
    Settings settings = Settings.fromEnvironment(environment("POSTGRES"));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, settings::validate);

    assertThat(thrown).hasMessageThat().contains("your own database");
  }

  @Test
  public void requiresTheDemoDatabase() {
    Map<String, String> env = environment("demo");
    env.remove("ALLOYDB_DEMO_DB");

    Settings settings = Settings.fromEnvironment(env);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, settings::validate);
    assertThat(thrown).hasMessageThat().contains("ALLOYDB_DEMO_DB");
  }

  @Test
  public void buildsAJdbcConnectionThroughTheConnector() {
    Settings settings = Settings.fromEnvironment(environment("demo"));

    settings.validate();
    Properties properties = settings.connectionProperties();

    assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql:///demo");
    assertThat(properties.getProperty("socketFactory"))
        .isEqualTo("com.google.cloud.alloydb.SocketFactory");
    assertThat(properties.getProperty("alloydbIpType")).isEqualTo("PUBLIC");
    assertThat(properties.getProperty("alloydbEnableBuiltInTelemetry")).isEqualTo("false");
    // The global path is on by default, and that default is what the demo proves.
    assertThat(properties).doesNotContainKey("alloydbEnableGlobalTelemetry");
  }
}
