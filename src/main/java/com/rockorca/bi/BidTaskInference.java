package com.rockorca.bi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Matches a plan to a reported task or an unambiguous open_url; bids never identify tasks. */
final class BidTaskInference {
  private BidTaskInference(){}

  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps){
    return infer(rows,rules,accountGaps,true);
  }

  // Kept for callers that supplied the old historical-price flag; URL matching is always used.
  static Map<String,Map<String,Object>> infer(List<?> rows,List<Map<String,Object>> rules,Map<String,Object> accountGaps,boolean ignored){
    Map<String,Object> gapIndex=new HashMap<>();
    if(accountGaps!=null)accountGaps.forEach((id,value)->{gapIndex.put(id,value);String canonical=canonicalId(id);if(!canonical.isBlank())gapIndex.put(canonical,value);});
    Map<String,Map<String,String>> learned=new HashMap<>();
    for(Object item:rows){
      if(!(item instanceof Map<?,?> row))continue;
      String task=text(taskEvidence(row,gapIndex).get("taskName"));
      if(!task.isBlank())for(String key:openUrlKeys(row))learned.computeIfAbsent(key,ignoredKey->new LinkedHashMap<>()).putIfAbsent(task.toLowerCase(Locale.ROOT),task);
    }
    Map<String,Map<String,Object>> result=new LinkedHashMap<>();
    for(Object item:rows){
      if(!(item instanceof Map<?,?> row))continue;
      String identity=inferenceIdentity(row),reported=text(taskEvidence(row,gapIndex).get("taskName"));
      if(!reported.isBlank()){
        Map<String,Object> rule=taskRule(reported,rules);
        result.put(identity,rule==null?ReportService.mapOf("task",reported,"method","daily-report-task"):
            ReportService.mapOf("rule",rule,"method","daily-report-task","reportedTaskName",reported));
        continue;
      }
      List<String> urlKeys=openUrlKeys(row);if(urlKeys.isEmpty())continue;
      boolean resolved=false,conflict=false;
      for(String key:urlKeys){
        Map<String,String> observed=learned.get(key);
        if(observed==null)continue;
        if(observed.size()!=1){conflict=true;break;}
        String task=observed.values().iterator().next();Map<String,Object> rule=taskRule(task,rules);
        String match=key.startsWith("outpushplanid:")?"same-push-id":"same-daily-url";
        result.put(identity,rule==null?ReportService.mapOf("task",task,"method","open-url-task","urlMatch",match):
            ReportService.mapOf("rule",rule,"method","open-url-task","urlMatch",match));
        resolved=true;break;
      }
      if(resolved||conflict)continue;
      String url=openUrlKey(row);
      String decoded=decodedUrl(url).toLowerCase(Locale.ROOT);
      var configured=rules.stream().filter(rule->{String keyword=text(rule.get("urlKeyword")).toLowerCase(Locale.ROOT);return !keyword.isBlank()&&decoded.contains(keyword);}).toList();
      if(configured.size()==1){result.put(identity,ReportService.mapOf("rule",configured.getFirst(),"method","open-url-task","urlMatch","configured-url"));continue;}
      if(configured.size()>1)continue;
      var named=rules.stream().filter(rule->{String name=text(rule.get("name")).toLowerCase(Locale.ROOT);return name.length()>=3&&decoded.contains(name);}).toList();
      if(named.size()==1)result.put(identity,ReportService.mapOf("rule",named.getFirst(),"method","open-url-task","urlMatch","task-name-in-url"));
    }
    return result;
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

  private static Map<?,?> taskEvidence(Map<?,?> row,Map<String,Object> gapIndex){
    Object entry=gapEntry(rowIds(row),gapIndex);
    if(!(entry instanceof Map<?,?> account))return Map.of();
    String optimizer=text(row.get("user_name"));
    if(account.get("taskByOptimizer") instanceof Map<?,?> byOptimizer&&!optimizer.isBlank()){
      for(var candidate:byOptimizer.entrySet())if(text(candidate.getKey()).equalsIgnoreCase(optimizer)&&candidate.getValue() instanceof Map<?,?> value&&!text(value.get("taskName")).isBlank())return value;
    }
    return account;
  }

  private static String openUrlKey(Map<?,?> row){return text(row.get("open_url"));}
  private static String decodedUrl(String url){
    String text=url;
    for(int step=0;step<3;step++)try{String decoded=URLDecoder.decode(text,StandardCharsets.UTF_8);if(decoded.equals(text))break;text=decoded;}catch(IllegalArgumentException error){break;}
    return text;
  }
  private static List<String> openUrlKeys(Map<?,?> row){
    String url=openUrlKey(row);if(url.isBlank())return List.of();
    var keys=new ArrayList<String>();keys.add("url:"+url);
    var matcher=java.util.regex.Pattern.compile("outpushplanid[\\\"']?\\s*[:=]\\s*[\\\"']?([a-z0-9_-]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(decodedUrl(url));
    if(matcher.find())keys.add("outpushplanid:"+matcher.group(1));
    return keys;
  }
  static String inferenceKey(Map<?,?> row){String advertiser=text(row.get("advertiser_id"));return advertiser.isBlank()?text(row.get("media_account_id")):advertiser;}
  static String inferenceIdentity(Map<?,?> row){return text(row.get("source_platform"))+"\u0000"+inferenceKey(row)+"\u0000"+text(row.get("user_name")).toLowerCase(Locale.ROOT)+"\u0000"+text(row.get("promotion_id"))+"\u0000"+openUrlKey(row);}

  static Object accountEntry(Map<?,?> row,Map<String,Object> accountGaps){
    if(accountGaps==null)return null;
    for(String id:rowIds(row)){
      Object value=accountGaps.get(id);if(value!=null)return value;
      String canonical=canonicalId(id);
      for(var entry:accountGaps.entrySet())if(canonical.equals(canonicalId(entry.getKey())))return entry.getValue();
    }
    return null;
  }

  private static String text(Object value){return Objects.toString(value,"").trim();}
  private static List<String> rowIds(Map<?,?> row){return List.of(text(row.get("advertiser_id")),text(row.get("media_account_id")));}
  private static Object gapEntry(List<String> ids,Map<String,Object> index){for(String id:ids){Object value=index.get(id);if(value==null)value=index.get(canonicalId(id));if(value!=null)return value;}return null;}
  private static String canonicalId(String id){return id.replaceFirst("\\.0+$","").replaceFirst("^0+(?=\\d)","");}
}
