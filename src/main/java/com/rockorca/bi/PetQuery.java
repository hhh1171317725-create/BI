package com.rockorca.bi;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, deterministic date interpretation; never generates SQL. */
final class PetQuery {
  private PetQuery() {}

  static List<String> range(String question, List<String> fallback, LocalDate today) {
    boolean comparison = Pattern.compile("对比|相比|比较|vs", Pattern.CASE_INSENSITIVE).matcher(question).find();
    Matcher periods = Pattern.compile("今天|今日|昨天|昨日|前天|上周|本周|这周|上月|上个月|本月|这个月").matcher(question);
    int periodCount = 0;
    while (periods.find()) periodCount++;
    if (periodCount > 1 || Pattern.compile("(?:对比|相比|比较)(?:昨天|昨日|上周|上月|上个月)").matcher(question).find()) {
      throw new IllegalArgumentException("请先指定要看的日期范围，再用“对比上期”比较前一个等长周期，例如“最近7天对比上期”。");
    }
    List<LocalDate> dates = new ArrayList<>();
    Matcher explicit = Pattern.compile("(?<!\\d)(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})日?(?!\\d)").matcher(question);
    try {
      while (explicit.find()) dates.add(LocalDate.of(Integer.parseInt(explicit.group(1)),
          Integer.parseInt(explicit.group(2)), Integer.parseInt(explicit.group(3))));
      if (dates.isEmpty()) {
        Matcher shortDate = Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日?").matcher(question);
        while (shortDate.find()) dates.add(LocalDate.of(today.getYear(),
            Integer.parseInt(shortDate.group(1)), Integer.parseInt(shortDate.group(2))));
      }
      if (!dates.isEmpty()) {
        Matcher shortEnd = Pattern.compile("(?:到|至|~|—)\\s*(\\d{1,2})日?(?![\\d月/-])").matcher(question);
        if (dates.size() == 1 && shortEnd.find()) dates.add(dates.getFirst().withDayOfMonth(Integer.parseInt(shortEnd.group(1))));
        if (dates.size() > 2) throw new IllegalArgumentException("请一次指定一个日期范围，或用“对比上期”比较相邻等长周期。");
        if (dates.size() == 2 && comparison && !question.contains("上期")) {
          throw new IllegalArgumentException("目前支持“日期范围＋对比上期”。比较两个单独日期时，请先查询其中一天，再问“对比上期”。");
        }
        return checked(dates.getFirst(), dates.getLast());
      }
      Matcher recent = Pattern.compile("(?:最近|近|过去)(\\d{1,3}|一|三|七|十|十四|三十)(?:天|日)").matcher(question);
      if (recent.find()) {
        int days = switch (recent.group(1)) {
          case "一" -> 1; case "三" -> 3; case "七" -> 7; case "十" -> 10;
          case "十四" -> 14; case "三十" -> 30; default -> Integer.parseInt(recent.group(1));
        };
        if (days < 1 || days > 366) throw new IllegalArgumentException("查询范围请控制在 1 至 366 天。");
        return checked(today.minusDays(days - 1), today);
      }
      if (question.contains("昨天") || question.contains("昨日")) return checked(today.minusDays(1), today.minusDays(1));
      if (question.contains("前天")) return checked(today.minusDays(2), today.minusDays(2));
      if (question.contains("今天") || question.contains("今日")) return checked(today, today);
      LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      if (question.contains("上周")) return checked(monday.minusWeeks(1), monday.minusDays(1));
      if (question.contains("本周") || question.contains("这周")) return checked(monday, today);
      if (question.contains("上月") || question.contains("上个月")) return checked(today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1).minusDays(1));
      if (question.contains("本月") || question.contains("这个月")) return checked(today.withDayOfMonth(1), today);
      Matcher month = Pattern.compile("(?:(20\\d{2})年)?(\\d{1,2})月").matcher(question);
      if (month.find()) {
        LocalDate first = LocalDate.of(month.group(1) == null ? today.getYear() : Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2)), 1);
        if (month.find()) throw new IllegalArgumentException("请一次指定一个月份，或用“对比上期”比较等长周期。");
        return checked(first, first.with(TemporalAdjusters.lastDayOfMonth()));
      }
      if (fallback.size() != 2 || fallback.contains("-")) throw new IllegalArgumentException("请先加载报表，或指定日期，例如“最近7天”。");
      return checked(LocalDate.parse(fallback.getFirst()), LocalDate.parse(fallback.get(1)));
    } catch (java.time.DateTimeException error) {
      throw new IllegalArgumentException("日期无效，请使用真实日期，例如 2026-09-01 至 2026-09-07。");
    }
  }

  static List<String> previous(List<String> range) {
    LocalDate start = LocalDate.parse(range.getFirst()), end = LocalDate.parse(range.get(1));
    long days = ChronoUnit.DAYS.between(start, end) + 1;
    return checked(start.minusDays(days), start.minusDays(1));
  }

  private static List<String> checked(LocalDate start, LocalDate end) {
    long days = ChronoUnit.DAYS.between(start, end) + 1;
    if (days < 1 || days > 366) throw new IllegalArgumentException("开始日期不能晚于结束日期，单次最多查询 366 天。");
    return List.of(start.toString(), end.toString());
  }
}
