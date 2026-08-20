package com.rockorca.bi;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RuntimeConfig {
  private final Map<String, String> values = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper;
  private Path runtimeDir;

  public RuntimeConfig(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void load() {
    // 系统环境变量优先级最高；runtime 文件只补充尚未配置的键。
    values.putAll(System.getenv());
    runtimeDir = Path.of(get("DHH_RUNTIME_DIR", ".runtime")).toAbsolutePath().normalize();
    loadFile("mysql.env");
    loadFile("ai.env");
    loadOptionalFile("deeplink.env");
    loadOptionalFile("ssh.env");
    loadOptionalFile("jd-low-activity.env");
    loadOptionalFile("adpflux.env");
    loadOptionalFile("report-visibility.env");
    // 未固定密钥时每次启动都会生成新密钥，因此旧登录 Cookie 会自然失效。
    values.computeIfAbsent("REPORT_SESSION_SECRET", ignored -> randomHex(32));
  }

  private void loadFile(String filename) {
    Path path = runtimeDir.resolve(filename);
    if (!Files.exists(path)) return;
    try {
      parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          .forEach(values::putIfAbsent);
    } catch (IOException error) {
      throw new IllegalStateException("读取 " + filename + " 失败：" + error.getMessage(), error);
    }
  }

  private void loadOptionalFile(String filename) {
    try {
      loadFile(filename);
    } catch (IllegalStateException ignored) {
      // An unavailable optional integration must not stop the reporting service.
    }
  }

  public static Map<String, String> parseEnvironmentFile(String content) {
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String rawLine : String.valueOf(content).split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int separator = line.indexOf('=');
      if (separator < 1) continue;
      String key = line.substring(0, separator).trim();
      String value = line.substring(separator + 1).trim();
      if (value.length() >= 2
          && ((value.startsWith("\"") && value.endsWith("\""))
          || (value.startsWith("'") && value.endsWith("'")))) {
        value = value.substring(1, value.length() - 1);
      }
      if (key.matches("^[A-Z_][A-Z0-9_]*$")) parsed.put(key, value);
    }
    return parsed;
  }

  public String get(String key, String defaultValue) {
    String value = values.get(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  public int getInt(String key, int defaultValue) {
    try {
      return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  public Path runtimeDir() {
    return runtimeDir;
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  public Map<String, Object> reportVisibility() {
    return Map.of(
        "dhh", enabled("REPORT_DHH_VISIBLE"),
        "jd", enabled("REPORT_JD_VISIBLE"),
        "jdLowActivity", enabled("REPORT_JD_LOW_ACTIVITY_VISIBLE"),
        "adpflux", enabled("REPORT_ADPFLUX_VISIBLE"));
  }

  public synchronized Map<String, Object> saveReportVisibility(
      boolean dhh,
      boolean jd,
      boolean jdLowActivity) {
    return saveReportVisibility(dhh, jd, jdLowActivity, enabled("REPORT_ADPFLUX_VISIBLE"));
  }

  public synchronized Map<String, Object> saveReportVisibility(
      boolean dhh,
      boolean jd,
      boolean jdLowActivity,
      boolean adpflux) {
    Path path = runtimeDir.resolve("report-visibility.env");
    Map<String, String> saved = new LinkedHashMap<>();
    saved.put("REPORT_DHH_VISIBLE", String.valueOf(dhh));
    saved.put("REPORT_JD_VISIBLE", String.valueOf(jd));
    saved.put("REPORT_JD_LOW_ACTIVITY_VISIBLE", String.valueOf(jdLowActivity));
    saved.put("REPORT_ADPFLUX_VISIBLE", String.valueOf(adpflux));
    try {
      Files.createDirectories(runtimeDir);
      saveEnvironmentFile(path, "# Managed by the report visibility settings page.", saved);
      saved.forEach(values::put);
      return reportVisibility();
    } catch (IOException error) {
      throw new IllegalStateException("保存日报展示设置失败：" + error.getMessage(), error);
    }
  }

  public synchronized void saveDeeplinkCredentials(String tokenValue, String signValue) {
    String token = String.valueOf(tokenValue == null ? "" : tokenValue).trim();
    String sign = String.valueOf(signValue == null ? "" : signValue).trim();
    if (token.isBlank() || sign.isBlank()) {
      throw new IllegalArgumentException("请同时填写 token 和 X-Request-Sign");
    }
    Path path = runtimeDir.resolve("deeplink.env");
    try {
      Files.createDirectories(runtimeDir);
      Map<String, String> saved = Files.isRegularFile(path)
          ? parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          : new LinkedHashMap<>();
      saved.put("XZ_DEEPLINK_TOKEN", token);
      saved.put("XZ_DEEPLINK_SIGN", sign);
      saveEnvironmentFile(path, "# Managed by the JD deeplink settings page.", saved);
      values.put("XZ_DEEPLINK_TOKEN", token);
      values.put("XZ_DEEPLINK_SIGN", sign);
    } catch (IOException error) {
      throw new IllegalStateException("保存深链接口配置失败：" + error.getMessage(), error);
    }
  }

  public synchronized void saveJdLowActivityCredentials(
      String tokenValue,
      String signValue) {
    String token = clean(tokenValue);
    String sign = clean(signValue);
    if (token.isBlank()) token = decodedSecret("JD_LOW_ACTIVITY_TOKEN_B64");
    if (sign.isBlank()) sign = get("JD_LOW_ACTIVITY_SIGN", "");
    if (token.length() < 20 || token.length() > 2_000
        || token.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("请填写有效的京东低活接口 token");
    }
    if (!sign.matches("(?i)^[0-9a-f]{40}$")) {
      throw new IllegalArgumentException("X-Request-Sign 应为 40 位十六进制字符串");
    }

    Path path = runtimeDir.resolve("jd-low-activity.env");
    try {
      Files.createDirectories(runtimeDir);
      Map<String, String> saved = Files.isRegularFile(path)
          ? parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          : new LinkedHashMap<>();
      saved.put("JD_LOW_ACTIVITY_TOKEN_B64", encodeSecret(token));
      saved.put("JD_LOW_ACTIVITY_SIGN", sign.toUpperCase());
      saveEnvironmentFile(path, "# Managed by the JD low-activity report page.", saved);
      saved.forEach(values::put);
    } catch (IOException error) {
      throw new IllegalStateException("保存京东低活接口配置失败：" + error.getMessage(), error);
    }
  }

  public synchronized void saveAdpfluxCredentials(
      String cookieValue,
      String csrfValue,
      String orgIdValue,
      String orgNameValue,
      String currencyValue,
      String timezoneValue) {
    String cookie = clean(cookieValue);
    String csrf = clean(csrfValue);
    String orgId = clean(orgIdValue);
    String orgName = clean(orgNameValue);
    String currency = clean(currencyValue).toUpperCase();
    String timezone = clean(timezoneValue);
    if (cookie.isBlank()) cookie = decodedSecret("ADPFLUX_TIKTOK_COOKIE_B64");
    if (csrf.isBlank()) csrf = decodedSecret("ADPFLUX_CSRF_TOKEN_B64");
    if (orgId.isBlank()) orgId = get("ADPFLUX_TIKTOK_ORG_ID", "");
    if (orgName.isBlank()) orgName = get("ADPFLUX_TIKTOK_ORG_NAME", "");
    if (currency.isBlank()) currency = get("ADPFLUX_TIKTOK_CURRENCY", "USD").toUpperCase();
    if (timezone.isBlank()) timezone = get("ADPFLUX_TIKTOK_TIMEZONE", "America/New_York");
    AdpfluxUpstreamService.Credentials credentials = new AdpfluxUpstreamService.Credentials(
        cookie, csrf, orgId, orgName, currency, timezone);
    credentials.validate();

    Path path = runtimeDir.resolve("adpflux.env");
    try {
      Files.createDirectories(runtimeDir);
      Map<String, String> saved = Files.isRegularFile(path)
          ? parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          : new LinkedHashMap<>();
      saved.remove("ADPFLUX_AUTHORIZATION_FRONT_B64");
      saved.remove("ADPFLUX_COMPANY_EX_ID");
      saved.put("ADPFLUX_TIKTOK_COOKIE_B64", encodeSecret(cookie));
      saved.put("ADPFLUX_CSRF_TOKEN_B64", encodeSecret(csrf));
      saved.put("ADPFLUX_TIKTOK_ORG_ID", orgId);
      saved.put("ADPFLUX_TIKTOK_ORG_NAME", orgName);
      saved.put("ADPFLUX_TIKTOK_CURRENCY", currency);
      saved.put("ADPFLUX_TIKTOK_TIMEZONE", timezone);
      saveEnvironmentFile(path, "# Managed by the TikTok Business account dashboard.", saved);
      saved.forEach(values::put);
    } catch (IOException error) {
      throw new IllegalStateException("保存 ADPFlux 接口配置失败：" + error.getMessage(), error);
    }
  }

  public synchronized void saveAiCredentials(
      String providerValue,
      String apiKeyValue,
      String modelValue) {
    String provider = String.valueOf(providerValue == null ? "" : providerValue)
        .trim().toLowerCase();
    if (!provider.equals("deepseek") && !provider.equals("openai")) {
      throw new IllegalArgumentException("请选择有效的 AI 提供商");
    }
    String keyName = provider.equals("deepseek") ? "DEEPSEEK_API_KEY" : "OPENAI_API_KEY";
    String modelName = provider.equals("deepseek") ? "DEEPSEEK_MODEL" : "OPENAI_MODEL";
    String defaultModel = provider.equals("deepseek") ? "deepseek-v4-flash" : "gpt-5.6-terra";
    String apiKey = String.valueOf(apiKeyValue == null ? "" : apiKeyValue).trim();
    if (apiKey.isBlank()) apiKey = get(keyName, "");
    if (apiKey.length() < 8 || apiKey.length() > 500 || apiKey.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("请填写有效的 AI API Key");
    }
    String model = String.valueOf(modelValue == null ? "" : modelValue).trim();
    if (model.isBlank()) model = get(modelName, defaultModel);
    if (!model.matches("^[A-Za-z0-9._:/-]{1,100}$")) {
      throw new IllegalArgumentException("AI 模型名称格式无效");
    }
    Path path = runtimeDir.resolve("ai.env");
    try {
      Files.createDirectories(runtimeDir);
      Map<String, String> saved = Files.isRegularFile(path)
          ? parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          : new LinkedHashMap<>();
      saved.put("AI_PROVIDER", provider);
      saved.put(keyName, apiKey);
      saved.put(modelName, model);
      saveEnvironmentFile(path, "# Managed by the AI settings page.", saved);
      values.put("AI_PROVIDER", provider);
      values.put(keyName, apiKey);
      values.put(modelName, model);
    } catch (IOException error) {
      throw new IllegalStateException("保存 AI 配置失败：" + error.getMessage(), error);
    }
  }

  public synchronized void saveSshCredentials(
      String hostValue,
      int port,
      String usernameValue,
      String authMethodValue,
      String passwordValue,
      String privateKeyValue,
      String passphraseValue) {
    String host = clean(hostValue);
    String username = clean(usernameValue);
    String authMethod = clean(authMethodValue);
    if (!host.matches("^[A-Za-z0-9._:-]{1,253}$")) {
      throw new IllegalArgumentException("SSH 主机地址格式无效");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("SSH 端口必须在 1 到 65535 之间");
    }
    if (!username.matches("^[A-Za-z_][A-Za-z0-9._-]{0,63}$")) {
      throw new IllegalArgumentException("SSH 用户名格式无效");
    }
    if (!authMethod.equals("password") && !authMethod.equals("privateKey")) {
      throw new IllegalArgumentException("SSH 认证方式无效");
    }

    Path path = runtimeDir.resolve("ssh.env");
    try {
      Files.createDirectories(runtimeDir);
      Map<String, String> saved = Files.isRegularFile(path)
          ? parseEnvironmentFile(Files.readString(path, StandardCharsets.UTF_8))
          : new LinkedHashMap<>();
      saved.put("SSH_HOST", host);
      saved.put("SSH_PORT", String.valueOf(port));
      saved.put("SSH_USERNAME", username);
      saved.put("SSH_AUTH_METHOD", authMethod);

      String password = secret(passwordValue, "SSH 密码");
      if (!password.isBlank()) saved.put("SSH_PASSWORD_B64", encodeSecret(password));

      String privateKey = String.valueOf(privateKeyValue == null ? "" : privateKeyValue).trim();
      if (!privateKey.isBlank()) {
        if (privateKey.length() > 100_000
            || !privateKey.contains("PRIVATE KEY")
            || privateKey.indexOf('\0') >= 0) {
          throw new IllegalArgumentException("SSH 私钥内容无效");
        }
        Path privateKeyPath = runtimeDir.resolve("ssh-private-key");
        Files.writeString(privateKeyPath, privateKey + '\n', StandardCharsets.UTF_8);
        setOwnerOnlyPermissions(privateKeyPath);
        saved.put("SSH_PRIVATE_KEY_PATH", privateKeyPath.toString());
      }

      String passphrase = secret(passphraseValue, "私钥口令");
      if (!passphrase.isBlank()) saved.put("SSH_PASSPHRASE_B64", encodeSecret(passphrase));

      if (authMethod.equals("password")
          && clean(saved.get("SSH_PASSWORD_B64")).isBlank()) {
        throw new IllegalArgumentException("请填写 SSH 密码");
      }
      if (authMethod.equals("privateKey")) {
        String keyPath = clean(saved.get("SSH_PRIVATE_KEY_PATH"));
        if (keyPath.isBlank() || !Files.isRegularFile(Path.of(keyPath))) {
          throw new IllegalArgumentException("请粘贴 SSH 私钥");
        }
      }

      saveEnvironmentFile(path, "# Managed by the server terminal settings page.", saved);
      saved.forEach(values::put);
    } catch (IOException error) {
      throw new IllegalStateException("保存 SSH 配置失败：" + error.getMessage(), error);
    }
  }

  public String decodedSecret(String key) {
    String encoded = get(key, "");
    if (encoded.isBlank()) return "";
    try {
      return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ignored) {
      return "";
    }
  }

  private static void saveEnvironmentFile(
      Path path,
      String header,
      Map<String, String> saved) throws IOException {
    StringBuilder content = new StringBuilder(header).append('\n');
    saved.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    setOwnerOnlyPermissions(temporary);
    try {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
    setOwnerOnlyPermissions(path);
  }

  private static void setOwnerOnlyPermissions(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows 等非 POSIX 文件系统不支持 Unix 权限。
    }
  }

  private static String clean(String value) {
    return String.valueOf(value == null ? "" : value).trim();
  }

  private boolean enabled(String key) {
    return !"false".equalsIgnoreCase(get(key, "true"));
  }

  private static String secret(String value, String label) {
    String result = String.valueOf(value == null ? "" : value);
    if (result.length() > 1_000 || result.indexOf('\0') >= 0
        || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0) {
      throw new IllegalArgumentException(label + "格式无效");
    }
    return result;
  }

  private static String encodeSecret(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String randomHex(int bytes) {
    byte[] data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return java.util.HexFormat.of().formatHex(data);
  }
}
