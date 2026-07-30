package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class RuntimeConfigTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void aiCredentialsArePersistedAndAvailableWithoutRestart() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    config.saveAiCredentials("deepseek", "sk-server-secret", "deepseek-chat");

    Path path = temporaryDirectory.resolve("ai.env");
    assertTrue(Files.isRegularFile(path));
    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(
        Files.readString(path, StandardCharsets.UTF_8));
    assertEquals("deepseek", saved.get("AI_PROVIDER"));
    assertEquals("sk-server-secret", saved.get("DEEPSEEK_API_KEY"));
    assertEquals("deepseek-chat", saved.get("DEEPSEEK_MODEL"));
    assertEquals("sk-server-secret", config.get("DEEPSEEK_API_KEY", ""));

    config.saveAiCredentials("deepseek", "", "deepseek-reasoner");
    assertEquals("deepseek-reasoner", config.get("DEEPSEEK_MODEL", ""));
  }

  @Test
  void aiCredentialsRejectInvalidProviderAndKey() {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveAiCredentials("unknown", "sk-server-secret", "model"));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveAiCredentials("openai", "short", "gpt-5.6-terra"));
  }
}
