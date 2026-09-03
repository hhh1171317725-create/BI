package com.rockorca.bi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class DingtalkRobotClient {
  private final ObjectMapper mapper;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  public DingtalkRobotClient(ObjectMapper mapper){this.mapper=mapper;}

  static void validate(String webhook,String secret){
    if(!webhook.matches("https://oapi\\.dingtalk\\.com/robot/send\\?access_token=[A-Za-z0-9_-]{8,512}"))
      throw new IllegalArgumentException("请填写有效的钉钉机器人 Webhook");
    if(!secret.isEmpty()&&!secret.matches("SEC[A-Za-z0-9]{8,256}"))
      throw new IllegalArgumentException("加签密钥应以 SEC 开头");
  }

  static URI signed(String webhook,String secret,long timestamp)throws Exception{
    validate(webhook,secret);
    if(secret.isEmpty())return URI.create(webhook);
    var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    String sign=Base64.getEncoder().encodeToString(mac.doFinal((timestamp+"\n"+secret).getBytes(StandardCharsets.UTF_8)));
    return URI.create(webhook+"&timestamp="+timestamp+"&sign="+URLEncoder.encode(sign,StandardCharsets.UTF_8));
  }

  void send(String webhook,String secret,String keyword,String text)throws Exception{
    validate(webhook,secret);
    String content=keyword.isBlank()?text:keyword+"\n"+text;
    if(content.getBytes(StandardCharsets.UTF_8).length>18000)throw new IllegalArgumentException("推送内容过长，请缩短任务名称");
    HttpResponse<String> response;
    try{
      var request=HttpRequest.newBuilder(signed(webhook,secret,System.currentTimeMillis())).timeout(Duration.ofSeconds(20))
          .header("Content-Type","application/json;charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("msgtype","text","text",Map.of("content",content))),StandardCharsets.UTF_8)).build();
      response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    catch(Exception error){throw new IllegalArgumentException("钉钉发送结果未确认，请先检查群消息；不会自动重复发送");}
    checkResponse(response.statusCode(),response.body());
  }

  void checkResponse(int status,String body){
    if(status!=200)throw new IllegalArgumentException("钉钉返回 HTTP "+status+"，请检查机器人状态");
    Map<String,Object> data;
    try{data=mapper.readValue(body,new TypeReference<Map<String,Object>>(){});}
    catch(Exception error){throw new IllegalArgumentException("钉钉返回格式异常，发送结果未确认");}
    String code=data==null?"":Objects.toString(data.get("errcode"),"");
    if(!code.equals("0"))throw new IllegalArgumentException("钉钉未确认发送成功，请核对安全关键词、加签密钥、IP 白名单或限流状态");
  }
}
