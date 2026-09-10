package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class GdtBidMonitorClient {
  private static final URI ENDPOINT=URI.create("https://cli1.mobgi.com/MainPanelReport/AdReport/getReport");
  private final ObjectMapper mapper;
  private final HttpClient client;

  @Autowired
  public GdtBidMonitorClient(ObjectMapper mapper){
    this(mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  GdtBidMonitorClient(ObjectMapper mapper,HttpClient client){this.mapper=mapper;this.client=client;}

  Map<String,Object> page(Map<String,Object> input)throws Exception{
    LocalDate start=LocalDate.parse(text(input,"startDate")),end=LocalDate.parse(text(input,"endDate"));
    if(start.isAfter(end)||start.plusDays(92).isBefore(end))throw new IllegalArgumentException("查询日期范围必须为 1 至 93 天");
    int page=Integer.parseInt(text(input,"page"));
    if(page<1||page>BidMonitorApiController.MAX_PLAN_ROWS/BidMonitorApiController.PAGE_SIZE)
      throw new IllegalArgumentException("广点通计划页码超出安全范围");
    String cookie=BidMonitorApiController.normalizeCookie(text(input,"cookie"));
    String user=text(input,"clientUser"),main=text(input,"mainUserId");
    if(cookie.isBlank()||!user.matches("[0-9]+")||!main.matches("[0-9]+"))
      throw new IllegalArgumentException("请填写 Cookie、client-user 和 main-user-id");
    BidMonitorApiController.validateCookieUser(cookie,user);
    Map<String,Object> body=requestBody(input,start,end,page);
    HttpRequest request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(40))
        .header("Content-Type","application/json;charset=UTF-8")
        .header("Accept","application/json, text/plain, */*").header("Cookie",cookie)
        .header("Accept-Language","zh-CN,zh;q=0.9")
        .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
        .header("Origin","https://cl.mobgi.com").header("Referer","https://cl.mobgi.com/")
        .header("client-user",user).header("main-user-id",main)
        .header("ff-request-id",BidMonitorApiController.requestId())
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200)throw new IllegalArgumentException("广点通接口 HTTP "+response.statusCode()+"，请检查登录凭据或网络");
    Map<String,Object> result;
    try{result=mapper.readValue(response.body(),new TypeReference<Map<String,Object>>(){});}
    catch(Exception error){throw new IllegalArgumentException("广点通返回的不是 JSON，请重新登录");}
    return parse(result,page,cookie);
  }

  static Map<String,Object> requestBody(Map<String,Object> input,LocalDate start,LocalDate end,int page){
    String createdStart=text(input,"createdStart"),createdEnd=text(input,"createdEnd");
    if(!createdStart.isBlank())LocalDate.parse(createdStart);
    if(!createdEnd.isBlank())LocalDate.parse(createdEnd);
    if(!createdStart.isBlank()&&!createdEnd.isBlank()&&createdStart.compareTo(createdEnd)>0)
      throw new IllegalArgumentException("计划创建开始日期不能晚于结束日期");
    Map<String,Object> conditions=new LinkedHashMap<>();
    conditions.put("company",List.of());conditions.put("owner_user_id",List.of());conditions.put("advertiser_id",List.of());
    conditions.put("media_project_id",List.of());conditions.put("configured_status","");conditions.put("smart_delivery_platform","");
    conditions.put("system_status",List.of());conditions.put("auto_acquisition_status",List.of());conditions.put("smart_targeting_status",List.of());
    conditions.put("created_time",createdStart.isBlank()||createdEnd.isBlank()?List.of():List.of(createdStart,createdEnd));
    conditions.put("last_modified_time",List.of());conditions.put("combinatorial_id","");conditions.put("time_line","REPORTING_TIME");
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("data_type","list");body.put("media_type","gdt_upgrade");body.put("conditions",conditions);
    body.put("sort_field","adgroup_id");body.put("sort_direction","desc");
    body.put("base_infos",List.of("adgroup_name","adgroup_id","advertiser_id","advertiser_nick","user_name","balance",
        "deep_bid_amount","deep_conversion_spec","optimization_goal","bid_amount","created_time","daily_budget","bid_mode","begin_date"));
    body.put("page",page);body.put("page_size",BidMonitorApiController.PAGE_SIZE);
    body.put("start_date",start.toString());body.put("end_date",end.toString());
    body.put("kpis",List.of("view_count","view_user_count","ctr","cost","conversions_count","conversions_rate",
        "conversions_cost","reg_pv","deep_conversions_count","deep_conversions_rate","deep_conversions_cost"));
    body.put("time_line","REPORTING_TIME");
    return body;
  }

  static Map<String,Object> parse(Map<String,Object> result,int page,String cookie){
    if(!List.of("0","200").contains(text(result,"code")))
      throw new IllegalArgumentException(BidMonitorApiController.upstreamError(result,cookie).replaceFirst("创量拒绝请求","广点通拒绝请求"));
    if(!(result.get("data") instanceof Map<?,?> data)||!(data.get("list") instanceof List<?> list))
      throw new IllegalArgumentException("广点通响应缺少计划列表，请提供脱敏的成功响应以核对字段");
    List<Map<String,Object>> rows=new ArrayList<>();
    for(Object item:list){
      if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("广点通计划数据格式异常");
      Map<String,Object> row=new LinkedHashMap<>();
      Map<String,Object> providerData=new LinkedHashMap<>();raw.forEach((key,value)->providerData.put(String.valueOf(key),value));
      row.put("provider_data",providerData);
      row.put("promotion_id",id(raw.get("adgroup_id")));row.put("promotion_name",raw.get("adgroup_name"));
      row.put("advertiser_id",id(raw.get("advertiser_id")));row.put("media_account_id",id(raw.get("media_account_id")));
      row.put("media_account_name",raw.get("advertiser_nick"));row.put("user_name",raw.get("user_name"));
      row.put("promotion_create_time",raw.get("created_time"));row.put("stat_cost",metric(raw.get("cost")));
      row.put("convert_cnt",metric(raw.get("conversions_count")));row.put("active_register",metric(raw.get("reg_pv")));
      row.put("cpa_bid",metric(raw.get("bid_amount")));row.put("ecpm",ecpm(raw));row.put("app_type_text","");
      row.put("deep_bid_type_text",first(raw,"bid_mode_name","bid_mode"));row.put("deep_cpabid",optionalMetric(raw.get("deep_bid_amount")));
      row.put("deep_external_action_text",first(raw,"deep_conversion_spec_name","deep_conversion_spec"));
      row.put("external_action_text",first(raw,"optimization_goal_name","optimization_goal"));
      row.put("status_text",first(raw,"system_status_name","system_status"));
      row.put("source_platform","gdt");row.put("platform_text","广点通");rows.add(row);
    }
    Object total=BidMonitorApiController.totalCount(data,result);
    Map<String,Object> output=new LinkedHashMap<>();output.put("rows",rows);output.put("total",total);
    output.put("page",page);output.put("platform","gdt");return output;
  }

  private static Object metric(Object value){
    Object parsed=optionalMetric(value);return parsed==null?"0":parsed;
  }
  private static Object optionalMetric(Object value){
    if(value==null)return null;String text=value.toString().trim().replace(",","");
    return text.isBlank()||"--".equals(text)||"-".equals(text)?null:text;
  }
  private static Object ecpm(Map<?,?> row){
    Object direct=optionalMetric(first(row,"ecpm","cpm_platform","cpm"));if(direct!=null)return direct;
    try{double cost=Double.parseDouble(String.valueOf(row.get("cost")).replace(",","")),views=Double.parseDouble(String.valueOf(row.get("view_count")).replace(",",""));return views>0&&Double.isFinite(cost)?java.math.BigDecimal.valueOf(cost*1000/views).stripTrailingZeros().toPlainString():null;}catch(Exception ignored){return null;}
  }
  private static String id(Object value){return BidMonitorApiController.idText(value);}
  private static Object first(Map<?,?> row,String... keys){for(String key:keys){Object value=row.get(key);if(value!=null&&!value.toString().isBlank())return value;}return "";}
  private static String text(Map<String,Object> values,String key){return ReportService.text(values.get(key));}
}
