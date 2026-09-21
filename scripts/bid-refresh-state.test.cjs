const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor.js'),'utf8');

test('failed gap refresh keeps same-date financial references but never carries them to a different date',async()=>{
 const elements=new Map(),previous={anchor:'2026-09-20',accounts:{a:{gap:.9}}};
 const context=vm.createContext({AbortSignal,range:{end:'2026-09-20'},historyMode:false,gapGeneration:0,gapData:previous,
  render(){},B:{normalizeGapPayload:value=>value},api:async()=>{throw Error('timeout');},
  $:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);}});
 vm.runInContext(source.slice(source.indexOf('async function loadGap('),source.indexOf('let gapTitleSource=')),context);
 await context.loadGap();
 assert.equal(context.gapData,previous);
 assert.match(elements.get('#gapStatus').textContent,/保留上次关联结果/);
 assert.equal(elements.get('#gapReload').disabled,false);
 context.range.end='2026-09-21';await context.loadGap();
 assert.equal(context.gapData,null);
 assert.match(elements.get('#gapStatus').textContent,/暂不计算/);
});

test('unchanged shared polls do not recompute the report or redraw strategy cards; history stays open',async()=>{
 let renders=0,receives=0,events=0;
 const elements={sharedReportNotice:{},taskFilter:{options:[]}};
 const context=vm.createContext({document:{body:{dataset:{}},getElementById:id=>elements[id],dispatchEvent(){events++;}},
  location:{hash:'#report'},window:{},CustomEvent:class{},taskRules:[],followSync:true,
  receive:async()=>{receives++;},render:()=>renders++,message(){}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-shared.js'),'utf8'),context);
 const data={userId:'member',canManage:false,strategies:[],strategyRevision:'s1',rules:[{name:'task',price:1}],pricingRevision:'p1',version:'v1',snapshot:{date:'2026-09-20',rows:[{id:'1'}]}};
 await context.bidApplyShared(data);
 const rules=context.taskRules;
 for(let i=0;i<30;i++)await context.bidApplyShared({...data,snapshot:null});
 assert.equal(receives,1);assert.equal(renders,0);assert.equal(events,1);
 assert.equal(context.taskRules,rules,'Keep analysis cache identity when pricing is unchanged');
 context.followSync=false;
 await context.bidApplyShared({...data,version:'v2'});
 assert.equal(receives,1,'Polling must not replace a historical report');
 await context.bidApplyShared({...data,snapshot:null,pricingRevision:'p2',rules:[{name:'task',price:2}]});
 assert.equal(renders,1);assert.equal(context.taskRules[0].price,2);
 await context.bidApplyShared(data,true);assert.equal(receives,2,'Explicit realtime refresh still works');
});

test('out-of-order references cannot overwrite a newer historical query with the same end date',async()=>{
 const elements=new Map([['#historyStart',{value:'2026-09-01'}],['#historyEnd',{value:'2026-09-10'}]]),pending=[];
 const context=vm.createContext({busy:false,raw:[],historyMode:false,today:()=> '2026-09-21',render(){},message(){},
  $:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);},
  fetchHistoryRange:async(start,end)=>({startDate:start,endDate:end,count:1,rows:[{start}]}),
  window:{loadBidHistoricalReferences:()=>new Promise(resolve=>pending.push(resolve))}});
 context.receive=async(rows,label,range)=>{context.raw=rows;context.range=range;context.historyMode=true;};
 vm.runInContext(source.slice(source.indexOf('async function loadHistory('),source.indexOf('async function loadRealtime(')),context);
 await context.loadHistory();elements.get('#historyStart').value='2026-09-05';await context.loadHistory();
 const latest={size:1,totalDates:1,complete:true},stale={size:9,totalDates:9,complete:true};
 pending[1](latest);await Promise.resolve();pending[0](stale);await Promise.resolve();
 assert.equal(context.historyTaskReferences,latest);
 assert.match(elements.get('#historyStatus').textContent,/2026-09-05 至 2026-09-10/);
});
