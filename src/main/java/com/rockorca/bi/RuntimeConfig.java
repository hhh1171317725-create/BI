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

  private static String randomHex(int bytes) {
    byte[] data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return java.util.HexFormat.of().formatHex(data);
  }
}
