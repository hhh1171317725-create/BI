package com.rockorca.bi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

final class BidTop5Formatter {
  private BidTop5Formatter(){}
  static BigDecimal number(Object value){
    try{var n=new BigDecimal(Objects.toString(value,""));if(n.signum()<0)throw new NumberFormatException();return n;}
    catch(NumberFormatException error){throw new IllegalArgumentException("快照指标缺失或无效，请重新同步");}
  }
  static String money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP).toPlainString();}
  static String displayMoney(BigDecimal value){return String.format(Locale.ROOT,"%,.2f",value);}
  static String rank(int value){return new String[]{"①","②","③","④","⑤"}[value-1];}
  static String clip(Object value,int max){
    String text=Objects.toString(value,"").replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}|]"," ");
    return text.codePointCount(0,text.length())>max?text.substring(0,text.offsetByCodePoints(0,max))+"…":text;
  }
  static String field(Object value){
    String text=clip(value,1000).strip();
    return text.isBlank()?"--":text;
  }
  static Map<String,String> metrics(Map<?,?> row,BigDecimal price){
    var cost=number(row.get("stat_cost"));var conv=number(row.get("convert_cnt"));
    var reg=number(row.get("active_register"));var bid=number(row.get("cpa_bid"));
    var commission=reg.multiply(price);var bidCost=bid.multiply(conv);
    boolean eligible=conv.compareTo(BigDecimal.valueOf(6))>0&&cost.compareTo(bidCost.multiply(new BigDecimal("1.2")))>0;
    var grant=eligible?cost.subtract(bidCost):BigDecimal.ZERO;
    var cash=cost.subtract(grant);
    String roi=cash.signum()>0?commission.divide(cash,3,RoundingMode.HALF_UP).toPlainString():"--";
    String line=commission.signum()>0&&conv.signum()>0?commission.divide(conv,2,RoundingMode.HALF_UP).toPlainString():"--";
    String rate=commission.signum()>0&&conv.signum()>0?commission.subtract(bidCost).multiply(BigDecimal.valueOf(100)).divide(commission,2,RoundingMode.HALF_UP).toPlainString()+"%":"--";
    String ratio=reg.signum()>0?conv.multiply(BigDecimal.valueOf(100)).divide(reg,2,RoundingMode.HALF_UP).toPlainString()+"%":"--";
    return Map.of("roi",roi,"line",line,"rate",rate,"ratio",ratio,"cash",money(cash),"grant",money(grant),"commission",money(commission));
  }

  static List<Map<String,String>> messages(Map<String,Object> snapshot,List<Map<String,Object>> rules,List<String> tasks){
    return messages(snapshot,rules,tasks,null);
  }
  @SuppressWarnings("unchecked")
  static List<Map<String,String>> messages(Map<String,Object> snapshot,List<Map<String,Object>> rules,List<String> tasks,Map<String,Object> gapPayload){
    if(!(snapshot.get("rows") instanceof List<?> rows))throw new IllegalArgumentException("没有可推送的快照");
    Map<String,Object> accountGaps=gapPayload!=null&&gapPayload.get("accounts") instanceof Map<?,?> accounts
        ?(Map<String,Object>)accounts:gapPayload;
    Map<String,Object> taskGaps=gapPayload!=null&&gapPayload.get("tasks") instanceof Map<?,?> taskEntries
        ?(Map<String,Object>)taskEntries:Map.of();
    String priceDate=gapPayload==null?"":Objects.toString(gapPayload.get("priceDate"),"");
    var inferred=BidTaskInference.infer(rows,rules,accountGaps);
    var groups=new ArrayList<String>();
    boolean missingOptimizer=false,missingAccountId=false;
    for(String task:tasks){
      var rule=rules.stream().filter(r->task.equals(r.get("name"))).findFirst()
          .orElseThrow(()->new IllegalArgumentException("已选任务不存在，请重新选择并保存"));
      var selected=new ArrayList<Map<?,?>>();
      for(Object item:rows){
        if(!(item instanceof Map<?,?> row))throw new IllegalArgumentException("快照格式无效");
        var matched=BidTaskInference.nameRule(row,rules);String inferredSource="";
        if("gdt".equalsIgnoreCase(Objects.toString(row.get("source_platform"),""))){
          Object match=inferred.get(BidTaskInference.inferenceIdentity(row));
          if(match instanceof Map<?,?> detail&&detail.get("rule") instanceof Map<?,?> inferredRule){
            String method=Objects.toString(detail.get("method"),"");
            if("daily-report-task".equals(method)||matched==null){
              @SuppressWarnings("unchecked") var cast=(Map<String,Object>)inferredRule;matched=cast;inferredSource=method;
            }
          }
        }
        if(matched!=null&&task.equals(matched.get("name"))){
          if(!inferredSource.isBlank()){var copy=new LinkedHashMap<Object,Object>(row);copy.put("task_source",inferredSource);selected.add(copy);}else selected.add(row);
        }
      }
      selected.sort(Comparator.<Map<?,?>,BigDecimal>comparing(r->number(r.get("stat_cost"))).reversed()
          .thenComparing(r->Objects.toString(r.get("promotion_id"),"")));
      var entries=new ArrayList<String>();
      if(selected.isEmpty())entries.add("暂无匹配计划");
      int index=0;
      for(var row:selected.stream().limit(5).toList()){
        Object entry=accountGaps==null?null:BidTaskInference.accountEntry(row,accountGaps);
        Map<?,?> account=entry instanceof Map<?,?> value?value:Map.of();
        Map<?,?> taskEntry=namedEntry(taskGaps,task);
        BigDecimal basePrice=optionalNumber(rule.get("price"));String priceSource="手动单价";
        if(basePrice==null){
          Map<?,?> daily=accountDailyPrice(account,task);
          if(!usableDailyPrice(daily,priceDate)){daily=dailyPrice(taskEntry);priceSource="同任务前天日报价";}
          else priceSource="账户前天日报价";
          basePrice=usableDailyPrice(daily,priceDate)?optionalNumber(daily.get("price")):null;
        }
        BigDecimal gap=gapPayload==null?BigDecimal.ONE:optionalNumber(account.get("gap"));String gapSource="";
        if(gap==null){gap=optionalNumber(taskEntry.get("gap"));if(gap!=null)gapSource="｜同任务gap";}
        boolean priceMissing=basePrice==null,gapMissing=gap==null;
        BigDecimal effective=priceMissing||gapMissing?BigDecimal.ZERO:basePrice.multiply(gap);
        var metrics=metrics(row,effective);
        String optimizer=field(row.get("user_name"));missingOptimizer|=optimizer.equals("--");
        String accountId=field(row.get("advertiser_id"));missingAccountId|=accountId.equals("--");
        entries.add(rank(++index)+" 利润出价"+metrics.get("rate")
            +"｜消耗"+displayMoney(number(row.get("stat_cost")))+"｜回传"+metrics.get("ratio")
            +"｜出价"+displayMoney(number(row.get("cpa_bid")))+("daily-report-task".equals(row.get("task_source"))?"｜日报任务":"historical-settlement-price".equals(row.get("task_source"))?"｜历史结算价反推":"")
            +(priceMissing?"｜单价缺失":"手动单价".equals(priceSource)?"":"｜"+priceSource)+(gapMissing?"｜gap缺失":gapSource)
            +"\n   "+optimizer+"｜账"+accountId+"｜计"+field(row.get("promotion_id")));
      }
      groups.add("【"+clip(task,80)+" TOP5】\n"+String.join("\n",entries));
    }
    return List.of(Map.of("text",String.join("\n\n",groups),"missingOptimizer",Boolean.toString(missingOptimizer),"missingAccountId",Boolean.toString(missingAccountId)));
  }

  private static BigDecimal optionalNumber(Object value){
    try{
      String text=Objects.toString(value,"").trim();if(text.isBlank())return null;
      var number=new BigDecimal(text);return number.signum()>=0?number:null;
    }catch(NumberFormatException ignored){return null;}
  }
  private static Map<?,?> namedEntry(Map<String,Object> entries,String name){
    for(var entry:entries.entrySet())if(entry.getKey().trim().equalsIgnoreCase(name.trim())&&entry.getValue() instanceof Map<?,?> value)return value;
    return Map.of();
  }
  private static Map<?,?> dailyPrice(Map<?,?> entry){return entry.get("dailyPrice") instanceof Map<?,?> value?value:Map.of();}
  private static Map<?,?> accountDailyPrice(Map<?,?> account,String task){
    if(account.get("dailyPricesByTask") instanceof Map<?,?> split){
      for(var entry:split.entrySet())if(Objects.toString(entry.getKey(),"").trim().equalsIgnoreCase(task.trim())&&entry.getValue() instanceof Map<?,?> value)return value;
      if(!split.isEmpty())return Map.of();
    }
    return dailyPrice(account);
  }
  private static boolean usableDailyPrice(Map<?,?> daily,String expectedDate){
    return !expectedDate.isBlank()&&expectedDate.equals(Objects.toString(daily.get("date"),""))&&optionalNumber(daily.get("price"))!=null;
  }
}
