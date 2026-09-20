package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReportRepositoryPoolSizingTest {
  @Test
  void reservesControlConnectionsWithinExistingTotalBudget() {
    assertEquals(new ReportRepository.PoolSizes(3, 2), ReportRepository.poolSizes(5, 2));
    assertEquals(new ReportRepository.PoolSizes(6, 2), ReportRepository.poolSizes(8, 2));
  }

  @Test
  void keepsAtLeastOneReportConnectionForInvalidOrOversizedSettings() {
    assertEquals(new ReportRepository.PoolSizes(2, 1), ReportRepository.poolSizes(1, 0));
    assertEquals(new ReportRepository.PoolSizes(1, 4), ReportRepository.poolSizes(5, 99));
  }
}
