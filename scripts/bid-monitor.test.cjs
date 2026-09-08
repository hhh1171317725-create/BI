const test=require('node:test');
const assert=require('node:assert/strict');
const {analyze,normalize,analyzeTask}=require('../frontend/bid-monitor-core.js');
const {cashMetrics,summarizeCash,aggregateOptimizers,aggregateTasks,aggregateOptimizerTasks}=require('../frontend/bid-monitor-core.js');
const row={cost:2000,registrations:1000,conversions:150,bid:130};
test('gap changes actual price and related financial metrics; missing never defaults to one',()=>{
 const rules=[{name:'甲',keyword:'account',price:2}];
 const source={...row,account:'account',cost:100,conversions:10,registrations:100,bid:5};
 const half=analyzeTask(source,rules,0,20,false,.5);
 assert.equal(half.basePrice,2);assert.equal(half.price,1);assert.equal(half.commission,100);assert.equal(half.breakEvenBid,10);
 const missing=analyzeTask(source,rules,0,20,false,null);assert.equal(missing.price,null);assert.equal(missing.commission,null);assert.equal(missing.estimatedRoi,null);
 const zero=analyzeTask(source,rules,0,20,false,0);assert.equal(zero.price,0);assert.equal(zero.commission,0);
});
test('account ID uses advertiser_id and never substitutes Chuangliang internal ID',()=>{
 assert.equal(normalize({advertiser_id:'1866402186668232',media_account_id:'12601552720'}).accountId,'1866402186668232');
 assert.equal(normalize({media_account_id:'12601552720'}).accountId,'');
 assert.equal(normalize({'账户ID':'1866402186668232'}).accountId,'1866402186668232');
});
test('retains optimizer from upstream, snapshots and Excel without inventing missing names',()=>{
 assert.equal(normalize({user_name:'张三'}).optimizer,'张三');
 assert.equal(normalize({'优化师':'李四'}).optimizer,'李四');
 assert.equal(normalize({}).optimizer,'');
 const r=normalize({promotion_id:'7681075475582042163',advertiser_id:'7676449794404745237',media_account_id:'12601552720',user_name:'张三'});
 assert.equal(r.id,'7681075475582042163');assert.equal(r.accountId,'7676449794404745237');
});
test('retains optional Chuangliang fields for report columns and filters',()=>{
 const result=normalize({app_type_text:'小程序',deep_bid_type_text:'深度转化',deep_cpabid:'88.5',deep_external_action_text:'深度付费',external_action_text:'注册',status_text:'投放中'});
 assert.equal(result.appType,'小程序');assert.equal(result.deepBidType,'深度转化');assert.equal(result.deepCpaBid,88.5);
 assert.equal(result.deepExternalAction,'深度付费');assert.equal(result.externalAction,'注册');assert.equal(result.planStatus,'投放中');
});
test('aggregates every plan by optimizer with weighted metrics',()=>{
 const rules=[{name:'A',keyword:'account',price:10}];
 const rows=[
  analyzeTask({...row,id:'1',accountId:'a',account:'account-a',optimizer:'张三',cost:150,conversions:10,registrations:20,bid:10},rules,0,20,false),
  analyzeTask({...row,id:'2',accountId:'b',account:'account-b',optimizer:'张三',cost:600,conversions:6,registrations:10,bid:10},rules,0,20,false),
  analyzeTask({...row,id:'3',accountId:'c',account:'unknown',optimizer:'',cost:5,conversions:0,registrations:0,bid:1},rules,0,20,false)
 ];
 const result=aggregateOptimizers(rows);
 assert.equal(result.length,2);assert.equal(result[0].optimizer,'张三');assert.equal(result[0].plans,2);assert.equal(result[0].accounts,2);
 assert.equal(result[0].cost,750);assert.equal(result[0].conversions,16);assert.equal(result[0].registrations,30);assert.equal(result[0].profit,-400);
 assert.equal(result[0].estimatedRoi,(300+50+540)/750);assert.equal(result[1].optimizer,'未填写');assert.equal(result[1].priced,0);assert.equal(result[1].profit,null);
});
test('aggregate financial metrics use the priced subset without treating unmatched plans as zero',()=>{
 const rules=[{name:'A',keyword:'account-a',price:10}];
 const rows=[
  analyzeTask({id:'1',accountId:'a',account:'account-a',optimizer:'张三',cost:150,conversions:10,registrations:20,bid:10},rules,0,20,false),
  analyzeTask({id:'2',accountId:'b',account:'unknown',optimizer:'张三',cost:900,conversions:9,registrations:90,bid:10},rules,0,20,false)
 ];
 const result=aggregateOptimizers(rows)[0];
 assert.equal(result.plans,2);assert.equal(result.priced,1);assert.equal(result.cost,1050);
 assert.equal(result.commission,200);assert.equal(result.estimatedCompensation,50);assert.equal(result.cashCost,100);assert.equal(result.profit,100);
 assert.equal(result.estimatedRoi,250/150);assert.equal(result.bidProfitRate,.5);
});
test('aggregates task and optimizer-task dimensions with new and spending plan counts',()=>{
 const rows=[
  {optimizer:'张三',task:'任务A',accountId:'a',createdAt:'2026-09-05 08:00:00',cost:10,conversions:2,registrations:4,price:10,commission:40,cashCost:10,estimatedCompensation:0,bidCost:8,bidProfitRate:.8},
  {optimizer:'张三',task:'任务A',accountId:'b',createdAt:'2026-09-04 08:00:00',cost:0,conversions:0,registrations:0,price:10,commission:0,cashCost:0,estimatedCompensation:0,bidCost:0,bidProfitRate:0},
  {optimizer:'李四',task:'任务A',accountId:'c',createdAt:'2026-09-05T09:00:00',cost:5,conversions:1,registrations:2,price:10,commission:20,cashCost:5,estimatedCompensation:0,bidCost:4,bidProfitRate:.8},
  {optimizer:'李四',task:'',accountId:'d',createdAt:'',cost:0,conversions:0,registrations:0,price:null,commission:null,cashCost:null,estimatedCompensation:null,bidCost:null,bidProfitRate:null}
 ];
 const tasks=aggregateTasks(rows,'2026-09-05'),combinations=aggregateOptimizerTasks(rows,'2026-09-05');
 assert.equal(tasks.length,2);assert.equal(tasks[0].task,'任务A');assert.equal(tasks[0].plans,3);
 assert.equal(tasks[0].todayPlans,2);assert.equal(tasks[0].spendingPlans,2);
 assert.equal(combinations.length,3);const zhang=combinations.find(r=>r.optimizer==='张三'&&r.task==='任务A');
 assert.equal(zhang.plans,2);assert.equal(zhang.todayPlans,1);assert.equal(zhang.spendingPlans,1);
 assert.ok(combinations.some(r=>r.task==='未匹配任务'));
});
test('aggregates by conversion, deep conversion and app type dimensions',()=>{
 const rows=[
  {externalAction:'注册',deepExternalAction:'付费',appType:'应用',accountId:'a',cost:10,conversions:2,registrations:4,price:null},
  {externalAction:'注册',deepExternalAction:'付费',appType:'应用',accountId:'b',cost:20,conversions:3,registrations:6,price:null},
  {externalAction:'激活',deepExternalAction:'',appType:'小程序',accountId:'a',cost:5,conversions:1,registrations:2,price:null}
 ];
 const groups=require('../frontend/bid-monitor-core.js').aggregateGroups(rows,['externalAction','deepExternalAction','appType']);
 assert.equal(groups.length,2);assert.equal(groups[0].externalAction,'注册');assert.equal(groups[0].deepExternalAction,'付费');
 assert.equal(groups[0].appType,'应用');assert.equal(groups[0].plans,2);assert.equal(groups[0].accounts,2);assert.equal(groups[0].cost,30);
 assert.equal(groups[1].deepExternalAction,'未填写');
});
test('aggregates account dimension by account name and advertiser ID',()=>{
 const rows=[
  {account:'同名账户',accountId:'1001',cost:10,conversions:2,registrations:4,price:null},
  {account:'同名账户',accountId:'1002',cost:20,conversions:3,registrations:6,price:null},
  {account:'同名账户',accountId:'1001',cost:5,conversions:1,registrations:2,price:null}
 ];
 const groups=require('../frontend/bid-monitor-core.js').aggregateGroups(rows,['account','accountId']);
 assert.equal(groups.length,2);
 const account=groups.find(row=>row.accountId==='1001');
 assert.equal(account.account,'同名账户');assert.equal(account.plans,2);assert.equal(account.accounts,1);assert.equal(account.cost,15);
});
test('15% return rate uses division for break-even bid',()=>{
 const r=analyze(row,21.5,10,20,false);
 assert.equal(r.ratio,.15);assert.ok(Math.abs(r.breakEven-143.3333333333)<1e-6);
 assert.equal(r.ceiling,129);assert.equal(r.status,'margin-bid');assert.equal(r.profit,19500);
});
test('assumed conversion cost equals bid, profit and ROI differ from actual-spend profit',()=>{
 const r=analyze(row,21.5,10,20,false);
 assert.equal(r.projectedCost,19500);assert.equal(r.projectedProfit,2000);
 assert.ok(Math.abs(r.bidRoi-21500/19500)<1e-12);assert.equal(r.profit,19500);
 for(const change of [{bid:0},{conversions:0},{registrations:0},{conversions:1001},{bid:null}]){
  const invalid=analyze({...row,...change},21.5,0,20,false);assert.equal(invalid.projectedProfit,null);assert.equal(invalid.bidRoi,null);
 }
});
test('matches account names to independently priced tasks and refuses ambiguous prices',()=>{
 const rules=[{name:'A',keyword:'account-A',price:20},{name:'B',keyword:'account-B',price:30}];
 const a=analyzeTask({...row,account:'client-ACCOUNT-a-01'},rules,0,20,false);
 const b=analyzeTask({...row,account:'client-account-B-01'},rules,0,20,false);
 assert.equal(a.task,'A');assert.equal(a.projectedProfit,500);assert.equal(b.projectedProfit,10500);
 for(const account of ['','unknown','account-A-account-B']){
  const r=analyzeTask({...row,account},rules,0,20,false);assert.equal(r.price,null);assert.equal(r.profit,null);assert.equal(r.projectedProfit,null);assert.equal(r.bidRoi,null);
 }
 assert.equal(normalize({advertiser_nick:'actual account'}).account,'actual account');
});
test('inactive priced plans contribute zero profit without inventing a zero-cost ROI',()=>{
 const r=analyze({cost:0,registrations:0,conversions:0,bid:20},21.5,0,20,true);
 assert.equal(r.projectedCost,0);assert.equal(r.projectedProfit,0);assert.equal(r.bidRoi,null);
 const missingCost=analyze({...row,cost:null},21.5,0,20,false);
 assert.equal(missingCost.revenue,21500);assert.equal(missingCost.projectedProfit,2000);assert.equal(missingCost.profit,null);
});
test('missing, zero and abnormal rates never report a safe bid',()=>{
 assert.equal(analyze({...row,registrations:0},20,0,20,false).status,'no-register');
 assert.equal(analyze({...row,conversions:0},20,0,20,false).status,'no-return');
 assert.equal(analyze({...row,bid:null},20,0,20,false).status,'missing');
 assert.equal(analyze({...row,conversions:1001},20,0,20,false).status,'abnormal');
});
test('today and sparse data are provisional',()=>{
 assert.equal(analyze(row,20,0,20,true).status,'pending');
 assert.equal(analyze({...row,conversions:5},20,0,20,false).status,'sample');
 assert.equal(analyze({...row,bid:200},20,0,20,false).status,'loss-bid');
 assert.equal(analyze({...row,bid:100},20,0,20,false).status,'within');
});
test('Chinese export headers and nulls preserve meaning and string IDs',()=>{
 const r=normalize({'计划ID':'7676449794404745237','消耗':'1,234.50','转化数':'0','注册数':'--','出价':'20'});
 assert.equal(r.id,'7676449794404745237');assert.equal(r.cost,1234.5);assert.equal(r.conversions,0);assert.equal(r.registrations,null);
 assert.equal(normalize({stat_cost:''}).cost,null);
});
test('cash ROI deducts grant only when both strict conditions hold',()=>{
 const base={cost:150,conversions:10,registrations:20,bid:10};
 const r=cashMetrics(base,10);
 assert.equal(r.commission,200);assert.equal(r.grant,50);assert.equal(r.cashCost,100);assert.equal(r.roi,2);assert.equal(r.bidProfitRate,.5);
 for(const cost of [100,119.99,120]){
  const r=cashMetrics({...base,cost},10);assert.equal(r.grant,0);assert.equal(r.cashCost,cost);assert.equal(r.roi,200/cost);
 }
 assert.ok(Math.abs(cashMetrics({...base,cost:120.01},10).grant-20.01)<1e-12);
 assert.equal(cashMetrics({...base,conversions:6},10).grant,0);
 assert.equal(cashMetrics({...base,conversions:7},10).grant,80);
 assert.equal(cashMetrics({...base,bid:.3,conversions:7,cost:2.52},10).grant,0);
});
test('estimated ROI adds compensation when conversion cost exceeds 1.2 times bid',()=>{
 const base={cost:150,conversions:10,registrations:20,bid:10};
 const eligible=cashMetrics(base,10);
 assert.equal(eligible.commission,200);assert.equal(eligible.estimatedCompensation,50);assert.equal(eligible.estimatedRoi,250/150);
 const boundary=cashMetrics({...base,cost:120},10);
 assert.equal(boundary.estimatedCompensation,0);assert.equal(boundary.estimatedRoi,200/120);
 const sixConversions=cashMetrics({...base,cost:600,conversions:6,registrations:10},10);
 assert.equal(sixConversions.grant,0);assert.equal(sixConversions.estimatedCompensation,540);assert.equal(sixConversions.estimatedRoi,640/600);
 assert.equal(cashMetrics({...base,cost:0},10).estimatedRoi,null);
 assert.equal(cashMetrics(base,null).estimatedRoi,null);
});
test('cash ROI handles zero conversions, zero cash and missing inputs independently',()=>{
 const base={cost:150,conversions:10,registrations:20,bid:10};
 const zero=cashMetrics({...base,conversions:0},10);
 assert.equal(zero.grant,0);assert.equal(zero.cashCost,150);assert.equal(zero.roi,200/150);assert.equal(zero.bidProfitRate,null);
 assert.equal(cashMetrics({...base,cost:0},10).roi,null);
 assert.equal(cashMetrics({...base,bid:0},10).cashCost,0);assert.equal(cashMetrics({...base,bid:0},10).roi,null);
 assert.equal(cashMetrics({...base,registrations:0},10).roi,0);
 for(const key of ['cost','conversions','bid'])assert.equal(cashMetrics({...base,[key]:null},10).roi,null);
 const noPrice=cashMetrics(base,null);assert.equal(noPrice.cashCost,100);assert.equal(noPrice.roi,null);assert.equal(noPrice.bidProfitRate,null);
 assert.equal(cashMetrics({...base,cost:null},10).bidProfitRate,.5);
 assert.equal(cashMetrics({...base,registrations:5},10).bidProfitRate,-1);
});
test('cash summary applies grant per plan and weights ROI and bid profit by their denominators',()=>{
 const rows=[cashMetrics({cost:150,conversions:10,registrations:20,bid:10},10),cashMetrics({cost:600,conversions:6,registrations:10,bid:10},10)];
 const total=summarizeCash(rows);
 assert.equal(total.roi,300/700);assert.equal(total.bidProfitRate,140/300);
 assert.equal(total.estimatedRoi,(300+50+540)/750);
 assert.notEqual(total.roi,(rows[0].roi+rows[1].roi)/2);
 assert.equal(summarizeCash([]).roi,null);
 const partial=summarizeCash([...rows,{commission:null,cashCost:1,bidCost:1,bidProfitRate:null}]);assert.equal(partial.roi,null);assert.equal(partial.bidProfitRate,140/300);
});
test('bid profit rate compares the current bid to the break-even bid, not cash ROI',()=>{
 const base={cost:300,conversions:10,registrations:100,bid:80};
 const r=cashMetrics(base,10);
 assert.equal(r.breakEvenBid,100);assert.equal(r.bidProfitRate,.2);assert.equal(r.roi,1000/300);
 assert.equal(cashMetrics({...base,bid:100},10).bidProfitRate,0);
 assert.equal(cashMetrics({...base,bid:120},10).bidProfitRate,-.2);
 assert.equal(cashMetrics({...base,bid:0},10).bidProfitRate,1);
 assert.equal(cashMetrics({...base,cost:3000},10).bidProfitRate,.2);
 assert.equal(cashMetrics({...base,conversions:0},10).breakEvenBid,null);
 assert.equal(cashMetrics(base,null).breakEvenBid,null);
});
