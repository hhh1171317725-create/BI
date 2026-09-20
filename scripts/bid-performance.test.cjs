const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const B=require('../frontend/bid-monitor-core.js');

function legacyHistory(rows,rules,references){
  const prepared=B.withOverallConversions(rows),groups=new Map(),output=new Array(rows.length);
  prepared.forEach((row,index)=>{if(!groups.has(row.statDate))groups.set(row.statDate,[]);groups.get(row.statDate).push({row,index});});
  for(const [date,items] of groups){
    const indexes=new Set(items.map(item=>item.index)),prior=prepared.filter((_,index)=>!indexes.has(index)),daily=references.get(date);
    const result=B.createAnalysisCache()(items.map(item=>item.row),rules,false,daily?.accounts,daily,prior);
    items.forEach((item,index)=>output[item.index]=result[index]);
  }
  return output;
}

test('single-pass history retains daily prices, missing references, order and cumulative eligibility',()=>{
  const rows=[],references=new Map();
  for(let day=1;day<=15;day++){
    const date=`2026-09-${String(day).padStart(2,'0')}`;
    if(day!==7)references.set(date,{priceDate:date,accounts:{a:{gap:.5,taskName:'任务甲',dailyPricesByTask:{'任务甲':{date,price:day}}}},tasks:{}});
    for(let plan=0;plan<40;plan++)rows.push(B.normalize({promotion_id:String(plan),advertiser_id:'a',source_platform:'gdt',platform_text:'广点通',advertiser_nick:'账户',report_date:date,stat_cost:50,convert_cnt:plan%3,active_register:20,cpa_bid:10}));
  }
  rows.reverse();
  const actual=B.analyzeHistoricalRows(rows,[],references);
  assert.deepEqual(actual,legacyHistory(rows,[],references));
  const eligible=actual.find(row=>row.id==='1'&&row.statDate==='2026-09-15');
  assert.equal(eligible.overallConversions,15);
  assert.equal(eligible.estimatedCompensation,40);
  assert.equal(eligible.basePrice,15);
  assert.equal(actual.find(row=>row.statDate==='2026-09-07').estimatedRoi,null);
});

test('pagination reuses sorting; sort direction and changed data invalidate it',()=>{
  const source=fs.readFileSync(require('node:path').join(__dirname,'../frontend/bid-monitor.js'),'utf8');
  let calls=0;
  const context=vm.createContext({sortKey:'cost',sortDirection:'desc',sortRows:rows=>{calls++;return rows.sort((a,b)=>context.sortDirection==='desc'?b.cost-a.cost:a.cost-b.cost);}});
  vm.runInContext(source.slice(source.indexOf('let sortedRowsSource='),source.indexOf('const reportTextCollator=')),context);
  const rows=[{cost:1},{cost:3},{cost:2}];
  const sorted=context.sortedReportRows(rows);
  assert.equal(sorted[0].cost,3);assert.equal(rows[0].cost,1);
  assert.equal(context.sortedReportRows(rows),sorted);assert.equal(calls,1);
  context.sortDirection='asc';assert.equal(context.sortedReportRows(rows)[0].cost,1);assert.equal(calls,2);
  context.sortedReportRows([...rows,{cost:5}]);assert.equal(calls,3);
});
