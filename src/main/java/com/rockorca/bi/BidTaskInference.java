package com.rockorca.bi;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

final class BidTaskInference {
  private BidTaskInference(){}

  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps){
    if(accountGaps==null)return Map.of();
    Map<String,List<Map<?,?>>> grouped=new LinkedHashMap<>();
    for(Object item:rows){
      if(!(item instanceof Map<?,?> row)||!"gdt".equalsIgnoreCase(text(row.get("source_platform"))))continue;
      if(nameRule(row,rules)!=null)continue;
      String id=text(row.get("advertiser_id"));if(!id.isBlank())grouped.computeIfAbsent(id,k->new ArrayList<>()).add(row);
    }
    Map<String,Map<String,Object>> result=new LinkedHashMap<>();
    grouped.forEach((id,items)->{
      Object accountEntry=accountGaps.get(id);
      if(!(accountEntry instanceof Map<?,?> account)||!(account.get("tasks") instanceof List<?> evidence))return;
      var candidates=new LinkedHashMap<String,Candidate>();
      for(Object value:evidence){
        if(!(value instanceof Map<?,?> task))continue;
        Map<String,Object> rule=taskRule(text(task.get("name")),rules);if(rule==null)continue;
        BigDecimal price=positive(rule.get("price")),gap=nonnegative(task.get("gap"));
        if(price!=null)candidates.putIfAbsent(text(rule.get("name")).toLowerCase(Locale.ROOT),new Candidate(rule,task,gap==null?null:price.multiply(gap)));
      }
      if(candidates.isEmpty())return;
      BigDecimal registrations=sum(items,"active_register"),conversions=sum(items,"convert_cnt"),bidCost=BigDecimal.ZERO;
      for(var row:items){BigDecimal bid=nonnegative(row.get("cpa_bid")),conversion=nonnegative(row.get("convert_cnt"));if(bid!=null&&conversion!=null)bidCost=bidCost.add(bid.multiply(conversion));}
      BigDecimal weighted=conversions.signum()>0?bidCost.divide(conversions,MathContext.DECIMAL64):null;
      BigDecimal ratio=registrations.signum()>0?conversions.divide(registrations,MathContext.DECIMAL64):null;
      BigDecimal implied=weighted!=null&&ratio!=null?weighted.multiply(ratio):null;
      Candidate chosen=null;String method="settlement-only";
      if(candidates.size()==1)chosen=candidates.values().iterator().next();
      else if(implied!=null&&implied.signum()>0){
        var ranked=candidates.values().stream().filter(c->c.expected()!=null&&c.expected().signum()>0)
            .map(c->new Scored(c,Math.abs(Math.log(c.expected().doubleValue()/implied.doubleValue())))).sorted(Comparator.comparingDouble(Scored::score)).toList();
        if(!ranked.isEmpty()&&(ranked.size()==1||ranked.get(1).score()-ranked.getFirst().score()>=.05)){chosen=ranked.getFirst().candidate();method="price-match";}
      }
      if(chosen!=null)result.put(id,ReportService.mapOf("rule",chosen.rule(),"method",method,"weightedBid",weighted,
          "callbackRatio",ratio,"impliedSettlementUnit",implied,"expectedSettlementUnit",chosen.expected()));
    });
    return result;
  }

  static Map<String,Object> nameRule(Map<?,?> row,List<Map<String,Object>> rules){
    String name=text(row.get("media_account_name")).toLowerCase(Locale.ROOT);
    var matches=rules.stream().filter(rule->{String keyword=text(rule.get("keyword")).toLowerCase(Locale.ROOT);return !keyword.isBlank()&&name.contains(keyword);}).toList();
    return matches.size()==1?matches.getFirst():null;
  }

  private static Map<String,Object> taskRule(String evidence,List<Map<String,Object>> rules){
    String name=evidence.strip().toLowerCase(Locale.ROOT);if(name.isBlank())return null;
    var exact=rules.stream().filter(rule->text(rule.get("name")).toLowerCase(Locale.ROOT).equals(name)).toList();
    if(exact.size()==1)return exact.getFirst();
    var contained=rules.stream().filter(rule->{String value=text(rule.get("name")).toLowerCase(Locale.ROOT);return !value.isBlank()&&(value.contains(name)||name.contains(value));}).toList();
    return contained.size()==1?contained.getFirst():null;
  }

  private static BigDecimal sum(List<Map<?,?>> rows,String key){BigDecimal value=BigDecimal.ZERO;for(var row:rows){var number=nonnegative(row.get(key));if(number!=null)value=value.add(number);}return value;}
  private static BigDecimal positive(Object value){var number=nonnegative(value);return number!=null&&number.signum()>0?number:null;}
  private static BigDecimal nonnegative(Object value){try{var number=new BigDecimal(text(value));return number.signum()>=0?number:null;}catch(Exception ignored){return null;}}
  private static String text(Object value){return Objects.toString(value,"").trim();}
  private record Candidate(Map<String,Object> rule,Map<?,?> evidence,BigDecimal expected){}
  private record Scored(Candidate candidate,double score){}
}
