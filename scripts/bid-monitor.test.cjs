const test=require('node:test');
const assert=require('node:assert/strict');
const {analyze,normalize,analyzeTask}=require('../frontend/bid-monitor-core.js');
const row={cost:2000,registrations:1000,conversions:150,bid:130};
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
