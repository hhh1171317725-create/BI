const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-gap-sync.js'),'utf8');
function setup(){
 const events={},calls=[],state={revision:'v1',hidden:false,success:true};
 const window={addEventListener:(name,fn)=>events[name]=fn,refreshBidReferences:async revision=>{calls.push(revision);return state.success;}};
 const document={get hidden(){return state.hidden},body:{classList:{contains:()=>true}}};
 vm.runInNewContext(script,{window,document,AbortSignal,api:async()=>({sourceRevision:state.revision})});
 return {window,events,calls,state};
}
test('only changed revisions refresh GAP; failures retry, hidden pages defer',async()=>{
 const {window,calls,state}=setup();
 await window.checkBidGapRevision();await window.checkBidGapRevision();assert.deepEqual(calls,['v1']);
 state.revision='v2';state.success=false;await window.checkBidGapRevision();
 state.success=true;await window.checkBidGapRevision();assert.deepEqual(calls,['v1','v2','v2']);
 state.hidden=true;state.revision='v3';await window.checkBidGapRevision();assert.equal(calls.length,3);
 state.hidden=false;await window.checkBidGapRevision();assert.equal(calls.at(-1),'v3');
});
test('a daily-report storage notification triggers an immediate check and overlapping polls coalesce',async()=>{
 const {window,events,calls}=setup();
 events.storage({key:'unrelated'});assert.equal(calls.length,0);
 events.storage({key:'dhh-report-updated'});
 await window.checkBidGapRevision();await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(calls,['v1']);
});
test('historical GAP requested before invalidation cannot restore the old revision',async()=>{
 const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor.js'),'utf8');
 const code=source.slice(source.indexOf('const historyReferencePromises='),source.indexOf('async function loadPriorPlanConversions('));
 const pending=[],window={};
 vm.runInNewContext(code,{window,AbortSignal,B:{normalizeGapPayload:x=>x},historyMode:false,range:null,raw:[],api:()=>new Promise(resolve=>pending.push(resolve))});
 const old=window.loadBidStrategyReferences('2026-09-15');
 await window.refreshBidReferences('v2');
 const current=window.loadBidStrategyReferences('2026-09-15');
 pending[1]({sourceRevision:'v2'});pending[0]({sourceRevision:'v1'});
 assert.equal((await old).sourceRevision,'v2');assert.equal((await current).sourceRevision,'v2');
});
