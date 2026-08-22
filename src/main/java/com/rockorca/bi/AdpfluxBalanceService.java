package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Pulls current TikTok advertiser balances from the HubxAD account-management API. */
@Service
public class AdpfluxBalanceService {
  static final String BALANCE_URL =
      "https://front-api.hubxad.com/front_api/advertiser/list";
  static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final AdpfluxRepository repository;
  private final HttpClient client;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  @Autowired
  public AdpfluxBalanceService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      AdpfluxRepository repository) {
    this(
        config,
        objectMapper,
        repository,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  AdpfluxBalanceService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      AdpfluxRepository repository,
      HttpClient client) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.repository = repository;
    this.client = client;
  }

  public Map<String, Object> credentialStatus() {
    Credentials credentials = credentials();
    return ReportService.mapOf(
        "balanceConfigured", credentials.configured(),
        "balanceArbitrageTokenSaved",
            !config.decodedSecret("ADPFLUX_BALANCE_ARBITRAGE_TOKEN_B64").isBlank(),
        "balanceAuthorizationFrontSaved",
            !config.decodedSecret("ADPFLUX_BALANCE_AUTHORIZATION_FRONT_B64").isBlank(),
        "balanceArbitrageCompanyId", credentials.arbitrageCompanyId(),
        "balanceCompanyExId", credentials.companyExId(),
        "balanceUpAgentId", credentials.upAgentId(),
        "balanceCachedAt", repository.latestBalanceSyncTime());
  }

  public void saveCredentials(
      String arbitrageToken,
      String arbitrageCompanyId,
      String authorizationFront,
      String companyExId,
      String upAgentId) {
    config.saveAdpfluxBalanceCredentials(
        arbitrageToken,
        arbitrageCompanyId,
        authorizationFront,
        companyExId,
        upAgentId);
  }

  /** Starts shortly after boot and refreshes the MySQL balance snapshot every ten minutes. */
  @Scheduled(initialDelay = 15_000, fixedDelay = 600_000)
  public void scheduledSync() {
    Credentials credentials = credentials();
    if (!credentials.configured() || !syncRunning.compareAndSet(false, true)) return;
    try {
      List<Map<String, Object>> rows = fetchBalances(credentials);
      repository.replaceCurrentBalances(rows);
      System.out.println(
          "ADPFlux账户余额定时更新成功：" + rows.size() + " 个账户 "
              + ZonedDateTime.now(ReportService.BEIJING));
    } catch (Exception error) {
      System.err.println("ADPFlux账户余额定时更新失败：" + error.getMessage());
    } finally {
      syncRunning.set(false);
    }
  }

  List<Map<String, Object>> fetchBalances(Credentials credentials) {
    credentials.validate();
    Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    int expected = Integer.MAX_VALUE;
    for (int page = 1; page <= MAX_PAGES && rows.size() < expected; page++) {
      Page result = requestPage(page, credentials);
      expected = result.total();
      for (Map<String, Object> row : result.rows()) {
        rows.put(ReportService.text(row.get("advertiserId")), row);
      }
      if (result.rows().isEmpty()) break;
    }
    if (rows.isEmpty()) {
      throw new IllegalStateException("余额接口未返回账户，已取消覆盖数据库");
    }
    if (expected != Integer.MAX_VALUE && rows.size() < expected) {
      throw new IllegalStateException(
          "ADPFlux余额分页数据不完整：应有 " + expected + " 个账户，实际读取 " + rows.size());
    }
    return new ArrayList<>(rows.values());
  }

  Map<String, Object> mapRow(Map<String, Object> source) {
    String advertiserId = ReportService.text(source.get("advertiser_id"));
    if (advertiserId.isBlank()) return null;
    return ReportService.mapOf(
        "advertiserId", advertiserId,
        "advertiserName", ReportService.text(source.get("advertiser_name")),
        "companyExId", ReportService.text(source.get("company_ex_id")),
        "balance", ReportService.round(ReportService.number(source.get("balance")), 4));
  }

  private Page requestPage(int page, Credentials credentials) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("advertiser_ids", List.of());
    payload.put("order_info", ReportService.mapOf("field", "balance", "order_type", "desc"));
    payload.put("page", page);
    payload.put("page_size", PAGE_SIZE);
    payload.put("platform", 1);
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(BALANCE_URL))
          .timeout(Duration.ofSeconds(45))
          .header("Accept", "*/*")
          .header("Accept-Language", "zh-CN,zh;q=0.9")
          .header("ArbitrageCompanyID", credentials.arbitrageCompanyId())
          .header("ArbitrageToken", credentials.arbitrageToken())
          .header("AuthorizationFront", credentials.authorizationFront())
          .header("CompanyExID", credentials.companyExId())
          .header("Content-Type", "application/json")
          .header("Lang", "zh-CN")
          .header("Origin", "https://ad.hubxad.com")
          .header("Referer", "https://ad.hubxad.com/")
          .header("UpAgentID", credentials.upAgentId())
          .header("User-Agent", "Mozilla/5.0")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "余额接口返回 HTTP " + response.statusCode() + "：" + shorten(response.body()));
      }
      Map<String, Object> root = objectMapper.readValue(
          response.body(), new TypeReference<Map<String, Object>>() {});
      if ((int) ReportService.number(root.get("code")) != 0) {
        throw new IllegalStateException(
            "余额接口请求失败：" + ReportService.text(root.get("message")));
      }
      Map<String, Object> data = objectMap(root.get("data"));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object item : objectList(data.get("list"))) {
        Map<String, Object> row = mapRow(objectMap(item));
        if (row != null) rows.add(row);
      }
      return new Page(Math.max(rows.size(), (int) ReportService.number(data.get("count"))), rows);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ADPFlux余额接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("ADPFlux余额接口请求失败：" + error.getMessage(), error);
    }
  }

  private Credentials credentials() {
    return new Credentials(
        config.decodedSecret("ADPFLUX_BALANCE_ARBITRAGE_TOKEN_B64"),
        config.get("ADPFLUX_BALANCE_ARBITRAGE_COMPANY_ID", ""),
        config.decodedSecret("ADPFLUX_BALANCE_AUTHORIZATION_FRONT_B64"),
        config.get("ADPFLUX_BALANCE_COMPANY_EX_ID", ""),
        config.get("ADPFLUX_BALANCE_UP_AGENT_ID", ""));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static List<?> objectList(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static String shorten(String value) {
    String text = ReportService.text(value).replaceAll("\\s+", " ");
    return text.substring(0, Math.min(300, text.length()));
  }

  record Credentials(
      String arbitrageToken,
      String arbitrageCompanyId,
      String authorizationFront,
      String companyExId,
      String upAgentId) {
    boolean configured() {
      return validToken(arbitrageToken)
          && validId(arbitrageCompanyId)
          && validToken(authorizationFront)
          && validId(companyExId)
          && validId(upAgentId);
    }

    void validate() {
      if (!configured()) throw new IllegalStateException("请先配置完整的 ADPFlux 余额接口凭据");
    }

    private static boolean validToken(String value) {
      return value != null
          && value.length() >= 20
          && value.length() <= 4_000
          && value.chars().noneMatch(Character::isWhitespace);
    }

    private static boolean validId(String value) {
      return value != null && value.matches("^[0-9]{8,30}$");
    }
  }

  private record Page(int total, List<Map<String, Object>> rows) {}
}
