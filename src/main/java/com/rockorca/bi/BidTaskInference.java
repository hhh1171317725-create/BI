package com.rockorca.bi;

import java.math.BigDecimal;
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
      if(!(accountEntry instanceof Map<?,?> account))return;
      BigDecimal historical=positive(account.get("settlementPrice"));if(historical==null)return;
      var ranked=rules.stream().map(rule->new Candidate(rule,positive(rule.get("price"))))
          .filter(candidate->candidate.price()!=null)
          .map(candidate->new Scored(candidate,Math.abs(Math.log(candidate.price().doubleValue()/historical.doubleValue()))))
          .sorted(Comparator.comparingDouble(Scored::score)).toList();
      if(ranked.isEmpty())return;
      var best=ranked.getFirst();
      BigDecimal tolerance=historical.multiply(new BigDecimal("0.02")).max(new BigDecimal("0.02"));
      if(best.candidate().price().subtract(historical).abs().compareTo(tolerance)>0)return;
      if(ranked.size()>1&&ranked.get(1).score()-best.score()<.01)return;
      result.put(id,ReportService.mapOf("rule",best.candidate().rule(),"method","historical-settlement-price",
          "settlementPrice",historical,"settlementPriceDate",account.get("settlementPriceDate"),"matchedPrice",best.candidate().price()));
    });
    return result;
  }

  static Map<String,Object> nameRule(Map<?,?> row,List<Map<String,Object>> rules){
    String name=text(row.get("media_account_name")).toLowerCase(Locale.ROOT);
    var matches=rules.stream().filter(rule->{String keyword=text(rule.get("keyword")).toLowerCase(Locale.ROOT);return !keyword.isBlank()&&name.contains(keyword);}).toList();
    return matches.size()==1?matches.getFirst():null;
  }

  private static BigDecimal positive(Object value){var number=nonnegative(value);return number!=null&&number.signum()>0?number:null;}
  private static BigDecimal nonnegative(Object value){try{var number=new BigDecimal(text(value));return number.signum()>=0?number:null;}catch(Exception ignored){return null;}}
  private static String text(Object value){return Objects.toString(value,"").trim();}
  private record Candidate(Map<String,Object> rule,BigDecimal price){}
  private record Scored(Candidate candidate,double score){}
}
