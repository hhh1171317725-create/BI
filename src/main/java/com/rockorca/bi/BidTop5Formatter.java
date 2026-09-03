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
    String text=Objects.toString(value,"").replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}|]"," ");
    return text.codePointCount(0,text.length())>max?text.substring(0,text.offsetByCodePoints(0,max))+"…":text;
  }
  static String field(Object value){
    String text=clip(value,1000).strip();
    return text.isBlank()?"--":text;
  }
  static int columnWidth(String text){
    return text.codePoints().map(c->{
      if(Character.getType(c)==Character.NON_SPACING_MARK||Character.getType(c)==Character.FORMAT)return 0;
      var script=Character.UnicodeScript.of(c);
      return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HANGUL
          ||script==Character.UnicodeScript.HIRAGANA||script==Character.UnicodeScript.KATAKANA
          ||(c>=0xff01&&c<=0xff60)||(c>=0x1f300&&c<=0x1faff)?2:1;
    }).sum();
  }
  static String alignedTable(List<List<String>> rows){
    int[] widths=new int[rows.getFirst().size()];
    for(var row:rows)for(int i=0;i<widths.length;i++)widths[i]=Math.max(widths[i],columnWidth(row.get(i)));
    var lines=new ArrayList<String>();
    for(int r=0;r<rows.size();r++){
      var cells=new ArrayList<String>();
      for(int i=0;i<widths.length;i++){
        String value=rows.get(r).get(i),pad=" ".repeat(widths[i]-columnWidth(value));
        cells.add(r>0&&i>=1&&i<=5?pad+value:value+pad);
      }
      lines.add(String.join(" | ",cells).stripTrailing());
    }
    return String.join("\n",lines);
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
    var table=new ArrayList<List<String>>();
    table.add(List.of("任务","排名","消耗","回传比例","出价","出价利润率","优化师","账户ID","计划ID"));
    boolean missingOptimizer=false;
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
      if(selected.isEmpty())table.add(List.of(clip(task,80),"--","无匹配计划","--","--","--","--","--","--"));
      int index=0;
      for(var row:selected.stream().limit(5).toList()){
        var metrics=metrics(row,number(rule.get("price")));
        String optimizer=field(row.get("user_name"));missingOptimizer|=optimizer.equals("--");
        table.add(List.of(clip(task,80),Integer.toString(++index),money(number(row.get("stat_cost"))),
            metrics.get("ratio"),money(number(row.get("cpa_bid"))),metrics.get("rate"),optimizer,
            field(row.get("media_account_id")),field(row.get("promotion_id"))));
      }
    }
    return List.of(Map.of("text",alignedTable(table),"missingOptimizer",Boolean.toString(missingOptimizer)));
  }
}
