package com.rockorca.bi;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupCheck implements ApplicationRunner {
  private final ReportRepository repository;
  private final UserService users;
  private final TodoService todos;

  public StartupCheck(ReportRepository repository, UserService users, TodoService todos) {
    this.repository = repository;
    this.users = users;
    this.todos = todos;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      repository.ping();
      users.initialize();
      System.out.println("MySQL 数据库连接成功");
    } catch (Exception error) {
      System.err.println("MySQL 数据库连接失败：" + error.getMessage());
    }
    try {
      repository.initializeJdLowActivitySchema();
      System.out.println("京东低活报表数据表检查完成");
    } catch (Exception error) {
      System.err.println("京东低活报表数据表初始化失败：" + error.getMessage());
    }
    try {
      todos.initialize();
      System.out.println("Todo 数据表检查完成");
    } catch (Exception error) {
      System.err.println("Todo 数据表初始化失败：" + error.getMessage());
    }
    System.out.println("下一次全量数据更新：" + ReportService.nextScheduledRefreshAt());
  }
}
