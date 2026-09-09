package com.rockorca.bi;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BidGapService {
  private static final int TASK_HISTORY_DAYS=30;
  private final ReportRepository repository;
  private final ReportService reports;
  public BidGapService(ReportRepository repository, ReportService reports) { this.repository=repository; this.reports=reports; }

  public Map<String,Object> load(String endDate) {
    LocalDate anchor=LocalDate.parse(endDate), start=anchor.minusDays(TASK_HISTORY_DAYS), end=anchor.minusDays(1);
    List<Map<String,Object>> rows=reports.buildDhhAccountRows(repository.readDhhRows(start.toString(),end.toString(),""));
    return calculate(rows,anchor);
  }

  static Map<String,Object> calculate(List<Map<String,Object>> rows,LocalDate anchor) {
    LocalDate start=anchor.minusDays(4), end=anchor.minusDays(2);
    LocalDate historyStart=anchor.minusDays(TASK_HISTORY_DAYS),historyEnd=anchor.minusDays(1);
    Map<String,Map<String,double[]>> grouped=new LinkedHashMap<>(),history=new LinkedHashMap<>();
    for(var row:rows){
      String id=ReportService.text(row.get("账户ID")),date=ReportService.text(row.get("日期"));
      if(id.isBlank()||date.compareTo(historyStart.toString())<0||date.compareTo(historyEnd.toString())>0)continue;
      double[] historical=history.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new double[2]);
      historical[0]+=ReportService.number(row.get("预估佣金"));historical[1]+=ReportService.number(row.get("结算数"));
      if(date.compareTo(start.toString())>=0&&date.compareTo(end.toString())<=0){
        double[] totals=grouped.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new double[2]);
        totals[0]+=ReportService.number(row.get("结算数"));totals[1]+=ReportService.number(row.get("注册数"));
      }
    }
    Map<String,Object> accounts=new LinkedHashMap<>();
    history.forEach((id,historyDays)->{
      var summary=summarize(grouped.getOrDefault(id,Map.of()),start,end);
      String latest=historyDays.entrySet().stream().filter(entry->entry.getValue()[1]>0)
          .map(Map.Entry::getKey).max(String::compareTo).orElse(null);
      double[] values=latest==null?null:historyDays.get(latest);
      summary.put("settlementPrice",values==null?null:values[0]/values[1]);
      summary.put("settlementPriceDate",latest);summary.put("settlementCommission",values==null?null:values[0]);
      summary.put("settlementCount",values==null?null:values[1]);accounts.put(id,summary);
    });
    return ReportService.mapOf("anchor",anchor.toString(),"start",start.toString(),"end",end.toString(),
        "historyStart",historyStart.toString(),"historyEnd",historyEnd.toString(),"accounts",accounts,
        "basis","gap使用统计结束日前第4天至第2天；任务识别查询统计日前30天，取账户最近一个有结算的日报日期，以预估佣金÷结算数得到历史结算单价并匹配任务价格");
  }

  private static Map<String,Object> summarize(Map<String,double[]> days,LocalDate start,LocalDate end){
    List<Map<String,Object>> detail=new ArrayList<>();double sum=0;int valid=0;
    for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)){
      double[] totals=days.get(date.toString());
      Double ratio=totals!=null&&totals[1]>0&&totals[0]>=0?totals[0]/totals[1]:null;
      if(ratio!=null){sum+=ratio;valid++;}
      detail.add(ReportService.mapOf("date",date.toString(),"settlements",totals==null?null:totals[0],
          "registrations",totals==null?null:totals[1],"ratio",ratio));
    }
    return ReportService.mapOf("gap",valid==0?null:sum/valid,"validDays",valid,"days",detail);
  }
}
