(function(root){
  'use strict';
  const aliases={id:['promotion_id','计划ID','广告ID','计划 ID'],name:['promotion_name','计划名称','广告名称'],platform:['platform_text','投放平台','平台'],account:['media_account_name','advertiser_nick','account_name','账户名称','广告账户名称'],optimizer:['user_name','优化师','优化师姓名'],accountId:['advertiser_id','账户ID','账户 ID'],createdAt:['promotion_create_time','计划创建时间','创建时间'],cost:['stat_cost','消耗','总消耗'],conversions:['convert_cnt','转化数'],registrations:['active_register','注册数','注册'],bid:['cpa_bid','出价','目标转化出价','目标转化成本'],appType:['app_type_text','应用类型'],deepBidType:['deep_bid_type_text','深度出价类型'],deepCpaBid:['deep_cpabid','深度CPA出价'],deepExternalAction:['deep_external_action_text','深度转化目标'],externalAction:['external_action_text','转化目标'],planStatus:['status_text','计划状态']};
  function value(row,keys){for(const key of keys){if(row[key]!==undefined&&row[key]!==null&&row[key]!=='')return row[key]}return null}
  function number(v){if(v===null||v===undefined||String(v).trim()==='')return null;const n=Number(String(v).replaceAll(',','').trim());return Number.isFinite(n)&&n>=0?n:null}
  function normalize(row){const out={};for(const [key,keys] of Object.entries(aliases))out[key]=value(row,keys);for(const key of ['cost','conversions','registrations','bid','deepCpaBid'])out[key]=number(out[key]);for(const key of ['id','name','platform','account','accountId','optimizer','createdAt','appType','deepBidType','deepExternalAction','externalAction','planStatus'])out[key]=String(out[key]??'');if(!out.account&&row.account_info&&typeof row.account_info==='object')out.account=String(row.account_info.media_account_name||row.account_info.account_name||'');return out}
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
  function taskFor(row,rules,inferred){
    const account=row.account.trim().toLowerCase();
    const matches=account?rules.filter(rule=>String(rule.keyword||'').trim()&&account.includes(String(rule.keyword).trim().toLowerCase())):[];
    if(matches.length!==1){
      if(!matches.length&&inferred){const price=number(inferred.rule?.price),task=String(inferred.rule?.name||'').trim();return{task,price,pricingStatus:'priced',taskSource:'inferred',inference:inferred.detail}}
      return{task:'',price:null,pricingStatus:matches.length?'task-conflict':'task-missing',taskSource:''};
    }
    const price=number(matches[0].price),task=String(matches[0].name||'').trim(),valid=Boolean(task)&&price>0&&price<=1000000;
    return{task,price:valid?price:null,pricingStatus:valid?'priced':'price-missing',taskSource:'account-name'};
  }
  function inferAccountTasks(rows,rules,gaps){
    const result=new Map(),groups=new Map();
    for(const row of rows){if(!/(广点通|gdt)/i.test(row.platform)||taskFor(row,rules).task)continue;const key=accountIdentity(row);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(row)}
    for(const [key,items] of groups){
      const accountId=items.find(r=>r.accountId)?.accountId, evidence=gaps?.[accountId]?.tasks;
      if(!Array.isArray(evidence)||!evidence.length)continue;
      const candidates=[];
      for(const item of evidence){
        const evidenceName=String(item.name||'').trim().toLowerCase();
        let matched=rules.filter(rule=>String(rule.name||'').trim().toLowerCase()===evidenceName);
        if(!matched.length)matched=rules.filter(rule=>{const name=String(rule.name||'').trim().toLowerCase();return name&&evidenceName&&(name.includes(evidenceName)||evidenceName.includes(name))});
        if(matched.length===1){const gap=number(item.gap),price=number(matched[0].price);if(price>0)candidates.push({rule:matched[0],evidence:item,expected:gap===null?null:price*gap})}
      }
      const unique=[...new Map(candidates.map(c=>[String(c.rule.name).toLowerCase(),c])).values()];if(!unique.length)continue;
      const registrations=items.reduce((s,r)=>s+(Number.isFinite(r.registrations)?r.registrations:0),0);
      const conversions=items.reduce((s,r)=>s+(Number.isFinite(r.conversions)?r.conversions:0),0);
      const bidCost=items.reduce((s,r)=>s+(Number.isFinite(r.bid)&&Number.isFinite(r.conversions)?r.bid*r.conversions:0),0);
      const weightedBid=conversions>0?bidCost/conversions:null,ratio=registrations>0?conversions/registrations:null;
      const implied=weightedBid!==null&&ratio!==null?weightedBid*ratio:null;
      let chosen=null,confidence='settlement-only';
      if(unique.length===1)chosen=unique[0];
      else if(implied>0){
        const ranked=unique.filter(c=>c.expected>0).map(c=>({...c,score:Math.abs(Math.log(c.expected/implied))})).sort((a,b)=>a.score-b.score);
        if(ranked.length&&(!ranked[1]||ranked[1].score-ranked[0].score>=.05)){chosen=ranked[0];confidence='price-match'}
      }
      if(chosen)result.set(key,{rule:chosen.rule,detail:{method:confidence,weightedBid,callbackRatio:ratio,impliedSettlementUnit:implied,expectedSettlementUnit:chosen.expected,settlements:number(chosen.evidence.settlements),registrations:number(chosen.evidence.registrations)}});
    }
    return result;
  }
  function cashMetrics(row,price){
    const valid=n=>Number.isFinite(n)&&n>=0;
    const finite=n=>Number.isFinite(n)?n:null;
    const commission=valid(row.registrations)&&Number.isFinite(price)&&price>=0?finite(row.registrations*price):null;
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
    const commission=total('commission'),cashCost=total('cashCost');
    const cost=total('cost'),estimatedCompensation=total('estimatedCompensation');
    const roi=commission!==null&&cashCost>0?commission/cashCost:null;
    const estimatedRoi=commission!==null&&estimatedCompensation!==null&&cost>0
      ?(commission+estimatedCompensation)/cost:null;
    const bidRows=rows.filter(r=>Number.isFinite(r.commission)&&Number.isFinite(r.bidCost)&&Number.isFinite(r.bidProfitRate));
    const bidCommission=bidRows.reduce((sum,row)=>sum+row.commission,0),validBidCost=bidRows.reduce((sum,row)=>sum+row.bidCost,0);
    const bidProfitRate=bidCommission>0?(bidCommission-validBidCost)/bidCommission:null;
    return{roi:Number.isFinite(roi)?roi:null,estimatedRoi:Number.isFinite(estimatedRoi)?estimatedRoi:null,bidProfitRate:Number.isFinite(bidProfitRate)?bidProfitRate:null};
  }
  function analyzeTask(row,rules,margin,minSample,current,gapValue=1,inferred=null){
    const task=taskFor(row,rules,inferred);
    const gap=Number.isFinite(gapValue)&&gapValue>=0?gapValue:null;
    const price=task.price!==null&&gap!==null?task.price*gap:null;
    const result=analyze(row,price||1,margin,minSample,current);
    if(price===null||price===0){for(const key of ['breakEven','ceiling','revenue','profit','bidRoi','actualRoi','projectedProfit'])result[key]=null;result.status=task.price===null?task.pricingStatus:gap===null?'gap-missing':'zero-price';}
    return{...result,...task,basePrice:task.price,gap,price,...cashMetrics(row,price)};
  }
  function createAnalysisCache(){
    let previousRows,previousRules,previousCurrent,previousGaps,result;
    return (rows,rules,current,gaps)=>{
      // Pricing edits mutate rules in place; compare their small serialized value.
      const ruleKey=JSON.stringify(rules);
      if(rows!==previousRows||ruleKey!==previousRules||current!==previousCurrent||gaps!==previousGaps){
        const inferred=inferAccountTasks(rows,rules,gaps);
        result=rows.map(row=>analyzeTask(row,rules,0,20,current,gaps?.[row.accountId]?.gap??null,inferred.get(accountIdentity(row))));
        previousRows=rows;previousRules=ruleKey;previousCurrent=current;previousGaps=gaps;
      }
      return result;
    };
  }
  const accountIdentity=row=>row.accountId?JSON.stringify([row.platform,'id',String(row.accountId).trim()]):row.account?JSON.stringify([row.platform,'name',String(row.account).trim()]):JSON.stringify([row.platform,'plan',String(row.id||'')]);
  function aggregateGroups(rows,dimensions,statDate){
    const groups=new Map();
    for(const row of rows){
      const labels={platform:String(row.platform||'').trim()||'平台缺失',account:String(row.account||'').trim()||'账户名称缺失',accountId:String(row.accountId||'').trim()||'账户ID缺失',optimizer:String(row.optimizer||'').trim()||'未填写',task:String(row.task||'').trim()||'未匹配任务',
        externalAction:String(row.externalAction||'').trim()||'未填写',deepExternalAction:String(row.deepExternalAction||'').trim()||'未填写',
        appType:String(row.appType||'').trim()||'未填写'};
      const accountView=dimensions.includes('account')&&dimensions.includes('accountId');
      labels.accountKey=accountIdentity(row);
      const key=accountView?JSON.stringify([labels.accountKey,...dimensions.filter(d=>d!=='account'&&d!=='accountId').map(d=>labels[d])]):JSON.stringify(dimensions.map(dimension=>labels[dimension]));
      if(!groups.has(key))groups.set(key,{labels,items:[]});
      groups.get(key).items.push(row);
    }
    const sum=(rows,key)=>rows.reduce((total,row)=>total+(Number.isFinite(row[key])?row[key]:0),0);
    return [...groups.values()].map(({labels,items})=>{
      const cost=sum(items,'cost'),conversions=sum(items,'conversions'),registrations=sum(items,'registrations');
      const pricedItems=items.filter(row=>row.price!==null),cash=summarizeCash(pricedItems);
      const commission=pricedItems.length&&pricedItems.every(row=>Number.isFinite(row.commission))?sum(pricedItems,'commission'):null;
      return{...labels,plans:items.length,todayPlans:items.filter(row=>statDate&&String(row.createdAt||'').slice(0,10)===statDate).length,
        spendingPlans:items.filter(row=>Number.isFinite(row.cost)&&row.cost>0).length,
        accounts:new Set(items.map(row=>row.accountId||row.account).filter(Boolean)).size,
        cost,conversions,registrations,ratio:registrations>0?conversions/registrations:null,
        priced:pricedItems.length,commission,
        estimatedCompensation:pricedItems.length&&pricedItems.every(row=>Number.isFinite(row.estimatedCompensation))?sum(pricedItems,'estimatedCompensation'):null,
        cashCost:pricedItems.length&&pricedItems.every(row=>Number.isFinite(row.cashCost))?sum(pricedItems,'cashCost'):null,
        estimatedRoi:cash.estimatedRoi,bidProfitRate:cash.bidProfitRate};
    }).map(row=>({...row,profit:row.commission===null||row.cashCost===null?null:row.commission-row.cashCost}))
      .sort((a,b)=>b.cost-a.cost||dimensions.map(key=>a[key]).join(' ').localeCompare(dimensions.map(key=>b[key]).join(' '),'zh-CN'));
  }
  const aggregateOptimizers=(rows,date)=>aggregateGroups(rows,['optimizer'],date);
  const aggregateTasks=(rows,date)=>aggregateGroups(rows,['task'],date);
  const aggregateOptimizerTasks=(rows,date)=>aggregateGroups(rows,['optimizer','task'],date);
  const api={normalize,analyze,taskFor,inferAccountTasks,analyzeTask,cashMetrics,summarizeCash,createAnalysisCache,accountIdentity,aggregateGroups,aggregateOptimizers,aggregateTasks,aggregateOptimizerTasks};if(typeof module!=='undefined')module.exports=api;else root.BidMonitor=api;
})(globalThis);
