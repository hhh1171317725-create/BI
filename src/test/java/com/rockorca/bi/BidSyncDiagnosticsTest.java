package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.sql.*;
import java.util.concurrent.CancellationException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class BidSyncDiagnosticsTest {
  @Test void pageContextAndNestedNetworkCategoriesNeverExposeOriginalBodies(){
    var timeout=new BidSyncDiagnostics.PageFailure("byte",2,new IOException("private cookie",new HttpTimeoutException("private response")));
    assertEquals("字节 · 第 2 页：网络请求超时",timeout.getMessage());
    assertEquals(timeout.getMessage(),BidSyncDiagnostics.describe(timeout));
    assertNotNull(timeout.getCause());assertFalse(BidSyncDiagnostics.requiresAttention(timeout));
    assertEquals("广点通 · 第 4 页：网络连接失败",BidSyncDiagnostics.describe(
        new BidSyncDiagnostics.PageFailure("gdt",4,new ConnectException("private host/password"))));
    assertEquals("TLS 连接失败",BidSyncDiagnostics.describe(new IOException("private proxy",new SSLException("private certificate"))));
  }

  @Test void boundedRequestFailuresKeepOnlyStatusAttemptsAndSafeCategories(){
    var transientHttp=new BidUpstreamRequest.Failure("广点通",BidUpstreamRequest.Kind.HTTP,503,3);
    assertEquals("广点通：上游 HTTP 503（已尝试 3 次）",BidSyncDiagnostics.describe(transientHttp));
    assertFalse(BidSyncDiagnostics.requiresAttention(transientHttp));
    assertTrue(BidSyncDiagnostics.requiresAttention(new BidSyncDiagnostics.PageFailure("byte",1,
        new BidUpstreamRequest.Failure("字节",BidUpstreamRequest.Kind.HTTP,401,1))));
    assertEquals("字节：网络请求超时（已尝试 2 次）",BidSyncDiagnostics.describe(
        new BidUpstreamRequest.Failure("字节",BidUpstreamRequest.Kind.TIMEOUT,0,2)));
    assertEquals("广点通：上游请求尝试次数或时间预算已用尽（已尝试 3 次）",BidSyncDiagnostics.describe(
        new BidUpstreamRequest.Failure("广点通",BidUpstreamRequest.Kind.BUDGET,0,3)));
  }

  @Test void onlyExactOwnedPaginationMessagesMayContributeNumericDetails(){
    assertEquals("字节 · 第 2 页：分页总数变化（300 → 301）",BidSyncDiagnostics.describe(
        new BidSyncDiagnostics.PageFailure("byte",2,new IllegalArgumentException("分页期间计划总数从 300 变为 301，请重新查询"))));
    assertEquals("广点通 · 第 3 页：分页数据不完整（应有 100 条，实际 99 条）",BidSyncDiagnostics.describe(
        new BidSyncDiagnostics.PageFailure("gdt",3,new IllegalArgumentException("第 3 页数据不完整：应有 100 条，实际 99 条"))));
    String untrusted=BidSyncDiagnostics.describe(new IllegalArgumentException("分页期间计划总数从 300 变为 301，请重新查询 private-cookie"));
    assertEquals("计划数据或分页响应校验失败",untrusted);
  }

  @Test void databaseDiagnosticsUseExceptionTypesAndNeverRawSqlMessages(){
    assertEquals("数据库连接池等待超时或连接失败",BidSyncDiagnostics.describe(
        new IllegalStateException("private datasource",new SQLTransientConnectionException("private JDBC password"))));
    assertEquals("数据库查询超时",BidSyncDiagnostics.describe(new SQLTimeoutException("private query")));
    assertEquals("数据库读写失败",BidSyncDiagnostics.describe(new SQLException("private SQL values")));
  }

  @Test void wrappedAuthenticationKeepsProviderAndCodeWithoutExceptionDetails(){
    var rejection=new BidSyncDiagnostics.PageFailure("gdt",3,
        new BidUpstreamRequest.Rejection("广点通","-1","private response body"));
    assertTrue(BidSyncDiagnostics.requiresAttention(rejection));
    assertEquals("广点通 · 第 3 页：上游拒绝请求（code=-1）",BidSyncDiagnostics.describe(rejection));
    assertTrue(BidSyncDiagnostics.failure(rejection).contains("广点通 code=-1"));
    assertFalse(BidSyncDiagnostics.failure(rejection).contains("private"));
    var legacy=new BidSyncDiagnostics.PageFailure("byte",1,new IllegalArgumentException("创量 code=-1 private-cookie"));
    assertTrue(BidSyncDiagnostics.requiresAttention(legacy));assertTrue(BidSyncDiagnostics.failure(legacy).contains("字节 code=-1"));
  }

  @Test void permissionCredentialEmptyAndCancellationHaveFixedDescriptions(){
    var permission=new IllegalStateException("permission");var credential=new IllegalStateException("credential");
    assertTrue(BidSyncDiagnostics.requiresAttention(new RuntimeException(permission)));
    assertEquals("网站账户或工具权限已撤销",BidSyncDiagnostics.describe(permission));
    assertTrue(BidSyncDiagnostics.requiresAttention(new RuntimeException(credential)));
    assertEquals("保存的凭据无法解密",BidSyncDiagnostics.describe(credential));
    assertEquals("查询范围内没有计划",BidSyncDiagnostics.describe(new IllegalArgumentException("查询范围内没有字节或广点通计划，保留原有结果")));
    assertEquals("查询已取消或中断",BidSyncDiagnostics.describe(new CancellationException("private details")));
  }
}
