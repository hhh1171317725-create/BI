package com.rockorca.bi;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RuntimeConfig {
  private final Map<String, String> values = new LinkedHashMap<>();
  private final ObjectMapper objectMapper;
  private Path runtimeDir;

  public RuntimeConfig(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void load() {
    values.putAll(System.getenv());
    runtimeDir = Path.of(get("DHH_RUNTIME_DIR", ".runtime")).toAbsolutePath().normalize();
    loadFile("mysql.env");
    loadFile("ai.env");
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

  private static String randomHex(int bytes) {
    byte[] data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return java.util.HexFormat.of().formatHex(data);
  }
}
