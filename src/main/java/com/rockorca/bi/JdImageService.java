package com.rockorca.bi;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Downloads the image carousel exposed by JD's mobile product page. */
@Service
public class JdImageService {
  static final int MAX_SKUS = 20;
  private static final int MAX_IMAGES_PER_SKU = 20;
  private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
  private static final int MAX_ARCHIVE_BYTES = 200 * 1024 * 1024;
  private static final Pattern SKU_PATTERN = Pattern.compile("\\d{5,20}");
  private static final Pattern SIZE_PATH = Pattern.compile("/(?:s\\d+x\\d+_)?jfs/");
  private static final String USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
      + "AppleWebKit/605.1.15 Mobile/15E148";

  private final ObjectMapper objectMapper;
  private final HttpClient client;

  @Autowired
  public JdImageService(ObjectMapper objectMapper) {
    this(objectMapper, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .version(HttpClient.Version.HTTP_1_1)
        .build());
  }

  JdImageService(ObjectMapper objectMapper, HttpClient client) {
    this.objectMapper = objectMapper;
    this.client = client;
  }

  public DownloadArchive download(List<String> values) {
    List<String> skus = normalizeSkus(values);
    AtomicLong totalBytes = new AtomicLong();
    try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, skus.size()))) {
      List<CompletableFuture<ProductDownload>> futures = skus.stream()
          .map(sku -> CompletableFuture.supplyAsync(() -> downloadProduct(sku, totalBytes), executor))
          .toList();
      List<ProductDownload> products = futures.stream().map(CompletableFuture::join).toList();
      return createArchive(products);
    }
  }

  static List<String> normalizeSkus(List<String> values) {
    Set<String> skus = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        Matcher matcher = SKU_PATTERN.matcher(String.valueOf(value == null ? "" : value));
        while (matcher.find()) skus.add(matcher.group());
      }
    }
    if (skus.isEmpty()) throw new IllegalArgumentException("请至少输入一个有效 SKU");
    if (skus.size() > MAX_SKUS) throw new IllegalArgumentException("单次最多下载 " + MAX_SKUS + " 个 SKU");
    return List.copyOf(skus);
  }

  ProductInfo parseProduct(String requestedSku, String html) throws Exception {
    String json = extractAssignedJson(html, "window._itemOnly");
    JsonNode item = objectMapper.readTree(json).path("item");
    if (item.isMissingNode() || item.isNull()) throw new IllegalArgumentException("商品页缺少主图数据");
    String actualSku = item.path("skuId").asString("");
    if (!requestedSku.equals(actualSku)) throw new IllegalArgumentException("商品页 SKU 与请求不一致");
    String name = item.path("skuName").asString("未命名商品");
    List<String> images = new ArrayList<>();
    JsonNode imageNode = item.path("image");
    if (imageNode.isArray()) {
      for (JsonNode image : imageNode) {
        String url = normalizeImageUrl(image.asString(""));
        if (!url.isBlank() && !images.contains(url)) images.add(url);
        if (images.size() >= MAX_IMAGES_PER_SKU) break;
      }
    }
    if (images.isEmpty()) throw new IllegalArgumentException("商品页未返回主图");
    return new ProductInfo(requestedSku, name, List.copyOf(images));
  }

  static String extractAssignedJson(String html, String assignment) {
    int marker = html.indexOf(assignment);
    if (marker < 0) throw new IllegalArgumentException("京东商品页暂未返回可用数据");
    int start = html.indexOf('{', marker + assignment.length());
    if (start < 0) throw new IllegalArgumentException("京东商品数据格式异常");
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = start; index < html.length(); index++) {
      char current = html.charAt(index);
      if (quoted) {
        if (escaped) escaped = false;
        else if (current == '\\') escaped = true;
        else if (current == '"') quoted = false;
        continue;
      }
      if (current == '"') quoted = true;
      else if (current == '{') depth++;
      else if (current == '}' && --depth == 0) return html.substring(start, index + 1);
    }
    throw new IllegalArgumentException("京东商品数据不完整");
  }

  static String normalizeImageUrl(String value) {
    String url = String.valueOf(value == null ? "" : value).trim().replace("\\/", "/");
    if (url.isBlank()) return "";
    if (url.startsWith("jfs/")) return "https://img10.360buyimg.com/n0/" + url;
    if (url.startsWith("//")) url = "https:" + url;
    if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException error) {
      return "";
    }
    String host = String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT);
    if (!host.equals("360buyimg.com") && !host.endsWith(".360buyimg.com")) return "";
    String path = uri.getPath().replaceFirst("/n\\d+/", "/n0/");
    path = SIZE_PATH.matcher(path).replaceFirst("/n0/jfs/");
    path = path.replaceFirst("(?i)\\.(?:avif|webp)$", "");
    return "https://" + host + path;
  }

  private ProductDownload downloadProduct(String sku, AtomicLong totalBytes) {
    try {
      String page = sendText("https://item.m.jd.com/product/" + sku + ".html");
      ProductInfo info = parseProduct(sku, page);
      List<ImageFile> files = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      for (int index = 0; index < info.images().size(); index++) {
        try {
          files.add(downloadImage(info.images().get(index), index + 1, totalBytes));
        } catch (Exception error) {
          warnings.add("第 " + (index + 1) + " 张：" + cleanMessage(error));
        }
      }
      if (files.isEmpty()) throw new IllegalArgumentException("主图下载全部失败");
      return new ProductDownload(sku, info.name(), List.copyOf(files), List.copyOf(warnings), "");
    } catch (Exception error) {
      return new ProductDownload(sku, "", List.of(), List.of(), cleanMessage(error));
    }
  }

  private String sendText(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IllegalArgumentException("商品页返回 HTTP " + response.statusCode());
    if (response.body().contains("window._itemOnly")) return response.body();
    return sendTextWithCurl(url);
  }

  private String sendTextWithCurl(String url) throws Exception {
    String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
        ? "curl.exe" : "curl";
    Process process;
    try {
      process = new ProcessBuilder(
          executable, "--silent", "--show-error", "--location", "--fail", "--max-filesize", "2097152",
          "--max-time", "20", "--user-agent", USER_AGENT,
          "--header", "Accept-Language: zh-CN,zh;q=0.9",
          "--header", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          url)
          .redirectErrorStream(true)
          .start();
    } catch (Exception error) {
      throw new IllegalArgumentException("京东限流且服务器未安装 curl", error);
    }
    byte[] bytes = process.getInputStream().readAllBytes();
    if (!process.waitFor(25, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalArgumentException("京东商品页请求超时");
    }
    String body = new String(bytes, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IllegalArgumentException("curl 请求失败：" + body.strip());
    }
    return body;
  }

  private ImageFile downloadImage(String url, int sequence, AtomicLong totalBytes) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(25))
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://item.jd.com/")
        .GET().build();
    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) throw new IllegalArgumentException("HTTP " + response.statusCode());
    byte[] bytes = response.body();
    if (bytes.length == 0) throw new IllegalArgumentException("图片内容为空");
    if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过 15MB");
    String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
    if (!contentType.startsWith("image/")) throw new IllegalArgumentException("返回内容不是图片");
    long total = totalBytes.addAndGet(bytes.length);
    if (total > MAX_ARCHIVE_BYTES) {
      totalBytes.addAndGet(-bytes.length);
      throw new IllegalArgumentException("批量图片超过 200MB，请减少 SKU 后重试");
    }
    return new ImageFile(sequence, extension(contentType, url), bytes);
  }

  private DownloadArchive createArchive(List<ProductDownload> products) {
    long successful = products.stream().filter(product -> product.error().isBlank()).count();
    int imageCount = products.stream().mapToInt(product -> product.images().size()).sum();
    if (imageCount == 0) {
      String details = products.stream().map(product -> product.sku() + "：" + product.error())
          .reduce((left, right) -> left + "；" + right).orElse("未找到主图");
      throw new IllegalArgumentException("下载失败。" + details);
    }
    String date = LocalDate.now().toString();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
         ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      StringBuilder report = new StringBuilder("京东商品主图下载结果\n\n");
      for (ProductDownload product : products) {
        if (!product.error().isBlank()) {
          report.append(product.sku()).append("：失败 - ").append(product.error()).append('\n');
          continue;
        }
        report.append(product.sku()).append(" ").append(product.name())
            .append("：").append(product.images().size()).append(" 张");
        if (!product.warnings().isEmpty()) report.append("，").append(String.join("；", product.warnings()));
        report.append('\n');
        for (ImageFile image : product.images()) {
          String name = "jd-images/" + date + "/" + product.sku() + "-"
              + String.format("%02d", image.sequence()) + "." + image.extension();
          zip.putNextEntry(new ZipEntry(name));
          zip.write(image.bytes());
          zip.closeEntry();
          if (output.size() > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("压缩包超过 200MB，请减少 SKU 后重试");
        }
      }
      zip.putNextEntry(new ZipEntry("jd-images/" + date + "/download-result.txt"));
      zip.write(report.toString().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.finish();
      return new DownloadArchive(output.toByteArray(), (int) successful,
          products.size() - (int) successful, imageCount);
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("生成图片压缩包失败：" + cleanMessage(error), error);
    }
  }

  private static String extension(String contentType, String url) {
    if (contentType.contains("png")) return "png";
    if (contentType.contains("webp")) return "webp";
    if (contentType.contains("gif")) return "gif";
    if (contentType.contains("avif")) return "avif";
    String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".png")) return "png";
    if (path.endsWith(".webp")) return "webp";
    return "jpg";
  }

  private static String cleanMessage(Throwable error) {
    Throwable source = error;
    while (source.getCause() != null) source = source.getCause();
    String message = source.getMessage();
    return message == null || message.isBlank() ? "未知错误" : message;
  }

  record ProductInfo(String sku, String name, List<String> images) {}
  private record ImageFile(int sequence, String extension, byte[] bytes) {}
  private record ProductDownload(String sku, String name, List<ImageFile> images,
                                 List<String> warnings, String error) {}
  public record DownloadArchive(byte[] content, int successful, int failed, int images) {}
}
