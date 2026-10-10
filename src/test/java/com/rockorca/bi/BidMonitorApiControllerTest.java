package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class BidMonitorApiControllerTest {
  private final BidMonitorApiController controller = new BidMonitorApiController(new ObjectMapper());
  private static Map<String,Object> reportInput(){return Map.of("startDate","2026-10-10","endDate","2026-10-10","page",1,
      "cookie","userId=123; chuangliang_session=unit-test-only","clientUser","123","mainUserId","456");}
  private static BidMonitorApiController noWaitController(HttpClient client){return new BidMonitorApiController(new ObjectMapper(),
      new BidUpstreamRequest(client,System::nanoTime,java.time.Clock.systemUTC(),delay->{}));}
  private static HttpResponse<String> reply(int status,String json,String retryAfter){
    return BidUpstreamRequestTest.response(status,json,retryAfter);
  }

  @Test void networkRetriesKeepTheQueryBodyAndReplaceTheTraceHeader()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.io.IOException("private detail")).thenReturn(reply(503,"not exposed",null),reply(200,"{\"code\":0,\"data\":{\"list\":[],\"total_count\":0}}",null));
    assertEquals(0,noWaitController(client).page(reportInput()).get("total"));
    var sent=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(3)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(1,sent.getAllValues().stream().map(BidUpstreamRequestTest::body).distinct().count());
    assertEquals(3,sent.getAllValues().stream().map(request->request.headers().firstValue("ff-request-id").orElseThrow()).distinct().count());
  }

  @Test void businessCodesNeverReceiveBlindTransportRetries()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(reply(200,"{\"code\":403,\"message\":\"business permission denied\"}",null));
    var error=assertThrows(BidUpstreamRequest.Rejection.class,()->noWaitController(client).page(reportInput()));
    assertEquals("字节",error.platform());assertEquals("403",error.code());verify(client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }

  @Test void aCompatibilityRetryPreservesRealNetworkAndRateLimitFailures()throws Exception{
    for(boolean rateLimited:List.of(false,true)){
      HttpClient client=mock(HttpClient.class);var response=when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
          .thenReturn(reply(200,"{\"code\":-1,\"message\":\"first compatibility refusal\"}",null));
      if(rateLimited)response.thenReturn(reply(429,"private body","120"));else response.thenThrow(new java.net.http.HttpTimeoutException("private timeout detail"));
      var error=assertThrows(BidUpstreamRequest.Failure.class,()->noWaitController(client).page(reportInput()));
      assertEquals(rateLimited?BidUpstreamRequest.Kind.HTTP:BidUpstreamRequest.Kind.TIMEOUT,error.kind());
      assertEquals(rateLimited?429:0,error.status());assertFalse(error.getMessage().contains("code=-1"));assertFalse(error.getMessage().contains("private"));
      verify(client,times(rateLimited?2:3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void compatibilityRefusalKeepsTheOriginalReasonOnlyForTheSameBusinessCode()throws Exception{
    for(int fallbackCode:List.of(-1,403)){
      HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
          .thenReturn(reply(200,"{\"code\":-1,\"message\":\"first compatibility refusal\"}",null),reply(200,"{\"code\":"+fallbackCode+",\"message\":\"second business refusal\"}",null));
      var error=assertThrows(BidUpstreamRequest.Rejection.class,()->noWaitController(client).page(reportInput()));
      assertEquals(Integer.toString(fallbackCode),error.code());assertTrue(error.getMessage().contains(fallbackCode==-1?"first compatibility":"second business"));
      verify(client,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void aCompatibilityRetryNeverSwallowsInterruption()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(reply(200,"{\"code\":-1,\"message\":\"optional field refused\"}",null)).thenThrow(new InterruptedException("interrupted"));
    try{assertThrows(InterruptedException.class,()->noWaitController(client).page(reportInput()));assertTrue(Thread.currentThread().isInterrupted());}
    finally{Thread.interrupted();}
    verify(client,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }

  @Test void compatibilityUsesTheRemainingOriginalTimeBudget()throws Exception{
    HttpClient client=mock(HttpClient.class);var millis=new java.util.concurrent.atomic.AtomicLong();
    when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{millis.set(35_000);return reply(200,"{\"code\":-1,\"message\":\"optional field refused\"}",null);})
        .thenReturn(reply(200,"{\"code\":0,\"data\":{\"list\":[],\"total_count\":0}}",null));
    var requests=new BidUpstreamRequest(client,()->millis.get()*1_000_000,java.time.Clock.systemUTC(),delay->millis.addAndGet(delay));
    new BidMonitorApiController(new ObjectMapper(),requests).page(reportInput());
    var sent=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(List.of(40_000L,25_000L),sent.getAllValues().stream().map(request->request.timeout().orElseThrow().toMillis()).toList());
  }

  @Test void springCanCreateTheProductionController() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.register(BidMonitorApiController.class);
      context.refresh();
      assertNotNull(context.getBean(BidMonitorApiController.class));
    }
  }

  @Test void warningVerificationUsesNarrowMetricsWithoutChangingNormalSnapshots(){
    var day=java.time.LocalDate.parse("2026-09-22");
    var body=controller.requestBody(Map.of("verificationOnly",true,"accountIds",List.of("7680747160631230500")),day,day,1);
    assertEquals(List.of("stat_cost","convert_cnt","cpa_bid","promotion_create_time","account_info"),body.get("select_kpi_fields"));
    var conditions=new ObjectMapper().readValue(body.get("conditions").toString(),Map.class);
    assertEquals(List.of("7680747160631230500"),conditions.get("media_account_id"));
    assertTrue(((List<?>)controller.requestBody(Map.of(),day,day,1).get("select_kpi_fields")).contains("active_register"));
    assertTrue(((List<?>)controller.requestBody(Map.of(),day,day,1).get("select_kpi_fields")).contains("open_url"));
    assertFalse(((List<?>)controller.requestBody(Map.of("requestOpenUrl",false),day,day,1).get("select_kpi_fields")).contains("open_url"));
    assertThrows(IllegalArgumentException.class,()->BidMonitorApiController.accountIds(Map.of("accountIds",List.of(123.0))));
  }

  @SuppressWarnings("unchecked")
  @Test void rejectedOptionalUrlFieldRetriesAndKeepsLaterPagesOnCompatibleRequest() throws Exception {
    HttpClient client=mock(HttpClient.class);
    HttpResponse<String> rejected=mock(HttpResponse.class),accepted=mock(HttpResponse.class);
    when(rejected.statusCode()).thenReturn(200);
    when(rejected.body()).thenReturn("{\"code\":-1,\"message\":\"invalid field\"}");
    when(accepted.statusCode()).thenReturn(200);
    when(accepted.body()).thenReturn("{\"code\":0,\"data\":{\"list\":[],\"page_info\":{\"total_count\":0}}}");
    when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(rejected,accepted,accepted);
    var subject=new BidMonitorApiController(new ObjectMapper(),client);
    var input=Map.<String,Object>of("startDate","2026-09-22","endDate","2026-09-22","page",1,
        "cookie","userId=123; chuangliang_session=test","clientUser","123","mainUserId","456");
    assertEquals(0,subject.page(input).get("total"));
    assertEquals(0,subject.page(input).get("total"));
    verify(client,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }

  @Test void requestIdMatchesSuccessfulUpstreamFormat() {
    assertTrue(BidMonitorApiController.requestId().matches("[0-9]{14}[0-9a-f]{32}ff"));
  }
  @Test void idsRemainExactStringsBeforeCrossingBrowserJsonBoundary(){
    assertEquals("1866402186668232",BidMonitorApiController.idText(1866402186668232L));
    assertEquals("7680747160631230500",BidMonitorApiController.idText(7680747160631230500L));
    assertNull(BidMonitorApiController.idText(null));
    assertThrows(IllegalArgumentException.class,()->BidMonitorApiController.idText(7680747160631230500d));
  }
  @Test void rejectsUnsafePageBeforeNetwork() {
    assertThrows(IllegalArgumentException.class,()->controller.page(Map.of("startDate","2026-09-03","endDate","2026-09-03","page",1001)));
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
