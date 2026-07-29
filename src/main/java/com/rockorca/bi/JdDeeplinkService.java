package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Creates JD deeplinks through the configured upstream account. */
@Service
public class JdDeeplinkService {
  private static final String DEFAULT_URL = "https://s.zaore.com/xz-cloud-api/v2/deeplink/jd";
  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final Map<String, JdProduct> productsBySku = new HashMap<>();
  private List<JdProduct> products = List.of();
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build();

  public JdDeeplinkService(RuntimeConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void loadProducts() {
    try {
      try (InputStream stream = new ClassPathResource("jd-deeplink-products.json").getInputStream()) {
        products = objectMapper.readValue(stream, new TypeReference<List<JdProduct>>() {});
      }
      products.forEach(product -> productsBySku.put(product.skuId(), product));
    } catch (Exception error) {
      throw new IllegalStateException("加载京东 SKU 底表失败：" + error.getMessage(), error);
    }
  }

  public List<JdProduct> products(String queryValue) {
    String query = String.valueOf(queryValue == null ? "" : queryValue).trim().toLowerCase();
    return products.stream()
        .filter(product -> query.isBlank() || product.skuId().contains(query)
            || product.name().toLowerCase().contains(query))
        .sorted(Comparator.comparing(product -> product.skuId().equals(query) ? 0 : 1))
        .limit(30)
        .toList();
  }

  public Map<String, Object> create(String skuIdValue) {
    String skuId = String.valueOf(skuIdValue == null ? "" : skuIdValue).trim();
    JdProduct product = productsBySku.get(skuId);
    if (product == null) throw new IllegalArgumentException("请选择底表中的 SKU");
    String landingPage = product.h5();

    String token = required("XZ_DEEPLINK_TOKEN");
    String signature = required("XZ_DEEPLINK_SIGN");
    String timestamp = required("XZ_DEEPLINK_TIMESTAMP");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("interface_version", 1);
    payload.put("lp_url", landingPage);
    payload.put("channel", config.get("XZ_DEEPLINK_CHANNEL", "ttyl1"));
    payload.put("platform", config.get("XZ_DEEPLINK_PLATFORM", "toutiao-v2"));
    payload.put("account", config.get("XZ_DEEPLINK_ACCOUNT", "yinfu-qac"));
    payload.put("pid", config.get("XZ_DEEPLINK_PID", "2036510647_4101491011_3107491173"));
    payload.put("account_list", List.of());

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.get("XZ_DEEPLINK_URL", DEFAULT_URL)))
          .timeout(Duration.ofSeconds(30))
          .header("Accept", "application/json, text/plain, */*")
          .header("Accept-Language", "zh-CN,zh;q=0.9")
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json")
          .header("Cookie", "token=" + token)
          .header("Origin", "https://s.zaore.com")
          .header("X-Request-Sign", signature)
          .header("X-Request-Source", config.get("XZ_DEEPLINK_SOURCE", "web??"))
          .header("X-Request-Timestamp", timestamp)
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("上游接口返回 HTTP " + response.statusCode() + "：" + shorten(response.body()));
      }
      Object body = objectMapper.readValue(response.body(), new TypeReference<Object>() {});
      return ReportService.mapOf("product", product, "response", body);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("深链请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException) throw (IllegalStateException) error;
      throw new IllegalStateException("深链请求失败：" + error.getMessage(), error);
    }
  }

  private String required(String key) {
    String value = config.get(key, "");
    if (value.isBlank()) throw new IllegalStateException("服务器尚未配置 " + key);
    return value;
  }

  private static String shorten(String text) {
    String value = String.valueOf(text).replaceAll("\\s+", " ").trim();
    return value.substring(0, Math.min(value.length(), 500));
  }

  public record JdProduct(String skuId, String name, String h5) {}
}
