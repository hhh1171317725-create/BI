package com.rockorca.bi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

final class BidTaskInference {
  private BidTaskInference(){}

  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps){
    return infer(rows,rules,accountGaps,true);
  }

  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps,boolean allowHistoricalPrice){
    if(accountGaps==null)return Map.of();
    Map<String,Object> gapIndex=new HashMap<>();
    accountGaps.forEach((id,value)->{gapIndex.put(id,value);String canonical=canonicalId(id);if(!canonical.isBlank())gapIndex.put(canonical,value);});
    Map<String,List<Map<?,?>>> grouped=new LinkedHashMap<>();
    for(Object item:rows){
      if(!(item instanceof Map<?,?> row)||!"gdt".equalsIgnoreCase(text(row.get("source_platform"))))continue;
      String id=inferenceKey(row);if(!id.isBlank())grouped.computeIfAbsent(inferenceIdentity(row),k->new ArrayList<>()).add(row);
    }
    Map<String,Map<String,Object>> result=new LinkedHashMap<>();
    grouped.forEach((id,items)->{
      Object accountEntry=gapEntry(rowIds(items.getFirst()),gapIndex);
      if(!(accountEntry instanceof Map<?,?> account))return;
      Map<?,?> taskEvidence=taskEvidence(account,items.getFirst());
      Map<String,Object> reportedRule=taskRule(text(taskEvidence.get("taskName")),rules);
      if(reportedRule!=null){
        result.put(id,ReportService.mapOf("rule",reportedRule,"method","daily-report-task",
            "reportedTaskName",taskEvidence.get("taskName"),"taskDate",taskEvidence.get("taskDate")));
        return;
      }
      if(nameRule(items.getFirst(),rules)!=null)return;
      if(!allowHistoricalPrice)return;
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

  static Map<String,Object> bidReturnRule(Map<?,?> row,List<Map<String,Object>> rules,Map<String,Object> accountGaps,
      Map<String,Object> taskEntries,String priceDate){
    if(nameMatches(row,rules).size()>1)return null;
    BigDecimal bid=nonnegative(row.get("cpa_bid")),conversions=nonnegative(row.get("convert_cnt")),registrations=positive(row.get("active_register"));
    if(bid==null||conversions==null||registrations==null)return null;
    BigDecimal estimated=bid.multiply(conversions).divide(registrations,12,RoundingMode.HALF_UP);
    Map<?,?> account=accountGaps!=null&&accountEntry(row,accountGaps) instanceof Map<?,?> value?value:Map.of();
    var candidates=new ArrayList<EstimateCandidate>();
    for(var rule:rules){
      String task=text(rule.get("name"));if(task.isBlank())continue;
      Map<?,?> taskEntry=namedEntry(taskEntries,task);
      BigDecimal base=positive(rule.get("price"));
      if(base==null){
        Map<?,?> daily=accountDailyPrice(account,task);
        if(!usableDailyPrice(daily,priceDate))daily=dailyPrice(taskEntry);
        base=usableDailyPrice(daily,priceDate)?nonnegative(daily.get("price")):null;
      }
      BigDecimal gap=nonnegative(account.get("gap"));if(gap==null)gap=nonnegative(taskEntry.get("gap"));
      if(base==null||gap==null)continue;
      BigDecimal actual=base.multiply(gap);
      candidates.add(new EstimateCandidate(rule,actual,actual.subtract(estimated).abs()));
    }
    candidates.sort(Comparator.comparing(EstimateCandidate::difference).thenComparing(candidate->text(candidate.rule().get("name"))));
    if(candidates.isEmpty()||candidates.size()>1&&candidates.get(1).difference().subtract(candidates.getFirst().difference()).abs().compareTo(new BigDecimal("0.000001"))<=0)return null;
    var best=candidates.getFirst();
    return ReportService.mapOf("rule",best.rule(),"method","bid-return-estimate","estimatedSettlementPrice",estimated,
        "matchedActualPrice",best.actualPrice(),"difference",best.difference(),"returnRatio",conversions.divide(registrations,12,RoundingMode.HALF_UP));
  }

  static Map<String,Object> nameRule(Map<?,?> row,List<Map<String,Object>> rules){
    var matches=nameMatches(row,rules);
    return matches.size()==1?matches.getFirst():null;
  }

  private static List<Map<String,Object>> nameMatches(Map<?,?> row,List<Map<String,Object>> rules){
    String name=text(row.get("media_account_name")).toLowerCase(Locale.ROOT);
    return rules.stream().filter(rule->{String keyword=text(rule.get("keyword")).toLowerCase(Locale.ROOT);return !keyword.isBlank()&&name.contains(keyword);}).toList();
  }

  private static Map<String,Object> taskRule(String reported,List<Map<String,Object>> rules){
    String task=reported.toLowerCase(Locale.ROOT);if(task.isBlank())return null;
    var exact=rules.stream().filter(rule->text(rule.get("name")).toLowerCase(Locale.ROOT).equals(task)).toList();
    if(exact.size()==1)return exact.getFirst();
    var contained=rules.stream().filter(rule->{String name=text(rule.get("name")).toLowerCase(Locale.ROOT);return !name.isBlank()&&(name.contains(task)||task.contains(name));}).toList();
    return contained.size()==1?contained.getFirst():null;
  }

  private static Map<?,?> taskEvidence(Map<?,?> account,Map<?,?> row){
    String optimizer=text(row.get("user_name"));
    if(account.get("taskByOptimizer") instanceof Map<?,?> byOptimizer&&!optimizer.isBlank()){
      for(var entry:byOptimizer.entrySet())if(text(entry.getKey()).equalsIgnoreCase(optimizer)&&entry.getValue() instanceof Map<?,?> value&& !text(value.get("taskName")).isBlank())return value;
    }
    return account;
  }

  static String inferenceKey(Map<?,?> row){String advertiser=text(row.get("advertiser_id"));return advertiser.isBlank()?text(row.get("media_account_id")):advertiser;}
  static String inferenceIdentity(Map<?,?> row){return inferenceKey(row)+"\u0000"+text(row.get("user_name")).toLowerCase(Locale.ROOT);}

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
  private static Map<?,?> namedEntry(Map<String,Object> entries,String name){
    if(entries==null)return Map.of();
    for(var entry:entries.entrySet())if(entry.getKey().trim().equalsIgnoreCase(name.trim())&&entry.getValue() instanceof Map<?,?> value)return value;
    return Map.of();
  }
  private static Map<?,?> dailyPrice(Map<?,?> entry){return entry.get("dailyPrice") instanceof Map<?,?> value?value:Map.of();}
  private static Map<?,?> accountDailyPrice(Map<?,?> account,String task){
    if(account.get("dailyPricesByTask") instanceof Map<?,?> split){
      for(var entry:split.entrySet())if(text(entry.getKey()).equalsIgnoreCase(task.trim())&&entry.getValue() instanceof Map<?,?> value)return value;
      if(!split.isEmpty())return Map.of();
    }
    return dailyPrice(account);
  }
  private static boolean usableDailyPrice(Map<?,?> daily,String expectedDate){
    return !expectedDate.isBlank()&&expectedDate.equals(text(daily.get("date")))&&nonnegative(daily.get("price"))!=null;
  }
  private record Candidate(Map<String,Object> rule,BigDecimal price){}
  private record Scored(Candidate candidate,BigDecimal difference){}
  private record EstimateCandidate(Map<String,Object> rule,BigDecimal actualPrice,BigDecimal difference){}
}
