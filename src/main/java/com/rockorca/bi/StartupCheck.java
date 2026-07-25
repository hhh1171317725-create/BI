package com.rockorca.bi;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupCheck implements ApplicationRunner {
  private final ReportRepository repository;

  public StartupCheck(ReportRepository repository) {
    this.repository = repository;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      repository.ping();
      System.out.println("MySQL 数据库连接成功");
    } catch (Exception error) {
      System.err.println("MySQL 数据库连接失败：" + error.getMessage());
    }
    System.out.println("下一次全量数据更新：" + ReportService.nextScheduledRefreshAt());
  }
}
