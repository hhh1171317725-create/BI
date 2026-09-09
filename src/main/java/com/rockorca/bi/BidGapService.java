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
    Map<String,Map<String,Map<String,Double>>> taskHistory=new LinkedHashMap<>();
    for(var row:rows){
      String id=ReportService.text(row.get("账户ID")),date=ReportService.text(row.get("日期"));
      if(id.isBlank()||date.compareTo(historyStart.toString())<0||date.compareTo(historyEnd.toString())>0)continue;
      double[] historical=history.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new double[2]);
      historical[0]+=ReportService.number(row.get("预估佣金"));historical[1]+=ReportService.number(row.get("结算数"));
      String task=ReportService.text(row.get("任务名"));
      if(!task.isBlank()&&!List.of("未填写","--","-").contains(task))taskHistory.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new LinkedHashMap<>())
          .merge(task,ReportService.number(row.get("消耗")),Double::sum);
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
      summary.put("settlementCount",values==null?null:values[1]);
      var accountTasks=taskHistory.getOrDefault(id,Map.of());
      String taskDate=accountTasks.keySet().stream().max(String::compareTo).orElse(null),taskName=null;
      List<Map<String,Object>> taskCandidates=new ArrayList<>();
      if(taskDate!=null){
        var candidates=accountTasks.get(taskDate).entrySet().stream()
            .sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();
        for(var candidate:candidates)taskCandidates.add(ReportService.mapOf("name",candidate.getKey(),"spend",candidate.getValue()));
        if(candidates.size()==1||candidates.getFirst().getValue()-candidates.get(1).getValue()>0.000001)taskName=candidates.getFirst().getKey();
      }
      summary.put("taskName",taskName);summary.put("taskDate",taskDate);summary.put("taskCandidates",taskCandidates);accounts.put(id,summary);
    });
    return ReportService.mapOf("anchor",anchor.toString(),"start",start.toString(),"end",end.toString(),
        "historyStart",historyStart.toString(),"historyEnd",historyEnd.toString(),"accounts",accounts,
        "basis","gap使用统计结束日前第4天至第2天；广点通任务优先使用统计日前30天内该账户最近一日报记录的任务名，同日多个任务时取账户消耗最高且唯一的任务；任务名无法匹配时再以历史结算单价匹配");
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
