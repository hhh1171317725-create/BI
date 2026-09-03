package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class BidMonitorApiControllerTest {
  private final BidMonitorApiController controller = new BidMonitorApiController(new ObjectMapper());

  @Test void requestIdMatchesSuccessfulUpstreamFormat() {
    assertTrue(BidMonitorApiController.requestId().matches("[0-9]{14}[0-9a-f]{32}ff"));
  }
  @Test void rejectsThirdPageBeforeNetwork() {
    assertThrows(IllegalArgumentException.class,()->controller.page(Map.of("startDate","2026-09-03","endDate","2026-09-03","page",3)));
  }

  @Test void readsTotalFromActualPageInfo() {
    assertEquals(4656, BidMonitorApiController.totalCount(Map.of("page_info", Map.of("total_count",4656)),Map.of()));
    assertEquals(20, BidMonitorApiController.totalCount(Map.of("total_count",20),Map.of()));
  }

  @Test void readsExcelWithoutRoundingTextIds() throws Exception {
    try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
      var sheet = book.createSheet(); var header = sheet.createRow(0); var row = sheet.createRow(1);
      String[] keys = {"计划ID", "消耗", "转化数", "注册数", "出价"};
      for (int i=0;i<keys.length;i++) header.createCell(i).setCellValue(keys[i]);
      row.createCell(0).setCellValue("7676449794404745237");
      for (int i=1;i<keys.length;i++) row.createCell(i).setCellValue(i*10);
      book.write(bytes);
      var result = controller.importExcel(new MockMultipartFile("file","report.xlsx","application/octet-stream",bytes.toByteArray()));
      var rows = (List<?>)result.get("rows");
      assertEquals(1, rows.size());
      assertEquals("7676449794404745237", ((Map<?,?>)rows.getFirst()).get("计划ID"));
    }
  }

  @Test void rejectsMissingCredentialsBeforeNetwork() {
    assertThrows(IllegalArgumentException.class, () -> controller.page(Map.of(
        "startDate","2026-09-03","endDate","2026-09-03","page",1)));
  }
  @Test void rejectsReversedDates() {
    assertThrows(IllegalArgumentException.class, () -> controller.page(Map.of(
        "startDate","2026-09-03","endDate","2026-09-01","page",1)));
  }

  @Test void normalizesPastedMarkdownCookie() {
    assertEquals("userId=12; chuangliang_session=abc_def", BidMonitorApiController.normalizeCookie(
        "Cookie: 'userId=12; chuangliang\\_session=abc\\_def'"));
    assertThrows(IllegalArgumentException.class, () -> BidMonitorApiController.normalizeCookie("a=b\nHeader: value"));
  }

  @Test void validatesSessionAndUserConsistency() {
    assertThrows(IllegalArgumentException.class, () -> BidMonitorApiController.validateCookieUser("userId=12", "12"));
    assertThrows(IllegalArgumentException.class, () -> BidMonitorApiController.validateCookieUser("userId=12; chuangliang_session=abc", "13"));
    assertDoesNotThrow(() -> BidMonitorApiController.validateCookieUser("userId=12; chuangliang_session=abc", "12"));
  }

  @Test void preservesUpstreamReasonAndRedactsCredentials() {
    String error = BidMonitorApiController.upstreamError(Map.of("code",-1,"message","非法访问 secret-value","request_id","trace-123"), "chuangliang_session=secret-value");
    assertTrue(error.contains("非法访问"));assertTrue(error.contains("trace-123"));
    assertFalse(error.contains("secret-value"));assertFalse(error.contains("请更新登录凭据"));
  }
}
