package com.rockorca.bi;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/** Safe, bounded sync diagnostics. Exception bodies and credentials never enter these descriptions. */
final class BidSyncDiagnostics {
  private static final Pattern CODE=Pattern.compile("code=(-?[0-9]{1,10})(?![0-9])");
  private static final Pattern HTTP=Pattern.compile("HTTP ([1-5][0-9]{2})(?![0-9])");
  private static final Pattern TOTAL_CHANGED=Pattern.compile("分页期间计划总数从 ([0-9]{1,6}) 变为 ([0-9]{1,6})，请重新查询");
  private static final Pattern SHORT_PAGE=Pattern.compile("第 ([0-9]{1,4}) 页数据不完整：应有 ([0-9]{1,3}) 条，实际 ([0-9]{1,6}) 条");
  private BidSyncDiagnostics(){}

  static final class PageFailure extends IllegalArgumentException {
    private final String platform;
    private final int page;
    PageFailure(String platform,int page,Exception cause){
      super(label(platform)+" · 第 "+page+" 页："+category(chain(cause)),cause);
      this.platform=label(platform);this.page=page;
    }
  }

  static String describe(Throwable error){
    var causes=chain(error);
    for(var cause:causes)if(cause instanceof PageFailure page)
      return page.platform+" · 第 "+page.page+" 页："+category(causes);
    String platform=platform(causes);
    return (platform.equals("上游")?"":platform+"：")+category(causes);
  }

  static boolean requiresAttention(Throwable error){
    for(var cause:chain(error)){
      String message=message(cause);
      if(permission(message)||credential(message)||cause instanceof BidUpstreamRequest.Rejection)return true;
      if(cause instanceof BidUpstreamRequest.Failure failure){
        if(failure.status()==401||failure.status()==403)return true;
        continue;
      }
      var http=HTTP.matcher(message);
      if(CODE.matcher(message).find()||http.find()&&List.of("401","403").contains(http.group(1))
          ||message.contains("返回的不是 JSON"))return true;
    }
    return false;
  }

  static String failure(Throwable error){
    var causes=chain(error);
    for(var cause:causes){
      if(permission(message(cause)))return "网站账户已停用或工具权限已撤销，同步已暂停";
      if(credential(message(cause)))return "保存的凭据无法解密，请更新登录凭据后重新启用";
    }
    String source=platform(causes),code=code(causes);
    return "服务器同步失败"+(code.isBlank()?"":"（"+source+" code="+code+"）")
        +"，已暂停并保留旧快照。请更新创量登录凭据并核对"+source+"接口权限及网络后重新启用。";
  }

  private static String category(List<Throwable> causes){
    for(var cause:causes){
      if(permission(message(cause)))return "网站账户或工具权限已撤销";
      if(credential(message(cause)))return "保存的凭据无法解密";
    }
    for(var cause:causes)if(cause instanceof BidUpstreamRequest.Failure failure){
      String reason=switch(failure.kind()){
        case HTTP->"上游 HTTP "+failure.status();
        case TIMEOUT->"网络请求超时";
        case CONNECT->"网络连接失败";
        case IO->"网络请求失败";
        case TLS->"TLS 连接失败";
        case BUDGET->"上游请求尝试次数或时间预算已用尽";
      };
      return reason+"（已尝试 "+failure.attempts()+" 次）";
    }
    for(var cause:causes)if(cause instanceof CancellationException||cause instanceof InterruptedException)return "查询已取消或中断";
    for(var cause:causes)if(cause instanceof SQLTransientConnectionException)return "数据库连接池等待超时或连接失败";
    for(var cause:causes)if(cause instanceof SQLTimeoutException)return "数据库查询超时";
    for(var cause:causes)if(cause instanceof SQLException)return "数据库读写失败";
    for(var cause:causes)if(cause instanceof HttpTimeoutException||cause instanceof SocketTimeoutException||cause instanceof TimeoutException)return "网络请求超时";
    for(var cause:causes)if(cause instanceof SSLException)return "TLS 连接失败";
    for(var cause:causes)if(cause instanceof ConnectException||cause instanceof UnknownHostException||cause instanceof NoRouteToHostException)return "网络连接失败";
    for(var cause:causes)if(cause instanceof IOException)return "网络请求失败";
    String code=code(causes);
    if(!code.isBlank())return "上游拒绝请求（code="+code+"）";
    for(var cause:causes)if(cause instanceof BidUpstreamRequest.Rejection)return "上游拒绝请求";
    for(var cause:causes){
      String message=message(cause);
      var http=HTTP.matcher(message);if(http.find())return "上游 HTTP "+http.group(1);
      var total=TOTAL_CHANGED.matcher(message);if(total.matches())return "分页总数变化（"+total.group(1)+" → "+total.group(2)+"）";
      var shortPage=SHORT_PAGE.matcher(message);if(shortPage.matches())return "分页数据不完整（应有 "+shortPage.group(2)+" 条，实际 "+shortPage.group(3)+" 条）";
      if(message.equals("查询范围内没有字节或广点通计划，保留原有结果"))return "查询范围内没有计划";
      if(message.contains("返回的不是 JSON"))return "上游响应格式无效";
      if(message.equals("定时同步仅保存北京时间当天数据，跨日请重新采集"))return "统计日期已跨日";
      if(message.startsWith("计划总数")&&message.contains("超过 100000 条"))return "计划数量超过安全上限";
    }
    for(var cause:causes)if(cause instanceof IllegalArgumentException)return "计划数据或分页响应校验失败";
    return "同步处理失败";
  }

  private static String platform(List<Throwable> causes){
    for(var cause:causes){
      if(cause instanceof PageFailure page)return page.platform;
      if(cause instanceof BidUpstreamRequest.Failure failure)return label(failure.platform());
      if(cause instanceof BidUpstreamRequest.Rejection rejection)return label(rejection.platform());
      if(message(cause).startsWith("广点通"))return "广点通";
      if(message(cause).startsWith("创量"))return "字节";
    }
    return "上游";
  }

  private static String code(List<Throwable> causes){
    for(var cause:causes){
      if(cause instanceof BidUpstreamRequest.Rejection rejection){
        String code=Objects.toString(rejection.code(),"");
        if(code.matches("-?[0-9]{1,10}"))return code;
      }
      var match=CODE.matcher(message(cause));if(match.find())return match.group(1);
    }
    return "";
  }

  private static List<Throwable> chain(Throwable error){
    var causes=new ArrayList<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
    for(Throwable cause=error;cause!=null&&causes.size()<32&&seen.add(cause);cause=cause.getCause())causes.add(cause);
    return causes;
  }
  private static String message(Throwable error){return Objects.toString(error.getMessage(),"");}
  private static boolean permission(String message){return message.equals("permission");}
  private static boolean credential(String message){return message.equals("credential")||message.contains("保存的凭据无法解密");}
  private static String label(String platform){return switch(Objects.toString(platform,"")){case "byte","字节","创量"->"字节";case "gdt","广点通"->"广点通";default->"上游";};}
}
