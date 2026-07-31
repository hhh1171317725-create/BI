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

  @Test
  void sshPasswordIsPersistedEncodedAndAvailableWithoutRestart() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    config.saveSshCredentials(
        "127.0.0.1", 22, "root", "password", "server-secret", "", "");

    Path path = temporaryDirectory.resolve("ssh.env");
    assertTrue(Files.isRegularFile(path));
    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(
        Files.readString(path, StandardCharsets.UTF_8));
    assertEquals("127.0.0.1", saved.get("SSH_HOST"));
    assertEquals("22", saved.get("SSH_PORT"));
    assertEquals("root", saved.get("SSH_USERNAME"));
    assertEquals("password", saved.get("SSH_AUTH_METHOD"));
    assertTrue(!saved.get("SSH_PASSWORD_B64").contains("server-secret"));
    assertEquals("server-secret", config.decodedSecret("SSH_PASSWORD_B64"));

    config.saveSshCredentials(
        "server.example.com", 2222, "deploy", "password", "", "", "");
    assertEquals("server-secret", config.decodedSecret("SSH_PASSWORD_B64"));
    assertEquals("server.example.com", config.get("SSH_HOST", ""));
  }

  @Test
  void sshCredentialsRejectInvalidConnectionSettings() {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveSshCredentials(
            "https://server.example.com", 22, "root", "password", "secret", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveSshCredentials(
            "server.example.com", 0, "root", "password", "secret", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveSshCredentials(
            "server.example.com", 22, "bad user", "password", "secret", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveSshCredentials(
            "server.example.com", 22, "root", "privateKey", "", "", ""));
  }
}
