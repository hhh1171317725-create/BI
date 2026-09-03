const test=require('node:test');
const assert=require('node:assert/strict');
const {analyze,normalize,analyzeTask}=require('../frontend/bid-monitor-core.js');
const {cashMetrics,summarizeCash}=require('../frontend/bid-monitor-core.js');
const row={cost:2000,registrations:1000,conversions:150,bid:130};
test('retains optimizer from upstream, snapshots and Excel without inventing missing names',()=>{
 assert.equal(normalize({user_name:'张三'}).optimizer,'张三');
 assert.equal(normalize({'优化师':'李四'}).optimizer,'李四');
 assert.equal(normalize({}).optimizer,'');
 const r=normalize({promotion_id:'7681075475582042163',media_account_id:'7676449794404745237',user_name:'张三'});
 assert.equal(r.id,'7681075475582042163');assert.equal(r.accountId,'7676449794404745237');
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
 assert.notEqual(total.roi,(rows[0].roi+rows[1].roi)/2);
 assert.equal(summarizeCash([]).roi,null);
 assert.equal(summarizeCash([...rows,{commission:null,cashCost:1,bidCost:1,bidProfitRate:null}]).roi,null);
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
