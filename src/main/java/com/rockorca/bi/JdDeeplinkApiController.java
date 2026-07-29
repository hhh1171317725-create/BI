package com.rockorca.bi;

import java.util.Map;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jd-deeplink")
public class JdDeeplinkApiController {
  private final JdDeeplinkService deeplinks;

  public JdDeeplinkApiController(JdDeeplinkService deeplinks) {
    this.deeplinks = deeplinks;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> payload) {
    return deeplinks.create(ReportService.text(payload.get("skuId")));
  }

  @GetMapping("/products")
  public List<JdDeeplinkService.JdProduct> products(
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String query) {
    return deeplinks.products(query);
  }

  @PostMapping("/batch")
  public ResponseEntity<byte[]> batch(@RequestBody Map<String, Object> payload) {
    Object values = payload.get("skuIds");
    List<String> skuIds = values instanceof List<?> list
        ? list.stream().map(ReportService::text).toList()
        : List.of();
    JdDeeplinkService.BatchExport export = deeplinks.batch(skuIds);
    String filename = "直达链接导入_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .contentLength(export.content().length)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .header("X-Batch-Success", String.valueOf(export.successful()))
        .header("X-Batch-Failed", String.valueOf(export.failed()))
        .body(export.content());
  }
}
