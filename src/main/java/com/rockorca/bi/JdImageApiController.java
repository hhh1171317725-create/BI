package com.rockorca.bi;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jd-images")
public class JdImageApiController {
  private final JdImageService images;

  public JdImageApiController(JdImageService images) {
    this.images = images;
  }

  @PostMapping("/download")
  public ResponseEntity<byte[]> download(@RequestBody Map<String, Object> payload) {
    Object values = payload.get("skus");
    List<String> skus = values instanceof List<?> list
        ? list.stream().map(ReportService::text).toList()
        : List.of(ReportService.text(values));
    JdImageService.DownloadArchive archive = images.download(skus);
    String filename = "jd-images-"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .contentLength(archive.content().length)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
        .header("X-Download-Success", String.valueOf(archive.successful()))
        .header("X-Download-Failed", String.valueOf(archive.failed()))
        .header("X-Download-Images", String.valueOf(archive.images()))
        .body(archive.content());
  }
}
