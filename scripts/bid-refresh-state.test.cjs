const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor.js'),'utf8');

test('failed gap refresh keeps same-date financial references but never carries them to a different date',async()=>{
 const elements=new Map(),previous={anchor:'2026-09-20',accounts:{a:{gap:.9}}};
 const context=vm.createContext({window:{},AbortSignal,range:{end:'2026-09-20'},historyMode:false,gapGeneration:0,gapData:previous,
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
  location:{hash:'#report'},window:{},CustomEvent:class{},taskRules:[],followSync:true,reportLoadGeneration:0,busy:false,raw:[],
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

test('switching to history retires the superseded gap loading indicator',async()=>{
 const active=new Set(),elements=new Map();let resolve;
 const context=vm.createContext({AbortSignal,range:{end:'2026-09-20'},historyMode:false,gapGeneration:0,gapData:null,
  window:{BidDataAlerts:{begin:channel=>active.add(channel),cancel:channel=>active.delete(channel)}},render(){},
  B:{normalizeGapPayload:value=>value},api:()=>new Promise(done=>{resolve=done;}),
  $:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);}});
 vm.runInContext(source.slice(source.indexOf('async function loadGap('),source.indexOf('let gapTitleSource=')),context);
 const pending=context.loadGap();assert.equal(active.has('gap'),true);
 context.historyMode=true;context.gapGeneration++;resolve({anchor:'2026-09-20',accounts:{}});await pending;
 assert.equal(active.has('gap'),false,'A discarded realtime response must not leave historical reports permanently updating');
 assert.equal(context.gapData,null);
});

test('out-of-order references cannot overwrite a newer historical query with the same end date',async()=>{
 const elements=new Map([['#historyStart',{value:'2026-09-01'}],['#historyEnd',{value:'2026-09-10'}]]),pending=[];
 const context=vm.createContext({busy:false,raw:[],historyMode:false,reportLoadGeneration:0,today:()=> '2026-09-21',render(){},message(){},
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

function reportLoadContext(){
 const elements=new Map([['#historyStart',{value:'2026-09-01'}],['#historyEnd',{value:'2026-09-10'}]]),messages=[],failures=[];
 const context=vm.createContext({busy:false,raw:[],historyMode:false,followSync:true,reportLoadGeneration:0,realtimeLoadPending:null,today:()=> '2026-09-21',render(){},
  message:text=>messages.push(text),window:{BidDataAlerts:{begin(){},success(){},fail:(...args)=>failures.push(args)}},
  $:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);}});
 vm.runInContext(source.slice(source.indexOf('async function loadHistory('),source.indexOf("$('#historyLoad').onclick=")),context);
 return {context,elements,messages,failures};
}

test('repeated realtime actions share a single pending load and finish the same action',async()=>{
 const {context,elements}=reportLoadContext();let resolve,calls=0;
 context.window.loadBidRealtime=()=>{calls++;return new Promise(done=>resolve=done);};
 const first=context.loadRealtime(),second=context.loadRealtime();
 assert.equal(calls,1);assert.equal(elements.get('#historyLoad').disabled,true);
 resolve(true);assert.equal(await first,true);assert.equal(await second,true);
 assert.equal(elements.get('#historyLoad').disabled,false);
 assert.match(elements.get('#historyStatus').textContent,/未选择日期/);
});

test('a realtime load waiting for GAP cannot replace a newer history status or enable its button',async()=>{
 const {context,elements,messages}=reportLoadContext();let gapDone,historyDone;
 context.window.loadBidRealtime=()=>{context.raw=[{id:'realtime'}];return new Promise(done=>gapDone=done);};
 context.fetchHistoryRange=()=>new Promise(done=>historyDone=done);
 context.receive=async(rows,label,range,live,historical)=>{context.raw=rows;context.range=range;context.historyMode=historical;context.followSync=live;};
 context.window.loadBidHistoricalReferences=async()=>({size:1,totalDates:1,complete:true});
 const live=context.loadRealtime(),history=context.loadHistory();
 assert.equal(context.busy,true);gapDone(true);assert.equal(await live,false);
 assert.equal(elements.get('#historyLoad').disabled,true,'An older finally must not unlock the newer query');
 assert.match(elements.get('#historyStatus').textContent,/正在读取已归档数据/);
 assert.equal(messages.includes('已切换到当日最新实时数据'),false);
 historyDone({startDate:'2026-09-01',endDate:'2026-09-10',count:1,rows:[{id:'history'}]});await history;
 assert.equal(context.raw[0].id,'history');assert.equal(elements.get('#historyLoad').disabled,false);
 assert.match(elements.get('#historyStatus').textContent,/2026-09-01 至 2026-09-10/);
});

test('a newer direct query owns button availability after superseding realtime',async()=>{
 const {context,elements,failures}=reportLoadContext();let reject;
 context.window.loadBidRealtime=()=>new Promise((resolve,fail)=>reject=fail);
 vm.runInContext(source.slice(source.indexOf('function setBusy('),source.indexOf('function dates(')),context);
 const live=context.loadRealtime();context.reportLoadGeneration++;context.setBusy(true);
 reject(Error('old snapshot failed'));assert.equal(await live,false);
 assert.equal(elements.get('#historyLoad').disabled,true);assert.equal(failures.length,0);
 context.setBusy(false);assert.equal(elements.get('#historyLoad').disabled,false);
});

function sharedContext(){
 const elements={sharedReportNotice:{},taskFilter:{options:[]}},requests=[],events=[],failures=[],snapshotLoads=[];let renders=0;
 const context=vm.createContext({document:{hidden:false,body:{dataset:{}},getElementById:id=>elements[id],dispatchEvent:event=>events.push(event)},location:{hash:'#report'},
  window:{BidDataAlerts:{begin(){},success(){},syncStatus(){},snapshotLoaded:stamp=>snapshotLoads.push(stamp),fail:(...args)=>failures.push(args)}},CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},AbortSignal,
  raw:[{id:'previous'}],reportLoadGeneration:0,busy:false,followSync:true,historyMode:false,taskRules:[],render:()=>renders++,message(){},
  api:path=>new Promise((resolve,reject)=>requests.push({path,resolve,reject}))});
 context.receive=async(rows,label,range,live)=>{context.raw=rows;context.followSync=live;context.historyMode=false;};
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-shared.js'),'utf8'),context);
 const data={userId:'member',canManage:false,strategies:[],strategyRevision:'s1',rules:[{name:'new-price',price:2}],pricingRevision:'p1',version:'v1',snapshot:{date:'2026-09-21',updatedAt:'2026-09-21T01:00:00Z',rows:[{id:'old-realtime'}]}};
 return {context,requests,data,events,failures,snapshotLoads,renders:()=>renders};
}

test('a delayed forced shared response keeps a newer history query and still applies shared metadata',async()=>{
 const {context,requests,data,events,renders}=sharedContext();
 const pending=context.bidRefreshShared(true);
 // Run the actual history loader while the forced shared response is still pending.
 const fields=new Map([['#historyStart',{value:'2026-09-01'}],['#historyEnd',{value:'2026-09-10'}]]);
 Object.assign(context,{today:()=> '2026-09-21',$:id=>{if(!fields.has(id))fields.set(id,{});return fields.get(id);},fetchHistoryRange:async()=>({startDate:'2026-09-01',endDate:'2026-09-10',count:1,rows:[{id:'history'}]})});
 context.receive=async(rows,label,range,live,historical)=>{context.raw=rows;context.range=range;context.followSync=live;context.historyMode=historical;};
 context.window.loadBidHistoricalReferences=async()=>({size:1,totalDates:1,complete:true});
 vm.runInContext(source.slice(source.indexOf('async function loadHistory('),source.indexOf('async function loadRealtime(')),context);
 await context.loadHistory();requests[0].resolve(data);assert.equal(await pending,false);
 assert.equal(context.raw[0].id,'history');assert.equal(context.historyMode,true);assert.equal(context.followSync,false);
 assert.equal(context.taskRules[0].name,'new-price');assert.equal(events.length,1);assert.ok(renders()>0);
 assert.match(fields.get('#historyStatus').textContent,/2026-09-01 至 2026-09-10/);
});

test('a forced shared refresh waits for polling, but never queues a snapshot for a superseded action',async()=>{
 const {context,requests,data}=sharedContext();
 const poll=context.bidRefreshShared();context.reportLoadGeneration++;
 const force=context.bidRefreshShared(true);context.reportLoadGeneration++;context.followSync=false;
 requests[0].resolve(data);await poll;assert.equal(await force,false);
 assert.equal(requests.length,1,'The retired realtime action must not start a later forced read');
 assert.equal(context.raw[0].id,'previous');assert.equal(context.followSync,false);
});

test('shared snapshot completion after GAP never marks a newer history report as realtime',async()=>{
 const {context,requests,data,snapshotLoads}=sharedContext();let gapDone;
 context.receive=rows=>{context.raw=rows;return new Promise(done=>gapDone=done);};
 const pending=context.bidRefreshShared(true);requests[0].resolve(data);
 await new Promise(resolve=>setImmediate(resolve));assert.equal(context.raw[0].id,'old-realtime');
 const fields=new Map([['#historyStart',{value:'2026-09-01'}],['#historyEnd',{value:'2026-09-10'}]]);
 Object.assign(context,{today:()=> '2026-09-21',$:id=>{if(!fields.has(id))fields.set(id,{});return fields.get(id);},fetchHistoryRange:async()=>({startDate:'2026-09-01',endDate:'2026-09-10',count:1,rows:[{id:'history'}]})});
 context.receive=async(rows,label,range,live,historical)=>{context.raw=rows;context.range=range;context.followSync=live;context.historyMode=historical;};
 context.window.loadBidHistoricalReferences=async()=>({size:1,totalDates:1,complete:true});
 vm.runInContext(source.slice(source.indexOf('async function loadHistory('),source.indexOf('async function loadRealtime(')),context);
 await context.loadHistory();gapDone();assert.equal(await pending,false);
 assert.equal(context.raw[0].id,'history');assert.deepEqual(snapshotLoads,[]);
 assert.match(fields.get('#historyStatus').textContent,/2026-09-01 至 2026-09-10/);
});

test('shared failures retain failure reporting and a manual refresh reads after an existing poll',async()=>{
 const {context,requests,data,failures}=sharedContext();
 const poll=context.bidRefreshShared();const force=context.bidRefreshShared(true);
 requests[0].reject(Error('temporary network failure'));assert.equal(await poll,false);
 await new Promise(resolve=>setImmediate(resolve));assert.equal(requests.length,2);assert.equal(failures[0][0],'shared');
 requests[1].resolve(data);assert.equal(await force,true);assert.equal(context.raw[0].id,'old-realtime');
});
