package com.rockorca.bi;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jd-account-deeplink")
public class JdAccountDeeplinkApiController {
  private final JdDeeplinkService deeplinks;

  public JdAccountDeeplinkApiController(JdDeeplinkService deeplinks) {
    this.deeplinks = deeplinks;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> payload) {
    JdDeeplinkService.AccountRequestConfig requestConfig = requestConfig(payload);
    return deeplinks.createAccount(
        ReportService.text(payload.get("skuId")),
        ReportService.text(payload.get("accountId")),
        ReportService.text(payload.get("siteId")),
        requestConfig);
  }

  @GetMapping("/products")
  public List<JdDeeplinkService.JdProduct> products(@RequestParam(defaultValue = "") String query) {
    return deeplinks.products(query);
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    Map<String, Object> result = new java.util.LinkedHashMap<>(deeplinks.credentialStatus());
    result.put("requestDefaults", deeplinks.accountRequestDefaults());
    return result;
  }

  @PostMapping("/config")
  public Map<String, Object> saveConfig(@RequestBody Map<String, Object> payload) {
    deeplinks.saveCredentials(
        ReportService.text(payload.get("token")),
        ReportService.text(payload.get("sign")));
    return deeplinks.credentialStatus();
  }

  @PostMapping("/batch")
  public ResponseEntity<byte[]> batch(@RequestBody Map<String, Object> payload) {
    Object values = payload.get("skuIds");
    List<String> skuIds = values instanceof List<?> list
        ? list.stream().map(ReportService::text).toList()
        : List.of();
    JdDeeplinkService.BatchExport export = deeplinks.batchAccount(
        skuIds,
        ReportService.text(payload.get("accountId")),
        ReportService.text(payload.get("siteId")),
        requestConfig(payload));
    String filename = "JD_account_deeplink_"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .contentLength(export.content().length)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .header("X-Batch-Success", String.valueOf(export.successful()))
        .header("X-Batch-Failed", String.valueOf(export.failed()))
        .body(export.content());
  }

  private JdDeeplinkService.AccountRequestConfig requestConfig(Map<String, Object> payload) {
    return deeplinks.accountRequestConfig(
        payload.get("interfaceVersion"),
        ReportService.text(payload.get("platform")),
        ReportService.text(payload.get("account")),
        ReportService.text(payload.get("pid")),
        ReportService.text(payload.get("channel")));
  }
}
