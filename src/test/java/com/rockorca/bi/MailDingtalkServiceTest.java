package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailDingtalkServiceTest {
  @Test
  void extractsIdsFromTikTokMailVariants() {
    assertEquals(List.of("1874489175001681", "1874489177329426", "1874489177202385"),
        MailDingtalkService.extractAdGroupIds(
            "Ad Group ID: 1874489175001681,1874489177329426,1874489177202385 Creative ID: 123"));
    assertEquals(List.of("1874489175001681", "1874489177329426"),
        MailDingtalkService.extractAdGroupIds(
            "Ad\u00a0Group\u00a0IDs：1874489175001681， 1874489177329426\nTraining videos"));
    assertEquals(List.of("1874489175001681"),
        MailDingtalkService.extractAdGroupIds("Campaign\u200B ID - 1874489175001681"));
  }

  @Test
  void extractsPolicyReasonFromBrokenHtmlText() {
    String content = "See the following for details:\u00a0Our review indicates that your advertising "
        + "content may violate TikTok's Advertising Policies by featuring or promoting sexually suggestive "
        + "content. We proactively enforce our Advertising Policies. Ad Group ID: 1874489175001681";
    assertEquals(
        "素材可能包含或推广性暗示内容，包括性暗示文字、音频、动作、性行为暗示或敏感部位暗示，违反 TikTok 广告政策。",
        MailDingtalkService.extractViolationReason(content));
  }

  @Test
  void fallsBackToOriginalReviewReason() {
    assertEquals("the landing page contains unsupported claims.",
        MailDingtalkService.extractViolationReason(
            "Our review indicates that the landing page contains unsupported claims. "
                + "We proactively enforce our Advertising Policies."));
  }

  @Test
  void parsesBudgetExhaustedEmail() {
    MailDingtalkService.TikTokBudgetNotice notice = MailDingtalkService.parseTikTokBudgetNotice(
        "Your campaign budget has been exhausted",
        "Ad account ID: 7676449794404745237 Ad account name: XM-budget-test "
            + "Campaign ID: 1874489175001681 Your campaign has reached its budget limit.");
    assertEquals(true, notice.isBudgetNotice());
    assertEquals("7676449794404745237", notice.accountId());
    assertEquals("XM-budget-test", notice.accountName());
    assertEquals(List.of("1874489175001681"), notice.campaignIds());
    assertEquals("预算已用尽或达到上限，相关广告可能已停止或减少投放。", notice.status());
  }

  @Test
  void parsesLowBalanceEmail() {
    MailDingtalkService.TikTokBudgetNotice notice = MailDingtalkService.parseTikTokBudgetNotice(
        "Important: Your account balance is running low", "Ad account ID: 7676449794404745237");
    assertEquals("预算或账户余额不足，可能影响广告继续投放，请及时检查并补充。", notice.status());
  }

  @Test
  void prefersCompleteHtmlDerivedContentOverLongGenericPlainText() {
    String generic = "Important TikTok notification. ".repeat(100);
    String complete = "Ad account ID: 7676449794404745237 Ad Group ID: 1874489175001681 "
        + "Our review indicates that the creative contains sexually suggestive content.";
    assertEquals(true,
        MailDingtalkService.informationScore(complete) > MailDingtalkService.informationScore(generic));
  }
}
