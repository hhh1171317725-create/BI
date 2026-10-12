package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** A fixed, read-only API. Authentication and reads run under the Garmin account lock. */
@RestController
@RequestMapping("/api/garmin-external")
public class GarminExternalApiController {
  private static final Set<String> PATHS=Set.of("status","data","day","activities","activity");
  private static final Set<String> DAY_CODES=Set.of("daily","sleep","heart","hrv","weight","battery","steps","floors","stress","battery_events",
      "hydration","respiration","spo2","intensity","training_readiness","training_status","training_load","daily_training","vo2max","fitness_age",
      "endurance","hill","move_iq","lifestyle","menstrual","nutrition","meals","composition","blood_pressure","race_predictions","running_tolerance","ftp","lactate_threshold");
  private static final Set<String> ACTIVITY_CODES=Set.of("overview","splits","typed_splits","split_summaries","weather","heart_zones","power_zones","exercise_sets","gear","samples");
  private static final List<String> ACTIVITY_FIELDS=List.of("id","name","type","startTime","durationSeconds","distanceMeters","calories","averageHeartRate");
  private final GarminSyncService service;
  private final ObjectMapper mapper;
  public GarminExternalApiController(GarminSyncService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}
  static boolean isReadPath(HttpServletRequest request){
    return "GET".equals(request.getMethod())&&PATHS.stream().anyMatch(path->request.getRequestURI().equals("/api/garmin-external/"+path));
  }
  @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");}
  private Map<String,Object> read(HttpServletRequest request,java.util.function.Function<Long,Map<String,Object>> action){
    return service.withReadKey(request.getHeader("Authorization"),id->{
      var result=new LinkedHashMap<>(service.externalStatus(id));result.putAll(action.apply(id));return result;
    });
  }
  @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request){return read(request,id->Map.of());}
  @GetMapping("/data") public Map<String,Object> data(HttpServletRequest request,
      @RequestParam(defaultValue="") String startDate,@RequestParam(defaultValue="") String endDate){
    return read(request,id->{
      LocalDate end=endDate.isBlank()?LocalDate.now(ReportService.BEIJING):date(endDate);
      LocalDate start=startDate.isBlank()?end.minusDays(6):date(startDate);
      long count=ChronoUnit.DAYS.between(start,end)+1;
      if(count<1||count>31)throw bad("每次汇总查询须为 1 至 31 天");
      var cached=service.data(id,start.toString(),end.toString());var result=new LinkedHashMap<String,Object>();
      for(String field:List.of("startDate","endDate","missingDates"))result.put(field,cached.get(field));
      var days=new ArrayList<Map<String,Object>>();
      for(Object item:list(cached.get("days"))){var day=map(item);var out=new LinkedHashMap<String,Object>();
        for(String field:List.of("date","summary","warnings","detailCount"))out.put(field,day.get(field));
        out.put("activityCount",list(day.get("activities")).size());days.add(out);
      }
      result.put("days",days);return result;
    });
  }
  @GetMapping("/day") public Map<String,Object> day(HttpServletRequest request,@RequestParam String date,
      @RequestParam(defaultValue="all") String category){
    return read(request,id->{
      category(category,DAY_CODES);var day=map(service.day(id,date).get("day"));var result=new LinkedHashMap<String,Object>();
      result.put("date",day.get("date"));result.put("summary",day.get("summary"));result.put("warnings",day.get("warnings"));
      result.put("activityCount",list(day.get("activities")).size());
      return details(result,map(day.get("details")),category,DAY_CODES,false);
    });
  }
  @GetMapping("/activities") public Map<String,Object> activities(HttpServletRequest request,@RequestParam String date,
      @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit){
    return read(request,id->{
      if(offset<0||limit<1||limit>50)throw bad("offset 不得为负数，limit 须为 1 至 50");
      var cached=service.data(id,date,date);var days=list(cached.get("days"));
      if(days.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该日期尚无已保存记录，请先在网站同步");
      var items=list(map(days.getFirst()).get("activities"));int start=Math.min(offset,items.size()),end=Math.min(start+limit,items.size());
      var page=new ArrayList<Map<String,Object>>();for(Object item:items.subList(start,end))page.add(activityHead(map(item)));
      var result=new LinkedHashMap<String,Object>();result.put("date",date);result.put("activities",page);result.put("total",items.size());
      result.put("offset",offset);result.put("nextOffset",end<items.size()?end:null);return result;
    });
  }
  @GetMapping("/activity") public Map<String,Object> activity(HttpServletRequest request,@RequestParam String date,@RequestParam String activityId,
      @RequestParam(defaultValue="all") String category,@RequestParam(defaultValue="false") boolean includeSamples){
    return read(request,id->{
      category(category,ACTIVITY_CODES);if(!activityId.matches("[1-9][0-9]{0,29}"))throw bad("运动 ID 无效");
      var day=map(service.day(id,date).get("day"));
      var saved=list(day.get("activities")).stream().map(GarminExternalApiController::map).filter(item->activityId.equals(item.get("id"))).findFirst()
          .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"该运动不属于当前账户该日期的缓存记录"));
      var records=new LinkedHashMap<>(map(saved.get("details")));if(saved.containsKey("overview"))records.putIfAbsent("overview",saved.get("overview"));
      var result=new LinkedHashMap<String,Object>();result.put("date",date);result.put("activity",activityHead(saved));
      return details(result,records,category,ACTIVITY_CODES,category.equals("all")&&!includeSamples);
    });
  }
  private static Map<String,Object> activityHead(Map<String,Object> item){var out=new LinkedHashMap<String,Object>();for(String field:ACTIVITY_FIELDS)out.put(field,item.get(field));return out;}
  private Map<String,Object> details(LinkedHashMap<String,Object> result,Map<String,Object> source,String category,Set<String> codes,boolean skipSamples){
    var budget=new Budget();var records=new LinkedHashMap<String,Object>();var omitted=new ArrayList<String>();
    for(String code:codes.stream().sorted().toList())if(category.equals("all")||category.equals(code)){
      if(skipSamples&&code.equals("samples")){if(source.containsKey(code))omitted.add(code);continue;}
      if(source.containsKey(code)){if(Boolean.TRUE.equals(map(source.get(code)).get("truncated")))budget.limited=true;records.put(code,budget.copy(source.get(code),0));}
    }
    result.put("details",records);result.put("category",category);result.put("omittedCategories",omitted);result.put("responseLimited",budget.limited||!omitted.isEmpty());
    // GPT Actions have a 100,000 character response limit. Preserve the summary if a provider payload is unusually wide.
    if(mapper.writeValueAsString(result).length()>70_000){result.put("details",Map.of());result.put("omittedCategories",new ArrayList<>(records.keySet()));result.put("responseLimited",true);}
    result.put("detailNotice","仅返回网站已采集的详情；数组最多 30 项，文本最多 300 字符。responseLimited=true 表示有省略，请按 category 读取，完整记录在网站查看。");
    return result;
  }
  private static final class Budget {
    int nodes=1800;boolean limited;
    Object copy(Object value,int depth){
      if(value==null)return null;if(--nodes<0||depth>12){limited=true;return null;}
      if(value instanceof String text){if(text.length()>300){limited=true;return text.substring(0,300);}return text;}
      if(value instanceof Map<?,?> input){var out=new LinkedHashMap<String,Object>();for(var entry:input.entrySet()){
        if(nodes<=0){limited=true;break;}out.put(entry.getKey().toString(),copy(entry.getValue(),depth+1));}return out;}
      if(value instanceof List<?> input){var out=new ArrayList<>();int count=Math.min(30,input.size());if(count<input.size())limited=true;
        for(int i=0;i<count;i++){if(nodes<=0){limited=true;break;}out.add(copy(input.get(i),depth+1));}return out;}
      return value;
    }
  }
  private static LocalDate date(String value){try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(Exception error){throw bad("日期须为 YYYY-MM-DD");}}
  private static void category(String value,Set<String> codes){if(!value.equals("all")&&!codes.contains(value))throw bad("不支持的详情分类");}
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> input?(Map<String,Object>)input:Map.of();}
  private static List<?> list(Object value){return value instanceof List<?> items?items:List.of();}
  private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
  @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Map<String,Object>> failure(ResponseStatusException error){
    var response=ResponseEntity.status(error.getStatusCode()).header("Cache-Control","no-store, private");
    if(error.getStatusCode().value()==401)response.header("WWW-Authenticate","Bearer");
    if(error.getStatusCode().value()==429)response.header("Retry-After","60");
    return response.body(Map.of("error",Objects.toString(error.getReason(),"请求未完成")));
  }
  @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unexpected(Exception error){
    boolean badInput=error instanceof org.springframework.web.bind.MissingServletRequestParameterException||error instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
    return ResponseEntity.status(badInput?400:500).header("Cache-Control","no-store, private").body(Map.of("error",badInput?"请检查必填参数及格式":"健康缓存暂时无法读取，请稍后重试"));
  }
}
