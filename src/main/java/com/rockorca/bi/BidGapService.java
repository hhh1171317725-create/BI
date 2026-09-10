package com.rockorca.bi;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BidGapService {
  private static final int TASK_HISTORY_DAYS=30;
  private final ReportRepository repository;
  private final ReportService reports;
  private final BidAccountReferenceStore references;
  private final Map<String,Map<String,Object>> memory=new LinkedHashMap<>();
  public BidGapService(ReportRepository repository, ReportService reports) { this(repository,reports,null); }
  @org.springframework.beans.factory.annotation.Autowired
  public BidGapService(ReportRepository repository, ReportService reports,BidAccountReferenceStore references) { this.repository=repository; this.reports=reports; this.references=references; }

  public synchronized Map<String,Object> load(String endDate) {
    LocalDate.parse(endDate);
    if(references==null)return compute(endDate);
    for(int attempt=0;attempt<3;attempt++){
      String revision=references.revision(),key=endDate+":"+revision;
      if(memory.containsKey(key))return memory.get(key);
      Map<String,Object> data=references.read(endDate,revision);
      if(data==null){
        data=compute(endDate);
        if(!revision.equals(references.revision()))continue;
        data.put("preparedAt",java.time.Instant.now().toString());
        data.put("sourceRevision",revision);
        references.save(endDate,revision,data);
      }
      memory.put(key,data);
      while(memory.size()>8)memory.remove(memory.keySet().iterator().next());
      return data;
    }
    throw new IllegalStateException("日报正在更新，请稍后重试账户关联");
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=20000)
  public void prepareToday(){
    try{load(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());}
    catch(Exception error){org.slf4j.LoggerFactory.getLogger(BidGapService.class).warn("账户任务单价预计算失败，下次自动重试",error);}
  }

  private Map<String,Object> compute(String endDate) {
    LocalDate anchor=LocalDate.parse(endDate), start=anchor.minusDays(TASK_HISTORY_DAYS), end=anchor.minusDays(1);
    List<Map<String,Object>> rows=reports.buildDhhAccountRows(repository.readDhhRows(start.toString(),end.toString(),""));
    return calculate(rows,anchor);
  }

  static Map<String,Object> calculate(List<Map<String,Object>> rows,LocalDate anchor) {
    LocalDate start=anchor.minusDays(4), end=anchor.minusDays(2);
    LocalDate historyStart=anchor.minusDays(TASK_HISTORY_DAYS),historyEnd=anchor.minusDays(1);
    Map<String,Map<String,double[]>> history=new LinkedHashMap<>();
    Map<String,Map<String,DailyTotals>> accountDays=new LinkedHashMap<>(),taskDays=new LinkedHashMap<>();
    Map<String,Map<String,Map<String,DailyTotals>>> accountTaskDays=new LinkedHashMap<>();
    Map<String,Map<String,Map<String,Double>>> taskHistory=new LinkedHashMap<>();
    Map<String,Map<String,Map<String,Map<String,Double>>>> optimizerTaskHistory=new LinkedHashMap<>();
    for(var row:rows){
      String id=ReportService.text(row.get("账户ID")),date=ReportService.text(row.get("日期"));
      if(id.isBlank()||date.compareTo(historyStart.toString())<0||date.compareTo(historyEnd.toString())>0)continue;
      double[] historical=history.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new double[2]);
      historical[0]+=ReportService.number(row.get("预估佣金"));historical[1]+=ReportService.number(row.get("结算数"));
      addDaily(accountDays,id,date,row);
      String task=ReportService.text(row.get("任务名"));
      if(!task.isBlank()&&!List.of("未填写","--","-").contains(task)){
        addDaily(taskDays,task,date,row);
        addDaily(accountTaskDays.computeIfAbsent(id,k->new LinkedHashMap<>()),task,date,row);
        double spend=ReportService.number(row.get("消耗"));
        taskHistory.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new LinkedHashMap<>()).merge(task,spend,Double::sum);
        String optimizer=ReportService.text(row.get("优化师"));
        if(!optimizer.isBlank())optimizerTaskHistory.computeIfAbsent(id,k->new LinkedHashMap<>()).computeIfAbsent(optimizer,k->new LinkedHashMap<>())
            .computeIfAbsent(date,k->new LinkedHashMap<>()).merge(task,spend,Double::sum);
      }
    }
    Map<String,Object> accounts=new LinkedHashMap<>();
    history.forEach((id,historyDays)->{
      var summary=summarize(accountDays.getOrDefault(id,Map.of()),start,end);
      summary.put("dailyPrice",dailyPrice(accountDays.getOrDefault(id,Map.of()).get(end.toString()),end));
      Map<String,Object> pricesByTask=new LinkedHashMap<>();
      accountTaskDays.getOrDefault(id,Map.of()).forEach((task,days)->pricesByTask.put(task,dailyPrice(days.get(end.toString()),end)));
      summary.put("dailyPricesByTask",pricesByTask);
      String latest=historyDays.entrySet().stream().filter(entry->entry.getValue()[1]>0)
          .map(Map.Entry::getKey).max(String::compareTo).orElse(null);
      double[] values=latest==null?null:historyDays.get(latest);
      summary.put("settlementPrice",values==null?null:values[0]/values[1]);
      summary.put("settlementPriceDate",latest);summary.put("settlementCommission",values==null?null:values[0]);
      summary.put("settlementCount",values==null?null:values[1]);
      summary.putAll(taskSummary(taskHistory.getOrDefault(id,Map.of())));
      Map<String,Object> taskByOptimizer=new LinkedHashMap<>();
      optimizerTaskHistory.getOrDefault(id,Map.of()).forEach((optimizer,days)->taskByOptimizer.put(optimizer,taskSummary(days)));
      summary.put("taskByOptimizer",taskByOptimizer);accounts.put(id,summary);
    });
    Map<String,Object> tasks=new LinkedHashMap<>();
    taskDays.forEach((task,days)->{
      var summary=summarize(days,start,end);
      summary.put("dailyPrice",dailyPrice(days.get(end.toString()),end));
      tasks.put(task,summary);
    });
    return ReportService.mapOf("anchor",anchor.toString(),"start",start.toString(),"end",end.toString(),
        "historyStart",historyStart.toString(),"historyEnd",historyEnd.toString(),"accounts",accounts,
        "priceDate",end.toString(),"tasks",tasks,
        "basis","手动单价优先，未设置时使用统计结束日前第2天日报预估佣金合计÷结算数合计；账户无有效单价时可参考同任务该日单价。gap使用统计结束日前第4天至第2天有效日结算数÷注册数的均值，账户无有效gap时可参考同任务gap。任务识别使用此前30天内最近日报任务名，历史结算价只用于兼容旧任务识别，不作为自动单价。");
  }

  private static void addDaily(Map<String,Map<String,DailyTotals>> grouped,String key,String date,Map<String,Object> row){
    grouped.computeIfAbsent(key,k->new LinkedHashMap<>()).computeIfAbsent(date,k->new DailyTotals()).add(row);
  }

  private static Map<String,Object> summarize(Map<String,DailyTotals> days,LocalDate start,LocalDate end){
    List<Map<String,Object>> detail=new ArrayList<>();double sum=0;int valid=0;
    for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)){
      DailyTotals totals=days.get(date.toString());
      String reason=totals==null?"该日无日报数据":totals.gapReason();
      Double ratio=reason==null?totals.settlements/totals.registrations:null;
      if(ratio!=null){sum+=ratio;valid++;}
      detail.add(ReportService.mapOf("date",date.toString(),"settlements",totals==null||!totals.hasSettlements?null:totals.settlements,
          "registrations",totals==null||!totals.hasRegistrations?null:totals.registrations,"ratio",ratio,"reason",reason));
    }
    return ReportService.mapOf("gap",valid==0?null:sum/valid,"validDays",valid,"days",detail,
        "reason",valid==0?"gap区间没有注册数大于0且结算数有效的日报":null);
  }

  private static Map<String,Object> dailyPrice(DailyTotals totals,LocalDate date){
    String reason=totals==null?"前天（"+date+"）无日报数据":totals.priceReason();
    return ReportService.mapOf("date",date.toString(),"price",reason==null?totals.commission/totals.settlements:null,
        "commission",totals==null||!totals.hasCommission?null:totals.commission,
        "settlements",totals==null||!totals.hasSettlements?null:totals.settlements,
        "registrations",totals==null||!totals.hasRegistrations?null:totals.registrations,"reason",reason);
  }

  private static final class DailyTotals {
    private double commission,settlements,registrations;
    private boolean hasCommission=true,hasSettlements=true,hasRegistrations=true;

    private void add(Map<String,Object> row){
      Double currentCommission=numeric(row.get("预估佣金")),currentSettlements=numeric(row.get("结算数")),currentRegistrations=numeric(row.get("注册数"));
      if(currentCommission==null)hasCommission=false;else commission+=currentCommission;
      if(currentSettlements==null)hasSettlements=false;else settlements+=currentSettlements;
      if(currentRegistrations==null)hasRegistrations=false;else registrations+=currentRegistrations;
    }

    private String gapReason(){
      if(!hasRegistrations)return "注册数缺失或无效";
      if(registrations<=0)return "注册数不大于0";
      if(!hasSettlements||settlements<0)return "结算数缺失或无效";
      return null;
    }

    private String priceReason(){
      if(!hasSettlements)return "前天日报结算数缺失或无效";
      if(settlements<=0)return "前天日报结算数不大于0";
      if(!hasCommission||commission<0)return "前天日报预估佣金缺失或无效";
      return null;
    }

    private static Double numeric(Object value){
      if(value==null)return null;
      try{
        double number=Double.parseDouble(String.valueOf(value).replace(",","").trim());
        return Double.isFinite(number)?number:null;
      }catch(NumberFormatException ignored){return null;}
    }
  }

  private static Map<String,Object> taskSummary(Map<String,Map<String,Double>> history){
    String taskDate=history.keySet().stream().max(String::compareTo).orElse(null),taskName=null;
    List<Map<String,Object>> candidatesOutput=new ArrayList<>();
    if(taskDate!=null){
      var candidates=history.get(taskDate).entrySet().stream()
          .sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();
      for(var candidate:candidates)candidatesOutput.add(ReportService.mapOf("name",candidate.getKey(),"spend",candidate.getValue()));
      if(candidates.size()==1||candidates.getFirst().getValue()-candidates.get(1).getValue()>0.000001)taskName=candidates.getFirst().getKey();
    }
    return ReportService.mapOf("taskName",taskName,"taskDate",taskDate,"taskCandidates",candidatesOutput);
  }
}
