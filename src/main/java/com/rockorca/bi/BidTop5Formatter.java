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
  static String clip(Object value,int max){
    String text=Objects.toString(value,"").replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]"," ");
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
    if(!(snapshot.get("rows") instanceof List<?> rows))throw new IllegalArgumentException("没有可推送的快照");
    var result=new ArrayList<Map<String,String>>();
    for(String task:tasks){
      var rule=rules.stream().filter(r->task.equals(r.get("name"))).findFirst()
          .orElseThrow(()->new IllegalArgumentException("已选任务不存在，请重新选择并保存"));
      var selected=new ArrayList<Map<?,?>>();
      for(Object item:rows){
        if(!(item instanceof Map<?,?> row))throw new IllegalArgumentException("快照格式无效");
        String name=Objects.toString(row.get("media_account_name"),"").toLowerCase(Locale.ROOT);
        var matches=rules.stream().filter(r->name.contains(r.get("keyword").toString().toLowerCase(Locale.ROOT))).toList();
        if(matches.size()==1&&task.equals(matches.getFirst().get("name")))selected.add(row);
      }
      selected.sort(Comparator.<Map<?,?>,BigDecimal>comparing(r->number(r.get("stat_cost"))).reversed()
          .thenComparing(r->Objects.toString(r.get("promotion_id"),"")));
      StringBuilder text=new StringBuilder("【").append(clip(task,80)).append(" TOP5】");
      if(selected.isEmpty())text.append("\n当前采集范围内无匹配计划");
      int index=0;
      for(var row:selected.stream().limit(5).toList()){
        var metrics=metrics(row,number(rule.get("price")));
        text.append("\n").append(++index).append(". 消耗 ").append(money(number(row.get("stat_cost"))))
            .append(" | 回传 ").append(metrics.get("ratio"))
            .append(" | 出价 ").append(money(number(row.get("cpa_bid"))))
            .append(" | 出价利润 ").append(metrics.get("rate"))
            .append(" | 优化师 ").append(field(row.get("user_name")))
            .append(" | 账户ID ").append(field(row.get("media_account_id")))
            .append(" | 计划ID ").append(field(row.get("promotion_id")));
      }
      result.add(Map.of("task",task,"text",text.toString()));
    }
    return result;
  }
}
