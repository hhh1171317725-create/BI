package com.rockorca.bi;

import java.math.BigDecimal;
import java.util.*;

final class BidTaskInference {
  private BidTaskInference(){}

  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps){
    if(accountGaps==null)return Map.of();
    Map<String,Object> gapIndex=new HashMap<>();
    accountGaps.forEach((id,value)->{gapIndex.put(id,value);String canonical=canonicalId(id);if(!canonical.isBlank())gapIndex.put(canonical,value);});
    Map<String,List<Map<?,?>>> grouped=new LinkedHashMap<>();
    for(Object item:rows){
      if(!(item instanceof Map<?,?> row)||!"gdt".equalsIgnoreCase(text(row.get("source_platform"))))continue;
      String id=inferenceKey(row);if(!id.isBlank())grouped.computeIfAbsent(id,k->new ArrayList<>()).add(row);
    }
    Map<String,Map<String,Object>> result=new LinkedHashMap<>();
    grouped.forEach((id,items)->{
      Object accountEntry=gapEntry(rowIds(items.getFirst()),gapIndex);
      if(!(accountEntry instanceof Map<?,?> account))return;
      Map<String,Object> reportedRule=taskRule(text(account.get("taskName")),rules);
      if(reportedRule!=null){
        result.put(id,ReportService.mapOf("rule",reportedRule,"method","daily-report-task",
            "reportedTaskName",account.get("taskName"),"taskDate",account.get("taskDate")));
        return;
      }
      if(nameRule(items.getFirst(),rules)!=null)return;
      BigDecimal historical=positive(account.get("settlementPrice"));if(historical==null)return;
      var ranked=rules.stream().map(rule->new Candidate(rule,positive(rule.get("price"))))
          .filter(candidate->candidate.price()!=null)
          .map(candidate->new Scored(candidate,candidate.price().subtract(historical).abs()))
          .sorted(Comparator.comparing(Scored::difference)).toList();
      if(ranked.isEmpty())return;
      var best=ranked.getFirst();
      if(ranked.size()>1&&ranked.get(1).difference().subtract(best.difference()).abs().compareTo(new BigDecimal("0.000001"))<=0)return;
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

  private static Map<String,Object> taskRule(String reported,List<Map<String,Object>> rules){
    String task=reported.toLowerCase(Locale.ROOT);if(task.isBlank())return null;
    var exact=rules.stream().filter(rule->text(rule.get("name")).toLowerCase(Locale.ROOT).equals(task)).toList();
    if(exact.size()==1)return exact.getFirst();
    var contained=rules.stream().filter(rule->{String name=text(rule.get("name")).toLowerCase(Locale.ROOT);return !name.isBlank()&&(name.contains(task)||task.contains(name));}).toList();
    return contained.size()==1?contained.getFirst():null;
  }

  static String inferenceKey(Map<?,?> row){String advertiser=text(row.get("advertiser_id"));return advertiser.isBlank()?text(row.get("media_account_id")):advertiser;}

  static Object accountEntry(Map<?,?> row,Map<String,Object> accountGaps){
    for(String id:rowIds(row)){
      Object value=accountGaps.get(id);if(value!=null)return value;
      String canonical=canonicalId(id);
      for(var entry:accountGaps.entrySet())if(canonical.equals(canonicalId(entry.getKey())))return entry.getValue();
    }
    return null;
  }

  private static BigDecimal positive(Object value){var number=nonnegative(value);return number!=null&&number.signum()>0?number:null;}
  private static BigDecimal nonnegative(Object value){try{var number=new BigDecimal(text(value));return number.signum()>=0?number:null;}catch(Exception ignored){return null;}}
  private static String text(Object value){return Objects.toString(value,"").trim();}
  private static List<String> rowIds(Map<?,?> row){return List.of(text(row.get("advertiser_id")),text(row.get("media_account_id")));}
  private static Object gapEntry(List<String> ids,Map<String,Object> index){for(String id:ids){Object value=index.get(id);if(value==null)value=index.get(canonicalId(id));if(value!=null)return value;}return null;}
  private static String canonicalId(String id){return id.replaceFirst("\\.0+$","").replaceFirst("^0+(?=\\d)","");}
  private record Candidate(Map<String,Object> rule,BigDecimal price){}
  private record Scored(Candidate candidate,BigDecimal difference){}
}
