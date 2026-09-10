(function(root){
  'use strict';
  const aliases={id:['promotion_id','计划ID','广告ID','计划 ID'],name:['promotion_name','计划名称','广告名称'],platform:['platform_text','投放平台','平台'],account:['media_account_name','advertiser_nick','account_name','账户名称','广告账户名称'],optimizer:['user_name','优化师','优化师姓名'],accountId:['advertiser_id','账户ID','账户 ID'],internalAccountId:['media_account_id'],createdAt:['promotion_create_time','计划创建时间','创建时间'],cost:['stat_cost','消耗','总消耗'],impressions:['show_cnt','view_count','曝光数','展示数'],mediaCpm:['cpm_platform','cpm','媒体CPM','千次展示成本'],conversions:['convert_cnt','转化数'],registrations:['active_register','注册数','注册'],bid:['cpa_bid','出价','目标转化出价','目标转化成本'],appType:['app_type_text','应用类型'],deepBidType:['deep_bid_type_text','深度出价类型'],deepCpaBid:['deep_cpabid','深度CPA出价'],deepExternalAction:['deep_external_action_text','深度转化目标'],externalAction:['external_action_text','转化目标'],planStatus:['status_text','计划状态']};
  function value(row,keys){for(const key of keys){if(row[key]!==undefined&&row[key]!==null&&row[key]!=='')return row[key]}return null}
  function number(v){if(v===null||v===undefined||String(v).trim()==='')return null;const n=Number(String(v).replaceAll(',','').trim());return Number.isFinite(n)&&n>=0?n:null}
  function normalize(row){const out={};for(const [key,keys] of Object.entries(aliases))out[key]=value(row,keys);for(const key of ['cost','impressions','mediaCpm','conversions','registrations','bid','deepCpaBid'])out[key]=number(out[key]);out.impressionsEstimated=false;if(!(out.impressions>0)&&out.cost>0&&out.mediaCpm>0){out.impressions=out.cost/out.mediaCpm*1000;out.impressionsEstimated=true}out.ecpm=out.bid!==null&&out.conversions!==null&&out.impressions>0?out.bid*out.conversions/out.impressions*1000:null;for(const key of ['id','name','platform','account','accountId','internalAccountId','optimizer','createdAt','appType','deepBidType','deepExternalAction','externalAction','planStatus'])out[key]=String(out[key]??'');if(!out.account&&row.account_info&&typeof row.account_info==='object')out.account=String(row.account_info.media_account_name||row.account_info.account_name||'');return out}
  function usableDailyPrice(value,date){return !!date&&number(value?.price)!==null&&(value?.date===date||value?.fallbackFrom===date&&typeof value?.date==='string'&&value.date<date);}
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
    if(['daily-report-task','plan-name-task','bid-return-estimate'].includes(inferred?.detail?.method)){const price=number(inferred.rule?.price),task=String(inferred.rule?.name||inferred.task||'').trim(),sources={'daily-report-task':'daily-report','plan-name-task':'plan-name','bid-return-estimate':'bid-return'};return{task,price:price>0?price:null,pricingStatus:price>0?'priced':'price-missing',taskSource:sources[inferred.detail.method],inference:inferred.detail}}
    if(matches.length!==1){
      if(!matches.length&&inferred){const price=number(inferred.rule?.price),task=String(inferred.rule?.name||'').trim();return{task,price,pricingStatus:'priced',taskSource:'inferred',inference:inferred.detail}}
      return{task:'',price:null,pricingStatus:matches.length?'task-conflict':'task-missing',taskSource:''};
    }
    const price=number(matches[0].price),task=String(matches[0].name||'').trim(),valid=Boolean(task)&&price>0&&price<=1000000;
    return{task,price:valid?price:null,pricingStatus:valid?'priced':'price-missing',taskSource:'account-name'};
  }
  const canonicalId=value=>String(value??'').trim().replace(/\.0+$/,'').replace(/^0+(?=\d)/,'');
  function buildGapIndex(gaps){const index=new Map();for(const [id,value] of Object.entries(gaps||{})){index.set(String(id),value);const canonical=canonicalId(id);if(canonical)index.set(canonical,value)}return index}
  function gapFor(row,index){for(const id of [row.accountId,row.internalAccountId]){if(index.has(String(id)))return index.get(String(id));const canonical=canonicalId(id);if(canonical&&index.has(canonical))return index.get(canonical)}return null}
  const inferenceIdentity=row=>JSON.stringify([accountIdentity(row),String(row.optimizer||'').trim().toLowerCase()]);
  function inferAccountTasks(rows,rules,gaps,references){
    const gapIndex=buildGapIndex(gaps);
    const result=new Map(),groups=new Map();
    const remember=(key,row,value)=>{result.set(key,value);const legacy=accountIdentity(row);if(!result.has(legacy))result.set(legacy,value);};
    for(const row of rows){if(!/(广点通|gdt)/i.test(row.platform)&&taskFor(row,rules).task)continue;const key=inferenceIdentity(row);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(row)}
    for(const [key,items] of groups){
      const evidence=gapFor(items[0],gapIndex)||{};
      const optimizer=String(items[0].optimizer||'').trim();
      const optimizerEvidence=Object.entries(evidence.taskByOptimizer||{}).find(([name])=>name.trim().toLowerCase()===optimizer.toLowerCase())?.[1];
      const dailyEvidence=optimizerEvidence?.taskName?optimizerEvidence:evidence;
      const reported=String(dailyEvidence.taskName||'').trim().toLowerCase();
      if(reported){
        let matched=rules.filter(rule=>String(rule.name||'').trim().toLowerCase()===reported);
        if(!matched.length)matched=rules.filter(rule=>{const name=String(rule.name||'').trim().toLowerCase();return name&&(name.includes(reported)||reported.includes(name))});
        const detail={method:'daily-report-task',reportedTaskName:dailyEvidence.taskName,taskDate:dailyEvidence.taskDate,optimizer};
        if(matched.length===1)remember(key,items[0],{rule:matched[0],detail});
        else remember(key,items[0],{task:dailyEvidence.taskName,detail});
        continue
      }
      if(taskFor(items[0],rules).task)continue;
      if(references?.priceDate){
        // Exact task-name evidence only: do not guess tasks from a similar price.
        const name=String(items[0].account||'').toLowerCase(),planNames=items.map(row=>String(row.name||'').toLowerCase());
        const candidates=Object.keys(references.tasks||{}).filter(task=>task.trim()&&(name.includes(task.toLowerCase())||planNames.every(plan=>plan.includes(task.toLowerCase()))));
        if(candidates.length===1){const task=candidates[0],matched=rules.filter(rule=>String(rule.name||'').trim().toLowerCase()===task.toLowerCase());remember(key,items[0],{task,rule:matched.length===1?matched[0]:null,detail:{method:'plan-name-task',reportedTaskName:task}});}
        continue;
      }
      const historical=number(evidence.settlementPrice);
      if(!(historical>0))continue;
      const ranked=rules.map(rule=>({rule,price:number(rule.price)})).filter(item=>item.price>0)
        .map(item=>({...item,difference:Math.abs(item.price-historical)})).sort((a,b)=>a.difference-b.difference);
      const best=ranked[0];
      if(best&&(!ranked[1]||ranked[1].difference-best.difference>1e-6))
        remember(key,items[0],{rule:best.rule,detail:{method:'historical-settlement-price',settlementPrice:historical,settlementPriceDate:evidence.settlementPriceDate,matchedPrice:best.price,dailyTaskMissing:true}});
    }
    return result;
  }
  function inferTaskFromBidReturn(row,rules,account,references){
    const direct=taskFor(row,rules);
    if(direct.task||direct.pricingStatus==='task-conflict'||!Number.isFinite(row.bid)||row.bid<0||!Number.isFinite(row.conversions)||row.conversions<0||!Number.isFinite(row.registrations)||row.registrations<=0)return null;
    const ratio=row.conversions/row.registrations,estimated=row.bid*ratio;
    if(!Number.isFinite(estimated)||estimated<0)return null;
    const exact=(object,name)=>Object.entries(object||{}).find(([key])=>key.trim().toLowerCase()===name.trim().toLowerCase())?.[1];
    const names=[...new Set([...Object.keys(references?.tasks||{}),...rules.map(rule=>String(rule.name||'').trim()).filter(Boolean)])];
    const candidates=[];
    for(const task of names){
      const rule=rules.find(item=>String(item.name||'').trim().toLowerCase()===task.trim().toLowerCase()),taskReference=exact(references?.tasks,task);
      const splits=account?.dailyPricesByTask||{},split=exact(splits,task),manual=number(rule?.price),accountDaily=split||(Object.keys(splits).length===0?account?.dailyPrice:null);
      const daily=usableDailyPrice(accountDaily,references?.priceDate)?accountDaily:usableDailyPrice(taskReference?.dailyPrice,references?.priceDate)?taskReference.dailyPrice:null;
      const basePrice=manual>0?manual:number(daily?.price),gap=number(account?.gap)??number(taskReference?.gap);
      const actualPrice=basePrice!==null&&gap!==null?basePrice*gap:null;
      if(Number.isFinite(actualPrice)&&actualPrice>=0)candidates.push({task,rule,actualPrice,difference:Math.abs(actualPrice-estimated)});
    }
    candidates.sort((a,b)=>a.difference-b.difference||a.task.localeCompare(b.task,'zh-CN'));
    const best=candidates[0];if(!best||candidates[1]&&Math.abs(candidates[1].difference-best.difference)<=1e-6)return null;
    return{task:best.task,rule:best.rule||null,detail:{method:'bid-return-estimate',estimatedSettlementPrice:estimated,matchedActualPrice:best.actualPrice,difference:best.difference,returnRatio:ratio}};
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
      const eligible=row.conversions>=6&&row.cost-threshold>tolerance;
      grant=eligible?row.cost-bidCost:0;
      cashCost=eligible?bidCost:row.cost;
      estimatedCompensation=eligible?Math.max(0,row.cost-bidCost):0;
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
  function analyzeTask(row,rules,margin,minSample,current,gapValue=1,inferred=null,resolved=null){
    const task=resolved?.taskResult||taskFor(row,rules,inferred);
    const gap=Number.isFinite(gapValue)&&gapValue>=0?gapValue:null;
    const basePrice=resolved?resolved.basePrice:task.price;
    const price=basePrice!==null&&gap!==null?basePrice*gap:null;
    const result=analyze(row,price||1,margin,minSample,current);
    if(price===null||price===0){for(const key of ['breakEven','ceiling','revenue','profit','bidRoi','actualRoi','projectedProfit'])result[key]=null;result.status=basePrice===null?task.pricingStatus:gap===null?'gap-missing':'zero-price';}
    return{...result,...task,basePrice,gap,price,...cashMetrics(row,price),...(resolved?.metadata||{})};
  }
  function resolveDailyInputs(row,rules,gapIndex,references,inferred){
    const taskResult=taskFor(row,rules,inferred),account=gapFor(row,gapIndex),taskName=String(taskResult.inference?.reportedTaskName||taskResult.task||'').trim();
    const findTask=object=>Object.entries(object||{}).find(([name])=>name.trim().toLowerCase()===taskName.toLowerCase())?.[1];
    const taskReference=taskName?findTask(references?.tasks):null;
    const sameDay=value=>usableDailyPrice(value,references?.priceDate);
    let basePrice=taskResult.price,priceSource=basePrice!==null?'manual':'',priceDate='',priceReason='';
    if(basePrice===null){
      const split=account?.dailyPricesByTask||{},splitNames=Object.keys(split);
      const accountPrice=taskName?(findTask(split)||(splitNames.length===0?account?.dailyPrice:null)):(splitNames.length<=1?account?.dailyPrice:null);
      if(sameDay(accountPrice)){basePrice=number(accountPrice.price);priceSource='daily-account';priceDate=accountPrice.date;}
      else if(sameDay(taskReference?.dailyPrice)){basePrice=number(taskReference.dailyPrice.price);priceSource='daily-task';priceDate=taskReference.dailyPrice.date;}
      else priceReason=references?.priceDate?`${references.priceDate} 无可用日报结算单价${taskName?'':'，且任务未识别'}`:'未取得日报单价数据';
    }
    let gap=number(account?.gap),gapSource=gap!==null?'account':'',gapReason='';
    if(gap===null&&number(taskReference?.gap)!==null){gap=number(taskReference.gap);gapSource='task-reference';}
    if(gap===null)gapReason=account?'账户及同任务均无可计算的历史 gap':'账户未关联到历史日报，且无同任务 gap';
    const missingReason=[basePrice===null?priceReason:'',gap===null?gapReason:''].filter(Boolean).join('；');
    return{taskResult,basePrice,gap,metadata:{priceSource,priceDate,gapSource,referenceTask:taskName,missingReason,priceReason,gapReason}};
  }
  function createAnalysisCache(){
    let previousRows,previousRules,previousCurrent,previousGaps,previousReferences,result;
    return (rows,rules,current,gaps,references)=>{
      // Pricing edits mutate rules in place; compare their small serialized value.
      const ruleKey=JSON.stringify(rules);
      if(rows!==previousRows||ruleKey!==previousRules||current!==previousCurrent||gaps!==previousGaps||references!==previousReferences){
        const inferred=inferAccountTasks(rows,rules,gaps,references),gapIndex=buildGapIndex(gaps);
        result=rows.map(row=>{const account=gapFor(row,gapIndex),match=inferred.get(inferenceIdentity(row))||inferTaskFromBidReturn(row,rules,account,references),resolved=resolveDailyInputs(row,rules,gapIndex,references,match);return analyzeTask(row,rules,0,20,current,resolved.gap,match,resolved);});
        previousRows=rows;previousRules=ruleKey;previousCurrent=current;previousGaps=gaps;previousReferences=references;
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
      const allCashCost=items.length&&items.every(row=>Number.isFinite(row.cashCost))?sum(items,'cashCost'):null;
      const allCompensation=items.length&&items.every(row=>Number.isFinite(row.estimatedCompensation))?sum(items,'estimatedCompensation'):null;
      const pricedCashCost=pricedItems.length&&pricedItems.every(row=>Number.isFinite(row.cashCost))?sum(pricedItems,'cashCost'):null;
      const commission=pricedItems.length&&pricedItems.every(row=>Number.isFinite(row.commission))?sum(pricedItems,'commission'):null;
      return{...labels,plans:items.length,todayPlans:items.filter(row=>statDate&&String(row.createdAt||'').slice(0,10)===statDate).length,
        spendingPlans:items.filter(row=>Number.isFinite(row.cost)&&row.cost>0).length,
        accounts:new Set(items.map(row=>row.accountId||row.account).filter(Boolean)).size,
        cost,conversions,registrations,ratio:registrations>0?conversions/registrations:null,
        priced:pricedItems.length,commission,
        estimatedCompensation:allCompensation,cashCost:allCashCost,pricedCashCost,
        estimatedRoi:cash.estimatedRoi,bidProfitRate:cash.bidProfitRate};
    }).map(row=>({...row,profit:row.commission===null||row.pricedCashCost===null?null:row.commission-row.pricedCashCost}))
      .sort((a,b)=>b.cost-a.cost||dimensions.map(key=>a[key]).join(' ').localeCompare(dimensions.map(key=>b[key]).join(' '),'zh-CN'));
  }
  const aggregateOptimizers=(rows,date)=>aggregateGroups(rows,['optimizer'],date);
  const aggregateTasks=(rows,date)=>aggregateGroups(rows,['task'],date);
  const aggregateOptimizerTasks=(rows,date)=>aggregateGroups(rows,['optimizer','task'],date);
  const api={normalize,analyze,taskFor,inferAccountTasks,inferTaskFromBidReturn,inferenceIdentity,analyzeTask,cashMetrics,summarizeCash,createAnalysisCache,accountIdentity,aggregateGroups,aggregateOptimizers,aggregateTasks,aggregateOptimizerTasks};if(typeof module!=='undefined')module.exports=api;else root.BidMonitor=api;
})(globalThis);
