package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Creates JD deeplinks through the configured upstream account. */
@Service
public class JdDeeplinkService {
  private static final String DEFAULT_URL = "https://s.zaore.com/xz-cloud-api/v2/deeplink/jd";
  private static final int MAX_BATCH_SIZE = 100;
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

  public Map<String, Object> credentialStatus() {
    return ReportService.mapOf(
        "tokenConfigured", !config.get("XZ_DEEPLINK_TOKEN", "").isBlank(),
        "signConfigured", !config.get("XZ_DEEPLINK_SIGN", "").isBlank());
  }

  public void saveCredentials(String token, String sign) {
    config.saveDeeplinkCredentials(token, sign);
  }

  public Map<String, Object> create(String skuIdValue) {
    String skuId = String.valueOf(skuIdValue == null ? "" : skuIdValue).trim();
    JdProduct product = productsBySku.get(skuId);
    if (product == null) throw new IllegalArgumentException("请选择底表中的 SKU");
    UpstreamResult result = request(product);
    return ReportService.mapOf("product", product, "requestBody", result.requestBody(), "response", result.response());
  }

  public BatchExport batch(List<String> skuIds) {
    List<String> requested = skuIds == null ? List.of() : skuIds.stream()
        .map(value -> String.valueOf(value == null ? "" : value).trim())
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
    if (requested.isEmpty()) throw new IllegalArgumentException("请至少添加一个 SKU");
    if (requested.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("单次最多生成 " + MAX_BATCH_SIZE + " 个 SKU");
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      List<CompletableFuture<BatchItem>> futures = requested.stream()
          .map(skuId -> CompletableFuture.supplyAsync(() -> createBatchItem(skuId), executor))
          .toList();
      List<BatchItem> items = futures.stream().map(CompletableFuture::join).toList();
      long successful = items.stream().filter(item -> item.error().isBlank()).count();
      return new BatchExport(exportTemplate(items), successful, items.size() - successful);
    }
  }

  private BatchItem createBatchItem(String skuId) {
    JdProduct product = productsBySku.get(skuId);
    if (product == null) return new BatchItem(skuId, "", "", "", "底表中未找到 SKU");
    try {
      UpstreamResult result = request(product);
      String deeplink = valueForKey(result.response(), "deeplink_cvt");
      String universalLink = valueForKey(result.response(), "universal_link");
      if (deeplink.isBlank() && universalLink.isBlank()) {
        return new BatchItem(skuId, product.name(), "", "", "接口响应中未找到 deeplink_cvt 或 universal_link");
      }
      return new BatchItem(skuId, product.name(), deeplink, universalLink, "");
    } catch (Exception error) {
      return new BatchItem(skuId, product.name(), "", "", error.getMessage());
    }
  }

  private UpstreamResult request(JdProduct product) {
    String token = required("XZ_DEEPLINK_TOKEN");
    String signature = required("XZ_DEEPLINK_SIGN");
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    Map<String, Object> payload = requestBody(product);

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
      return new UpstreamResult(payload, body);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("深链请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException) throw (IllegalStateException) error;
      throw new IllegalStateException("深链请求失败：" + error.getMessage(), error);
    }
  }

  Map<String, Object> requestBody(JdProduct product) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("interface_version", 1);
    payload.put("lp_url", product.externalUrl());
    payload.put("channel", config.get("XZ_DEEPLINK_CHANNEL", "ttyl1"));
    payload.put("platform", config.get("XZ_DEEPLINK_PLATFORM", "toutiao-v2"));
    payload.put("account", config.get("XZ_DEEPLINK_ACCOUNT", "yinfu-qac"));
    payload.put("pid", config.get("XZ_DEEPLINK_PID", "2036510647_4101491011_3107491173"));
    payload.put("account_list", List.of());
    return payload;
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

  private static String valueForKey(Object value, String key) {
    if (value instanceof Map<?, ?> map) {
      Object direct = map.get(key);
      if (direct != null && !(direct instanceof Map<?, ?>) && !(direct instanceof List<?>)) {
        return String.valueOf(direct);
      }
      for (Object child : map.values()) {
        String found = valueForKey(child, key);
        if (!found.isBlank()) return found;
      }
    } else if (value instanceof List<?> list) {
      for (Object child : list) {
        String found = valueForKey(child, key);
        if (!found.isBlank()) return found;
      }
    }
    return "";
  }

  private static byte[] exportTemplate(List<BatchItem> items) {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
      writeEntry(zip, "[Content_Types].xml", """
          <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
          <Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>
          """);
      writeEntry(zip, "_rels/.rels", """
          <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
          <Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>
          """);
      writeEntry(zip, "xl/workbook.xml", """
          <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
          <workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>
          """);
      writeEntry(zip, "xl/_rels/workbook.xml.rels", """
          <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
          <Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>
          """);
      StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
      sheet.append(row(1, List.of("填写说明：直达链接名称必填；DeepLink 和 ULink 至少填写一个。批量生成结果仅填写名称、DeepLink、ULink 三列。")));
      sheet.append(row(2, List.of("直达链接名称", "DeepLink", "ULink（和DeepLink至少填写一个）", "媒体账户ID（选填）", "应用项目（选填）", "覆盖直达链接（如覆盖填1，留空则不覆盖）", "DeepLink2", "DeepLink3", "DeepLink4", "DeepLink5", "DeepLink6", "DeepLink7", "DeepLink8", "DeepLink9", "DeepLink10")));
      int row = 3;
      for (BatchItem item : items) {
        if (!item.error().isBlank()) continue;
        String shortName = item.name().substring(0, Math.min(item.name().length(), 30));
        sheet.append(row(row++, List.of(item.skuId() + shortName, item.deeplink(), item.universalLink())));
      }
      sheet.append("</sheetData></worksheet>");
      writeEntry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
      zip.finish();
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("生成直达链接导入文件失败：" + error.getMessage(), error);
    }
  }

  private static String row(int rowNumber, List<String> values) {
    StringBuilder row = new StringBuilder("<row r=\"").append(rowNumber).append("\">");
    for (int index = 0; index < values.size(); index++) {
      row.append("<c r=\"").append(column(index)).append(rowNumber).append("\" t=\"inlineStr\"><is><t>")
          .append(xml(values.get(index))).append("</t></is></c>");
    }
    return row.append("</row>").toString();
  }

  private static String column(int index) {
    StringBuilder value = new StringBuilder();
    for (int position = index; position >= 0; position = position / 26 - 1) {
      value.insert(0, (char) ('A' + position % 26));
    }
    return value.toString();
  }

  private static String xml(String value) {
    return String.valueOf(value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  public record JdProduct(String skuId, String name, String externalUrl) {}

  private record UpstreamResult(Map<String, Object> requestBody, Object response) {}

  private record BatchItem(String skuId, String name, String deeplink, String universalLink, String error) {}

  public record BatchExport(byte[] content, long successful, long failed) {}
}
