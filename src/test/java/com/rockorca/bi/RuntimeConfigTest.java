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
  void generatedSessionSecretSurvivesServiceRestart() throws Exception {
    RuntimeConfig first = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(first, "runtimeDir", temporaryDirectory);
    first.ensureSessionSecret();

    String generated = first.get("REPORT_SESSION_SECRET", "");
    assertTrue(generated.matches("^[0-9a-f]{64}$"));
    assertTrue(Files.isRegularFile(temporaryDirectory.resolve("session.env")));

    RuntimeConfig restarted = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(restarted, "runtimeDir", temporaryDirectory);
    restarted.ensureSessionSecret();

    assertEquals(generated, restarted.get("REPORT_SESSION_SECRET", ""));
  }

  @Test
  void reportVisibilityIsEnabledByDefaultAndPersisted() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    assertEquals(
        Map.of("dhh", true, "jd", true, "jdLowActivity", true, "adpflux", true),
        config.reportVisibility());
    assertEquals(
        Map.of("dhh", true, "jd", false, "jdLowActivity", true, "adpflux", true),
        config.saveReportVisibility(true, false, true));

    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(
        Files.readString(
            temporaryDirectory.resolve("report-visibility.env"), StandardCharsets.UTF_8));
    assertEquals("true", saved.get("REPORT_DHH_VISIBLE"));
    assertEquals("false", saved.get("REPORT_JD_VISIBLE"));
    assertEquals("true", saved.get("REPORT_JD_LOW_ACTIVITY_VISIBLE"));
    assertEquals("true", saved.get("REPORT_ADPFLUX_VISIBLE"));
  }

  @Test
  void adpfluxCredentialsAreEncodedAndAvailableWithoutRestart() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    config.saveAdpfluxCredentials("front-token-value-123456", "12345678901234567890");

    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(
        Files.readString(temporaryDirectory.resolve("adpflux.env"), StandardCharsets.UTF_8));
    assertEquals("12345678901234567890", saved.get("ADPFLUX_COMPANY_EX_ID"));
    assertEquals(
        "front-token-value-123456",
        config.decodedSecret("ADPFLUX_AUTHORIZATION_FRONT_B64"));
  }

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

  @Test
  void jdLowActivityCredentialsArePersistedEncodedAndRetained() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);
    String token = "valid-jd-low-activity-token-123456";
    String sign = "A".repeat(40);

    config.saveJdLowActivityCredentials(token, sign);

    Path path = temporaryDirectory.resolve("jd-low-activity.env");
    assertTrue(Files.isRegularFile(path));
    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(
        Files.readString(path, StandardCharsets.UTF_8));
    assertTrue(!saved.get("JD_LOW_ACTIVITY_TOKEN_B64").contains(token));
    assertEquals(token, config.decodedSecret("JD_LOW_ACTIVITY_TOKEN_B64"));
    assertEquals(sign, config.get("JD_LOW_ACTIVITY_SIGN", ""));

    config.saveJdLowActivityCredentials("", "");
    assertEquals(token, config.decodedSecret("JD_LOW_ACTIVITY_TOKEN_B64"));
    assertEquals(sign, config.get("JD_LOW_ACTIVITY_SIGN", ""));
  }

  @Test
  void jdLowActivityCredentialsRejectInvalidValues() {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveJdLowActivityCredentials("short", "A".repeat(40)));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.saveJdLowActivityCredentials(
            "valid-jd-low-activity-token-123456", "not-a-sign"));
  }

  @Test
  void adpfluxBalanceCredentialsRemoveMarkdownEscapes() {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);

    String arbitrageToken = "header.payload.signature_with_dash-123456";
    String authorizationFront = "header.payload.signature_with_underscore-123456";
    config.saveAdpfluxBalanceCredentials(
        arbitrageToken.replace("_", "\\_"),
        "10017864344199803618",
        authorizationFront.replace("_", "\\_"),
        "70017864344226243679",
        "10017618944851621347");

    assertEquals(
        arbitrageToken,
        config.decodedSecret("ADPFLUX_BALANCE_ARBITRAGE_TOKEN_B64"));
    assertEquals(
        authorizationFront,
        config.decodedSecret("ADPFLUX_BALANCE_AUTHORIZATION_FRONT_B64"));
  }

  @Test
  void mailDingtalkCredentialsAreEncodedAndRetained() throws Exception {
    RuntimeConfig config = new RuntimeConfig(new ObjectMapper());
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);
    String webhook = "https://oapi.dingtalk.com/robot/send?access_token=abcdefghijk";

    config.saveMailDingtalkCredentials(
        "alerts@qq.com", "qq-imap-code", webhook, "SECabcdefghijk", "广告预警", true);

    Map<String, String> saved = RuntimeConfig.parseEnvironmentFile(Files.readString(
        temporaryDirectory.resolve("mail-dingtalk.env"), StandardCharsets.UTF_8));
    assertEquals("alerts@qq.com", saved.get("MAIL_DINGTALK_QQ_EMAIL"));
    assertTrue(!saved.get("MAIL_DINGTALK_QQ_AUTH_CODE_B64").contains("qq-imap-code"));
    assertEquals("qq-imap-code", config.decodedSecret("MAIL_DINGTALK_QQ_AUTH_CODE_B64"));
    assertEquals(webhook, config.decodedSecret("MAIL_DINGTALK_WEBHOOK_B64"));
    assertEquals("广告预警", saved.get("MAIL_DINGTALK_KEYWORD"));

    config.saveMailDingtalkCredentials("", "", "", "", "广告预警", false);
    assertEquals("alerts@qq.com", config.get("MAIL_DINGTALK_QQ_EMAIL", ""));
    assertEquals("false", config.get("MAIL_DINGTALK_AUTO_ENABLED", ""));
  }
}
