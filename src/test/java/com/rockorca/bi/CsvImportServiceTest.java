package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CsvImportServiceTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void parserStripsUtf8BomAndRejectsMalformedQuotes() {
    List<Map<String, String>> rows =
        CsvImportService.parseCsv("\uFEFF日期,消耗\r\n2026-07-25,100");

    assertEquals("100", rows.getFirst().get("消耗"));
    assertEquals("2026-07-25", rows.getFirst().get("日期"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CsvImportService.parseCsv("日期,备注\n2026-07-25,\"未闭合"));
  }

  @Test
  void nonFiniteNumbersAreConvertedToZero() {
    assertEquals(0, CsvImportService.number("NaN"));
    assertEquals(0, CsvImportService.number("Infinity"));
    assertEquals(1234.5, CsvImportService.number("1,234.5"));
  }

  @Test
  void upstreamRequestUsesRequiredHeadersAndImportsBomCsv() throws Exception {
    AtomicReference<String> tokenHeader = new AtomicReference<>();
    AtomicReference<String> userHeader = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/dhh", exchange -> {
      tokenHeader.set(exchange.getRequestHeaders().getFirst("X-Token"));
      userHeader.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
      query.set(exchange.getRequestURI().getRawQuery());
      respond(exchange, 200, dhhCsv());
    });
    server.createContext("/jd", exchange -> respond(exchange, 200, "not-used"));
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    CsvImportService importer = new CsvImportService(
        new ObjectMapper(), HttpClient.newHttpClient(), base + "/dhh", base + "/jd");

    List<Map<String, Object>> rows = importer.fetchDhhRows(
        "temporary-token", "20", "2026-07-01", "2026-07-25");

    assertEquals(1, rows.size());
    assertEquals("2026-07-25", rows.getFirst().get("日期"));
    assertEquals("temporary-token", tokenHeader.get());
    assertEquals("20", userHeader.get());
    assertEquals("startTime=2026-07-01&endTime=2026-07-25", query.get());
  }

  @Test
  void missingSchemaIsReportedBeforeDatabaseReplacement() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/dhh", exchange -> respond(exchange, 200, "日期,消耗\n2026-07-25,1"));
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/dhh";
    CsvImportService importer =
        new CsvImportService(new ObjectMapper(), HttpClient.newHttpClient(), url, url);

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> importer.fetchDhhRows("temporary-token", "20"));
    assertTrue(error.getMessage().contains("CSV 缺少必要列"));
  }

  private static String dhhCsv() {
    return "\uFEFF日期,媒体,账户信息,优化师,任务名,消耗,现金消耗,赠款消耗,预估佣金,结算数,转化数,注册数\r\n"
        + "2026-07-25,巨量,[],陈灵灿,淘宝促购CVR,100,80,20,120,8,10,9\r\n";
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/csv;charset=UTF-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
