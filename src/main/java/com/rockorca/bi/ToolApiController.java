package com.rockorca.bi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 下载给报表使用的辅助资料。 */
@RestController
@RequestMapping("/api/tools")
public class ToolApiController {
  @GetMapping("/data-dictionary")
  public ResponseEntity<byte[]> downloadDataDictionary() throws Exception {
    return download(Path.of("database", "DATA_DICTIONARY.md"), "报表数据字典.md", "text/markdown");
  }

  @GetMapping("/api-check-script")
  public ResponseEntity<byte[]> downloadApiCheckScript() throws Exception {
    return download(Path.of("scripts", "Test-AllApis.ps1"), "报表接口检查.ps1", "text/plain");
  }

  private ResponseEntity<byte[]> download(Path source, String filename, String contentType) throws Exception {
    if (!Files.isRegularFile(source)) throw new IllegalStateException("服务器未找到下载文件");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType + ";charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()
            .toString())
        .body(Files.readAllBytes(source));
  }
}
