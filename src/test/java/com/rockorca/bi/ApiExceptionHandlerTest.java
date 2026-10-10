package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiExceptionHandlerTest {
  private static final String SECRET="private-upstream-session-body";

  @RestController
  static class FailedPageController {
    @GetMapping("/api/test-page")
    Map<String,Object> page() {
      throw new BidSyncDiagnostics.PageFailure("byte",2,new IOException(SECRET));
    }
  }

  @Test void pageFailureJsonUsesTheSafeDiagnosisInsteadOfItsUpstreamCause()throws Exception {
    var mvc=MockMvcBuilders.standaloneSetup(new FailedPageController())
        .setControllerAdvice(new ApiExceptionHandler()).build();
    String response=mvc.perform(get("/api/test-page")).andExpect(status().isBadRequest())
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(response.contains("字节"));assertTrue(response.contains("第 2 页"));
    assertTrue(response.contains("网络"));assertFalse(response.contains(SECRET));
  }

  @Test void wrappedSafePageFailuresDoNotExposeTheirUnderlyingPrivateMessages() {
    var page=new BidSyncDiagnostics.PageFailure("gdt",7,new IOException(SECRET));
    var response=new ApiExceptionHandler().handleError(new IllegalStateException("wrapper",page));
    assertEquals(page.getMessage(),response.getBody().get("error"));
    assertFalse(response.getBody().toString().contains(SECRET));
  }

  @Test void manualUpstreamRejectionsExposeOnlyTheProviderAndBusinessCode() {
    var rejection=new BidUpstreamRequest.Rejection("广点通","-1","Authorization Bearer "+SECRET);
    for(Exception error:java.util.List.of(rejection,new IllegalStateException("wrapper",rejection))) {
      var response=new ApiExceptionHandler().handleError(error);
      assertEquals("广点通：上游拒绝请求（code=-1）",response.getBody().get("error"));
      assertFalse(response.getBody().toString().contains(SECRET));
    }
  }

  @Test void directTransportFailuresUseTheSameSafeTypedDiagnosis() {
    var response=new ApiExceptionHandler().handleError(
        new BidUpstreamRequest.Failure("字节",BidUpstreamRequest.Kind.HTTP,503,3));
    assertEquals("字节：上游 HTTP 503（已尝试 3 次）",response.getBody().get("error"));
  }

  @Test void unrelatedValidationCausesKeepTheirExistingResponseContract() {
    var response=new ApiExceptionHandler().handleError(
        new IllegalStateException("wrapper",new IllegalArgumentException("请选择有效日期")));
    assertEquals(400,response.getStatusCode().value());
    assertEquals("请选择有效日期",response.getBody().get("error"));
  }
}
