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
    Path dictionary = Path.of("database", "DATA_DICTIONARY.md");
    if (!Files.isRegularFile(dictionary)) {
      throw new IllegalStateException("服务器未找到数据字典文件");
    }
    byte[] content = Files.readAllBytes(dictionary);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename("报表数据字典.md", StandardCharsets.UTF_8)
            .build()
            .toString())
        .body(content);
  }
}
