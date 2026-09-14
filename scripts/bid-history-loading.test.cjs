const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../frontend/bid-monitor.js'),'utf8');
const loader=source.slice(source.indexOf('async function fetchHistoryRange('),source.indexOf('window.loadBidHistoryRange='));

test('history and live snapshot start together and safely merge 200000 archived rows',async()=>{
  const requests=[];
  const rows=Array.from({length:200000},(_,i)=>({promotion_id:String(i),report_date:'2026-09-13'}));
  const context=vm.createContext({Date,AbortSignal,today:()=> '2026-09-14',api:path=>new Promise(resolve=>{
    requests.push({path,resolve});
    if(requests.length===2){
      requests.find(r=>r.path.includes('/history?')).resolve({rows});
      requests.find(r=>r.path.includes('/shared-report')).resolve({snapshot:{date:'2026-09-14',updatedAt:'2026-09-14T01:00:00Z',rows:[{promotion_id:'42'}]}});
    }
  })});
  vm.runInContext(loader,context);
  const pending=context.fetchHistoryRange('2026-09-01','2026-09-14');
  assert.equal(requests.length,2,'Both sources must start before either response arrives');
  const result=await pending;
  assert.equal(result.count,200001);
  assert.equal(result.archivedCount,200000);
  assert.equal(result.liveCount,1);
  assert.equal(result.rows.at(-1).report_date,'2026-09-14');
  assert.match(requests[0].path,/endDate=2026-09-13/);
});

test('stale realtime snapshot rejects the combined range instead of returning partial totals',async()=>{
  const context=vm.createContext({Date,AbortSignal,today:()=> '2026-09-14',api:async path=>path.includes('/history?')?{rows:[]}:{snapshot:{date:'2026-09-13',updatedAt:'2026-09-13T00:00:00Z',rows:[]}}});
  vm.runInContext(loader,context);
  await assert.rejects(context.fetchHistoryRange('2026-09-01','2026-09-14'),/今日.*尚未生成/);
});

test('lightweight prior conversions preserve platform/account identity and the six-conversion threshold',async()=>{
  const B=require('../frontend/bid-monitor-core.js');
  const identity={promotion_id:'123',media_account_id:'456',advertiser_id:'789',platform_text:'广点通',source_platform:'gdt'};
  const current=B.normalize({...identity,convert_cnt:1,stat_cost:20,cpa_bid:10,active_register:10});
  let requested='';
  const context=vm.createContext({Date,AbortSignal,B,priorConversionGeneration:1,historyMode:false,range:{end:'2026-09-14'},priorConversionRows:[],render:()=>{},$:()=>({value:'',textContent:''}),api:async path=>{
    requested=path;
    return {startDate:'2026-09-10',endDate:'2026-09-13',rows:[{...identity,convert_cnt:5},{...identity,advertiser_id:'other',convert_cnt:100}]};
  }});
  vm.runInContext(source.slice(source.indexOf('async function loadPriorPlanConversions('),source.indexOf('function setBusy(')),context);
  await context.loadPriorPlanConversions('2026-09-14',[current],1);
  assert.match(requested,/\/history\/conversions\?/);
  assert.equal(context.priorConversionRows.length,1);
  const prepared=B.withOverallConversions([current],context.priorConversionRows);
  assert.equal(prepared[0].overallConversions,6);
});
