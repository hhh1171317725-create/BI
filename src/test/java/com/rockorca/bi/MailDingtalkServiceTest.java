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
}
