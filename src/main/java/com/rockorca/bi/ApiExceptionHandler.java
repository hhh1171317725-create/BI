package com.rockorca.bi;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将 API 异常统一转换为 JSON，避免前端意外收到 HTML 错误页。 */
@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleError(Exception error) {
    Throwable source = error.getCause() == null ? error : error.getCause();
    String message = source.getMessage();
    if (message == null || message.isBlank()) {
      message = "服务异常";
    }
    return ResponseEntity.badRequest().body(ReportService.mapOf("error", message));
  }
}
