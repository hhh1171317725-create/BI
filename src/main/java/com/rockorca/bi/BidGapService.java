package com.rockorca.bi;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BidGapService {
  private final ReportRepository repository;
  private final ReportService reports;
  public BidGapService(ReportRepository repository, ReportService reports) { this.repository=repository; this.reports=reports; }

  public Map<String,Object> load(String endDate) {
    LocalDate anchor=LocalDate.parse(endDate), start=anchor.minusDays(4), end=anchor.minusDays(2);
    List<Map<String,Object>> rows=reports.buildDhhAccountRows(repository.readDhhRows(start.toString(),end.toString(),""));
    return calculate(rows,anchor);
  }

  static Map<String,Object> calculate(List<Map<String,Object>> rows,LocalDate anchor) {
    LocalDate start=anchor.minusDays(4), end=anchor.minusDays(2);
    Map<String,Map<String,double[]>> grouped=new LinkedHashMap<>();
    Map<String,Map<String,Map<String,double[]>>> taskGrouped=new LinkedHashMap<>();
    for(var row:rows){
      String id=ReportService.text(row.get("账户ID")),date=ReportService.text(row.get("日期"));
      if(id.isBlank()||date.compareTo(start.toString())<0||date.compareTo(end.toString())>0)continue;
      double[] totals=grouped.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new double[2]);
      totals[0]+=ReportService.number(row.get("结算数"));totals[1]+=ReportService.number(row.get("注册数"));
      String task=ReportService.text(row.get("任务名"));
      if(!task.isBlank()){
        double[] taskTotals=taskGrouped.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(task,k->new LinkedHashMap<>())
            .computeIfAbsent(date,k->new double[2]);
        taskTotals[0]+=ReportService.number(row.get("结算数"));taskTotals[1]+=ReportService.number(row.get("注册数"));
      }
    }
    Map<String,Object> accounts=new LinkedHashMap<>();
    grouped.forEach((id,days)->{
      var summary=summarize(days,start,end);
      List<Map<String,Object>> tasks=new ArrayList<>();
      taskGrouped.getOrDefault(id,Map.of()).forEach((name,taskDays)->{
        var taskSummary=summarize(taskDays,start,end);
        double settlements=taskDays.values().stream().mapToDouble(v->v[0]).sum();
        double registrations=taskDays.values().stream().mapToDouble(v->v[1]).sum();
        tasks.add(ReportService.mapOf("name",name,"settlements",settlements,"registrations",registrations,
            "gap",taskSummary.get("gap"),"validDays",taskSummary.get("validDays")));
      });
      tasks.sort(Comparator.<Map<String,Object>>comparingDouble(v->-ReportService.number(v.get("registrations")))
          .thenComparing(v->ReportService.text(v.get("name"))));
      summary.put("tasks",tasks);accounts.put(id,summary);
    });
    return ReportService.mapOf("anchor",anchor.toString(),"start",start.toString(),"end",end.toString(),"accounts",accounts,
        "basis","使用统计结束日前一天之前的3天（不含前一天）；大航海日报账户分摊口径；每日汇总结算数÷注册数后取算术平均；无数据或注册数为0的日期不参与平均");
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
