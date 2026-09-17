package com.rockorca.bi;

import java.time.*;
import java.util.*;

/** Uses the report's existing four-calendar-day warning window, not a platform eligibility decision. */
final class BidEndedWarning {
  static Map<String,Object> evaluate(Map<String,Object> row,LocalDate today,double cost,double conversions,
      String firstDate,String lastDate,int days){
    LocalDate created=date(row.get("promotion_create_time"));
    double bid=number(row.get("cpa_bid"));
    if(created==null||!created.plusDays(3).isBefore(today)||!Double.isFinite(cost)||!Double.isFinite(conversions)
        ||conversions<0||conversions>=6||!Double.isFinite(bid)||bid<=0
        ||cost-bid*7.2<=Math.max(1,Math.abs(bid*7.2))*1e-9)return null;
    var result=new LinkedHashMap<String,Object>(row);
    result.put("created_date",created.toString());result.put("period_end",created.plusDays(3).toString());
    result.put("overall_cost",cost);result.put("overall_conversions",conversions);
    result.put("shortfall",(int)Math.ceil(6-conversions));result.put("warning_threshold",bid*7.2);
    result.put("first_date",firstDate);result.put("last_date",lastDate);
    // The archive may have started after the plan was created or missed a scheduled day.
    boolean complete=firstDate!=null&&lastDate!=null&&firstDate.compareTo(created.toString())<=0
        &&lastDate.compareTo(created.plusDays(3).toString())>=0
        &&days>=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(firstDate),LocalDate.parse(lastDate))+1;
    result.put("archive_complete",complete);
    return result;
  }

  static double number(Object value){try{return Double.parseDouble(Objects.toString(value,""));}catch(NumberFormatException e){return Double.NaN;}}
  static LocalDate date(Object value){
    String text=Objects.toString(value,"").trim();
    try{
      if(text.matches("\\d{10}|\\d{13}"))return Instant.ofEpochMilli(Long.parseLong(text)*(text.length()==10?1000:1)).atZone(ReportService.BEIJING).toLocalDate();
      return LocalDate.parse(text.substring(0,10).replace('/','-'));
    }catch(RuntimeException e){return null;}
  }
}
