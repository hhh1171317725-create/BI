const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-server-sync.js'),'utf8');
const loader=source.slice(source.indexOf('let syncLoadPending='),source.indexOf('window.loadBidRealtime='));
function setup(){
  const requests=[],received=[];
  const context=vm.createContext({AbortSignal,bidCanManage:true,busy:false,followSync:true,raw:[],syncRevision:0,syncStamp:'',
    syncText(){},syncIdentity(){},api:()=>new Promise((resolve,reject)=>requests.push({resolve,reject})),
    receive:async rows=>{received.push(rows);context.raw=rows;context.followSync=true;}});
  vm.runInContext(loader,context);
  const snapshot={userId:'1',snapshot:{updatedAt:'2026-09-22T01:00:00Z',date:'2026-09-22',rows:[{promotion_id:'1'}]}};
  return {context,requests,received,snapshot};
}
test('historical view including empty results does not download realtime snapshots on polls',async()=>{
  const {context,requests}=setup();context.followSync=false;
  for(let i=0;i<30;i++)assert.equal(await context.syncLoad(),false);
  assert.equal(requests.length,0);
});
test('overlapping polling and manual refresh fetch and apply once, and manual refresh is honored',async()=>{
  const {context,requests,received,snapshot}=setup();context.syncStamp=snapshot.snapshot.updatedAt;
  const poll=context.syncLoad(),manual=context.syncLoad(true);
  assert.equal(requests.length,1);requests[0].resolve(snapshot);
  assert.equal(await poll,true);assert.equal(await manual,true);assert.equal(received.length,1);
  context.followSync=false;
  const explicit=context.syncLoad(true);assert.equal(requests.length,2);
  requests[1].resolve(snapshot);assert.equal(await explicit,true);
});
test('late snapshot cannot overwrite a newer report or configuration',async()=>{
  for(const change of [context=>{context.raw=[{history:true}];context.followSync=false;},context=>context.syncRevision++,context=>context.busy=true]){
    const {context,requests,received,snapshot}=setup();const pending=context.syncLoad(true);
    change(context);requests[0].resolve(snapshot);
    assert.equal(await pending,false);assert.equal(received.length,0);
  }
});
test('failed shared request releases its slot so a manual retry can succeed',async()=>{
  const {context,requests,received,snapshot}=setup();const a=context.syncLoad(),b=context.syncLoad(true);
  const checks=[assert.rejects(a,/offline/),assert.rejects(b,/offline/)];requests[0].reject(Error('offline'));await Promise.all(checks);
  const retry=context.syncLoad(true);assert.equal(requests.length,2);requests[1].resolve(snapshot);
  assert.equal(await retry,true);assert.equal(received.length,1);
});
