(function(root){
  'use strict';
  const aliases={id:['promotion_id','计划ID','广告ID','计划 ID'],name:['promotion_name','计划名称','广告名称'],account:['media_account_name','advertiser_nick','account_name','账户名称','广告账户名称'],optimizer:['user_name','优化师','优化师姓名'],accountId:['advertiser_id','账户ID','账户 ID'],createdAt:['promotion_create_time','计划创建时间','创建时间'],cost:['stat_cost','消耗','总消耗'],conversions:['convert_cnt','转化数'],registrations:['active_register','注册数','注册'],bid:['cpa_bid','出价','目标转化出价','目标转化成本']};
  function value(row,keys){for(const key of keys){if(row[key]!==undefined&&row[key]!==null&&row[key]!=='')return row[key]}return null}
  function number(v){if(v===null||v===undefined||String(v).trim()==='')return null;const n=Number(String(v).replaceAll(',','').trim());return Number.isFinite(n)&&n>=0?n:null}
  function normalize(row){const out={};for(const [key,keys] of Object.entries(aliases))out[key]=value(row,keys);for(const key of ['cost','conversions','registrations','bid'])out[key]=number(out[key]);for(const key of ['id','name','account','accountId','optimizer','createdAt'])out[key]=String(out[key]??'');if(!out.account&&row.account_info&&typeof row.account_info==='object')out.account=String(row.account_info.media_account_name||row.account_info.account_name||'');return out}
  function analyze(row,price,margin,minSample,current){
    const r={...row,ratio:null,cpa:null,breakEven:null,ceiling:null,revenue:null,profit:null,impliedCost:null,bidRoi:null,actualRoi:null,projectedCost:null,projectedProfit:null,status:'missing'};
    if(!(price>0)||!(margin>=0&&margin<100)||!(minSample>=1))throw Error('结算价必须大于 0，毛利率为 0–99%，最小样本至少为 1');
    if(row.registrations!==null)r.revenue=row.registrations*price;
    if(row.registrations!==null&&row.cost!==null){r.profit=r.revenue-row.cost;r.cpa=row.registrations>0?row.cost/row.registrations:null;r.actualRoi=row.cost>0?r.revenue/row.cost:null}
    if(row.registrations===null||row.conversions===null||row.bid===null)return r;
    if(row.registrations===0){
      if(row.conversions===0&&row.cost===0){r.projectedCost=0;r.projectedProfit=0;}
      r.status='no-register';return r;
    }
    r.ratio=row.conversions/row.registrations;
    if(r.ratio===0){r.status='no-return';return r}
    r.breakEven=price/r.ratio;r.ceiling=r.breakEven*(1-margin/100);r.impliedCost=row.bid*r.ratio;
    if(r.ratio<=1&&r.impliedCost>0){r.bidRoi=price/r.impliedCost;r.projectedCost=row.bid*row.conversions;r.projectedProfit=row.registrations*price-r.projectedCost;}
    if(r.ratio>1)r.status='abnormal';
    else if(row.cost===null)r.status='missing';
    else if(row.registrations<minSample||row.conversions<minSample)r.status='sample';
    else if(current)r.status='pending';
    else if(row.bid>r.breakEven)r.status='loss-bid';
    else if(row.bid>r.ceiling)r.status='margin-bid';
    else r.status='within';
    return r;
  }
  function taskFor(row,rules){
    const account=row.account.trim().toLowerCase();
    const matches=account?rules.filter(rule=>String(rule.keyword||'').trim()&&account.includes(String(rule.keyword).trim().toLowerCase())):[];
    if(matches.length!==1)return{task:'',price:null,pricingStatus:matches.length?'task-conflict':'task-missing'};
    const price=number(matches[0].price),task=String(matches[0].name||'').trim(),valid=Boolean(task)&&price>0&&price<=1000000;
    return{task,price:valid?price:null,pricingStatus:valid?'priced':'price-missing'};
  }
  function cashMetrics(row,price){
    const valid=n=>Number.isFinite(n)&&n>=0;
    const finite=n=>Number.isFinite(n)?n:null;
    const commission=valid(row.registrations)&&price>0?finite(row.registrations*price):null;
    const bidCost=valid(row.bid)&&valid(row.conversions)?finite(row.bid*row.conversions):null;
    const breakEvenBid=commission>0&&row.conversions>0?finite(commission/row.conversions):null;
    let grant=null,cashCost=null,estimatedCompensation=null,estimatedRoi=null;
    if(valid(row.cost)&&valid(row.conversions)&&bidCost!==null){
      const threshold=1.2*bidCost;
      // Do not let binary rounding turn equality at the 1.2 boundary into a grant.
      const tolerance=Number.EPSILON*Math.max(Math.abs(row.cost),Math.abs(threshold))*8;
      const eligible=row.conversions>6&&row.cost-threshold>tolerance;
      grant=eligible?row.cost-bidCost:0;
      cashCost=eligible?bidCost:row.cost;
      const compensationEligible=row.conversions>0&&row.cost-threshold>tolerance;
      estimatedCompensation=compensationEligible?Math.max(0,row.cost-bidCost):0;
      estimatedRoi=commission!==null&&row.cost>0
        ?finite((commission+estimatedCompensation)/row.cost):null;
    }
    return{cost:valid(row.cost)?row.cost:null,commission,bidCost,breakEvenBid,grant,cashCost,estimatedCompensation,estimatedRoi,
      roi:commission!==null&&cashCost>0?finite(commission/cashCost):null,
      bidProfitRate:breakEvenBid>0&&valid(row.bid)?finite((breakEvenBid-row.bid)/breakEvenBid):null};
  }
  function summarizeCash(rows){
    const total=key=>rows.length&&rows.every(r=>Number.isFinite(r[key]))?rows.reduce((sum,r)=>sum+r[key],0):null;
    const commission=total('commission'),cashCost=total('cashCost'),bidCost=total('bidCost');
    const cost=total('cost'),estimatedCompensation=total('estimatedCompensation');
    const roi=commission!==null&&cashCost>0?commission/cashCost:null;
    const estimatedRoi=commission!==null&&estimatedCompensation!==null&&cost>0
      ?(commission+estimatedCompensation)/cost:null;
    const bidProfitRate=commission>0&&bidCost!==null&&rows.every(r=>Number.isFinite(r.bidProfitRate))?(commission-bidCost)/commission:null;
    return{roi:Number.isFinite(roi)?roi:null,estimatedRoi:Number.isFinite(estimatedRoi)?estimatedRoi:null,bidProfitRate:Number.isFinite(bidProfitRate)?bidProfitRate:null};
  }
  function analyzeTask(row,rules,margin,minSample,current){
    const task=taskFor(row,rules);
    const result=analyze(row,task.price||1,margin,minSample,current);
    if(task.price===null){for(const key of ['breakEven','ceiling','revenue','profit','bidRoi','actualRoi','projectedProfit'])result[key]=null;result.status=task.pricingStatus;}
    return{...result,...task,...cashMetrics(row,task.price)};
  }
  function aggregateGroups(rows,dimensions,statDate){
    const groups=new Map();
    for(const row of rows){
      const labels={optimizer:String(row.optimizer||'').trim()||'未填写',task:String(row.task||'').trim()||'未匹配任务'};
      const key=JSON.stringify(dimensions.map(dimension=>labels[dimension]));
      if(!groups.has(key))groups.set(key,{labels,items:[]});
      groups.get(key).items.push(row);
    }
    const sum=(rows,key)=>rows.reduce((total,row)=>total+(Number.isFinite(row[key])?row[key]:0),0);
    return [...groups.values()].map(({labels,items})=>{
      const cost=sum(items,'cost'),conversions=sum(items,'conversions'),registrations=sum(items,'registrations');
      const cash=summarizeCash(items);
      const commission=items.every(row=>Number.isFinite(row.commission))?sum(items,'commission'):null;
      return{...labels,plans:items.length,todayPlans:items.filter(row=>statDate&&String(row.createdAt||'').slice(0,10)===statDate).length,
        spendingPlans:items.filter(row=>Number.isFinite(row.cost)&&row.cost>0).length,
        accounts:new Set(items.map(row=>row.accountId||row.account).filter(Boolean)).size,
        cost,conversions,registrations,ratio:registrations>0?conversions/registrations:null,
        priced:items.filter(row=>row.price!==null).length,commission,
        estimatedCompensation:items.every(row=>Number.isFinite(row.estimatedCompensation))?sum(items,'estimatedCompensation'):null,
        cashCost:items.every(row=>Number.isFinite(row.cashCost))?sum(items,'cashCost'):null,
        estimatedRoi:cash.estimatedRoi,bidProfitRate:cash.bidProfitRate};
    }).map(row=>({...row,profit:row.commission===null||row.cashCost===null?null:row.commission-row.cashCost}))
      .sort((a,b)=>b.cost-a.cost||dimensions.map(key=>a[key]).join(' ').localeCompare(dimensions.map(key=>b[key]).join(' '),'zh-CN'));
  }
  const aggregateOptimizers=(rows,date)=>aggregateGroups(rows,['optimizer'],date);
  const aggregateTasks=(rows,date)=>aggregateGroups(rows,['task'],date);
  const aggregateOptimizerTasks=(rows,date)=>aggregateGroups(rows,['optimizer','task'],date);
  const api={normalize,analyze,taskFor,analyzeTask,cashMetrics,summarizeCash,aggregateGroups,aggregateOptimizers,aggregateTasks,aggregateOptimizerTasks};if(typeof module!=='undefined')module.exports=api;else root.BidMonitor=api;
})(globalThis);
