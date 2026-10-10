package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class GdtBidMonitorClientTest {
  private static Map<String,Object> reportInput(){return Map.of("startDate","2026-10-10","endDate","2026-10-10","page",1,
      "cookie","userId=123; chuangliang_session=unit-test-only","clientUser","123","mainUserId","456");}
  private static GdtBidMonitorClient noWaitClient(HttpClient client){return new GdtBidMonitorClient(new ObjectMapper(),
      new BidUpstreamRequest(client,System::nanoTime,java.time.Clock.systemUTC(),delay->{}));}
  private static HttpResponse<String> reply(int status,String json,String retryAfter){
    return BidUpstreamRequestTest.response(status,json,retryAfter);
  }

  @Test void networkRetriesKeepTheQueryBodyAndReplaceTheTraceHeader()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.io.IOException("private detail")).thenReturn(reply(503,"not exposed",null),reply(200,"{\"code\":0,\"data\":{\"list\":[],\"total_count\":0}}",null));
    assertEquals(0,noWaitClient(client).page(reportInput()).get("total"));
    var sent=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(3)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(1,sent.getAllValues().stream().map(BidUpstreamRequestTest::body).distinct().count());
    assertEquals(3,sent.getAllValues().stream().map(request->request.headers().firstValue("ff-request-id").orElseThrow()).distinct().count());
  }

  @Test void businessCodesNeverReceiveBlindTransportRetries()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(reply(200,"{\"code\":403,\"message\":\"business permission denied\"}",null));
    var error=assertThrows(BidUpstreamRequest.Rejection.class,()->noWaitClient(client).page(reportInput()));
    assertEquals("广点通",error.platform());assertEquals("403",error.code());verify(client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }

  @Test void aCompatibilityRetryPreservesRealNetworkAndRateLimitFailures()throws Exception{
    for(boolean rateLimited:List.of(false,true)){
      HttpClient client=mock(HttpClient.class);var response=when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
          .thenReturn(reply(200,"{\"code\":-1,\"message\":\"first compatibility refusal\"}",null));
      if(rateLimited)response.thenReturn(reply(429,"private body","120"));else response.thenThrow(new java.net.http.HttpTimeoutException("private timeout detail"));
      var error=assertThrows(BidUpstreamRequest.Failure.class,()->noWaitClient(client).page(reportInput()));
      assertEquals(rateLimited?BidUpstreamRequest.Kind.HTTP:BidUpstreamRequest.Kind.TIMEOUT,error.kind());
      assertEquals(rateLimited?429:0,error.status());assertFalse(error.getMessage().contains("code=-1"));assertFalse(error.getMessage().contains("private"));
      verify(client,times(rateLimited?2:3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void compatibilityRefusalKeepsTheOriginalReasonOnlyForTheSameBusinessCode()throws Exception{
    for(int fallbackCode:List.of(-1,403)){
      HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
          .thenReturn(reply(200,"{\"code\":-1,\"message\":\"first compatibility refusal\"}",null),reply(200,"{\"code\":"+fallbackCode+",\"message\":\"second business refusal\"}",null));
      var error=assertThrows(BidUpstreamRequest.Rejection.class,()->noWaitClient(client).page(reportInput()));
      assertEquals(Integer.toString(fallbackCode),error.code());assertTrue(error.getMessage().contains(fallbackCode==-1?"first compatibility":"second business"));
      verify(client,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void aCompatibilityRetryNeverSwallowsInterruption()throws Exception{
    HttpClient client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(reply(200,"{\"code\":-1,\"message\":\"optional field refused\"}",null)).thenThrow(new InterruptedException("interrupted"));
    try{assertThrows(InterruptedException.class,()->noWaitClient(client).page(reportInput()));assertTrue(Thread.currentThread().isInterrupted());}
    finally{Thread.interrupted();}
    verify(client,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }

  @Test void compatibilityUsesTheRemainingOriginalTimeBudget()throws Exception{
    HttpClient client=mock(HttpClient.class);var millis=new java.util.concurrent.atomic.AtomicLong();
    when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{millis.set(35_000);return reply(200,"{\"code\":-1,\"message\":\"optional field refused\"}",null);})
        .thenReturn(reply(200,"{\"code\":0,\"data\":{\"list\":[],\"total_count\":0}}",null));
    var requests=new BidUpstreamRequest(client,()->millis.get()*1_000_000,java.time.Clock.systemUTC(),delay->millis.addAndGet(delay));
    new GdtBidMonitorClient(new ObjectMapper(),requests).page(reportInput());
    var sent=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(List.of(40_000L,25_000L),sent.getAllValues().stream().map(request->request.timeout().orElseThrow().toMillis()).toList());
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
    var subject=new GdtBidMonitorClient(new ObjectMapper(),client);
    var input=Map.<String,Object>of("startDate","2026-09-22","endDate","2026-09-22","page",1,
        "cookie","userId=123; chuangliang_session=test","clientUser","123","mainUserId","456");
    assertEquals(0,subject.page(input).get("total"));
    assertEquals(0,subject.page(input).get("total"));
    verify(client,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  @Test void warningVerificationRequestsOnlyNecessaryCountersAndCandidateAdvertisers(){
    var body=GdtBidMonitorClient.requestBody(Map.of("verificationOnly",true,"accountIds",List.of("89696535","89696535")),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-22"),1);
    assertEquals(List.of("cost","conversions_count"),body.get("kpis"));
    assertEquals(List.of("89696535"),((Map<?,?>)body.get("conditions")).get("advertiser_id"));
    assertTrue(((List<?>)body.get("base_infos")).contains("bid_amount"));
  }
  @Test void springCanCreateTheProductionClient(){
    try(var context=new AnnotationConfigApplicationContext()){
      context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.register(GdtBidMonitorClient.class);context.refresh();
      assertNotNull(context.getBean(GdtBidMonitorClient.class));
    }
  }
  @Test void buildsTheVerifiedGdtReportRequest(){
    var input=Map.<String,Object>of("createdStart","2026-09-06","createdEnd","2026-09-09");
    var body=GdtBidMonitorClient.requestBody(input,LocalDate.parse("2026-09-09"),LocalDate.parse("2026-09-09"),3);
    assertEquals("gdt_upgrade",body.get("media_type"));assertEquals(100,body.get("page_size"));assertEquals(3,body.get("page"));
    assertEquals("adgroup_id",body.get("sort_field"));
    var conditions=(Map<?,?>)body.get("conditions");assertEquals(List.of("2026-09-06","2026-09-09"),conditions.get("created_time"));
    assertTrue(((List<?>)body.get("base_infos")).containsAll(List.of("advertiser_nick","user_name","deep_bid_amount","open_url")));
    assertFalse(((List<?>)GdtBidMonitorClient.requestBody(Map.of("requestOpenUrl",false),LocalDate.parse("2026-09-09"),LocalDate.parse("2026-09-09"),1).get("base_infos")).contains("open_url"));
    assertTrue(((List<?>)body.get("kpis")).contains("reg_pv"));
  }

  @Test void mapsGdtFieldsIntoTheSharedBidMonitorSchema(){
    var raw=new LinkedHashMap<String,Object>();
    raw.put("adgroup_id","134008243258");raw.put("adgroup_name","广点通计划");
    raw.put("advertiser_id","89696535");raw.put("media_account_id","12628542431");
    raw.put("advertiser_nick","账户甲");raw.put("user_name","优化师甲");raw.put("created_time","2026-09-09 10:00:00");
    raw.put("cost","123.45");raw.put("view_count","10000");raw.put("conversions_count","12");raw.put("reg_pv","56");raw.put("deep_conversions_count","34");raw.put("bid_amount","5.5");
    raw.put("deep_bid_amount","8.8");raw.put("bid_mode_name","自动出价");raw.put("optimization_goal_name","注册");
    raw.put("deep_conversion_spec_name","付费");raw.put("system_status_name","投放中");raw.put("open_url","tbopen://example/landing");
    var result=GdtBidMonitorClient.parse(Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",301))),1,"chuangliang_session=secret");
    assertEquals(301,result.get("total"));var row=(Map<?,?>)((List<?>)result.get("rows")).getFirst();
    assertEquals("134008243258",row.get("promotion_id"));assertEquals("89696535",row.get("advertiser_id"));
    assertEquals("123.45",row.get("stat_cost"));assertEquals("12",row.get("convert_cnt"));assertEquals("56",row.get("active_register"));
    assertEquals("5.5",row.get("cpa_bid"));assertEquals("10000",row.get("show_cnt"));assertEquals("账户甲",row.get("media_account_name"));assertEquals("优化师甲",row.get("user_name"));
    assertEquals("自动出价",row.get("deep_bid_type_text"));assertEquals("付费",row.get("deep_external_action_text"));
    assertEquals("注册",row.get("external_action_text"));assertEquals("投放中",row.get("status_text"));
    assertEquals("gdt",row.get("source_platform"));assertEquals("广点通",row.get("platform_text"));assertEquals("tbopen://example/landing",row.get("open_url"));
    var provider=(Map<?,?>)row.get("provider_data");assertEquals("34",provider.get("deep_conversions_count"));
  }

  @Test void keepsZeroMetricsAndRejectsInvalidIds(){
    var raw=new LinkedHashMap<String,Object>();raw.put("adgroup_id","1");raw.put("advertiser_id","2");raw.put("media_account_id","3");
    var result=GdtBidMonitorClient.parse(Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",1))),1,"chuangliang_session=x");
    var row=(Map<?,?>)((List<?>)result.get("rows")).getFirst();assertEquals("0",row.get("stat_cost"));assertEquals("0",row.get("active_register"));
    raw.put("adgroup_id",1.0d);assertThrows(IllegalArgumentException.class,()->GdtBidMonitorClient.parse(
        Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",1))),1,"chuangliang_session=x"));
  }
}
