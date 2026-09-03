const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const crypto=require('node:crypto');
const core=require('../browser-extension/ad-tools-helper/bid-sync-core.js');
const script=fs.readFileSync(require.resolve('../browser-extension/ad-tools-helper/bid-sync.js'),'utf8');
function setup({reject=false,switched=false,saved=null}={}) {
 let listener,alarmListener,storage=saved,uploads=[],alarm=null;
 const cookieReads=[];
 const rows=[{promotion_id:'123',media_account_id:'999',stat_cost:10,convert_cnt:2,active_register:4,cpa_bid:5}];
 const origin='https://www.huanghaha.fun';
 const chrome={
  cookies:{getAllCookieStores:async()=>[{id:'profile-store',tabIds:[1,2]}],get:async details=>{
   cookieReads.push(details);return {name:'userId',value:'123',httpOnly:true};
  }},
  storage:{local:{get:async()=>({bidSync:storage}),set:async obj=>{storage=obj.bidSync;}}},
  alarms:{get:async()=>alarm,create:async(name,options)=>{alarm={name,...options};},clear:async()=>{alarm=null;},onAlarm:{addListener:fn=>{alarmListener=fn;}}},
  runtime:{getManifest:()=>({version:'1.9.3'}),onStartup:{addListener(){}},onInstalled:{addListener(){}},onMessage:{addListener:fn=>{listener=fn;}}},
  tabs:{query:async({url})=>url.includes('cl.mobgi')?[{id:2,url:'https://cl.mobgi.com/'}]:[{id:1,url:origin+'/bid-monitor.html'}]},
  scripting:{executeScript:async({func,args})=>{
   if(func.name==='bidWebsiteRequest') {
    if(args[1]==='/identity')return [{result:{data:{userId:switched?'2':'1'}}}];
    uploads.push(args[2]);return [{result:{data:{count:args[2].rows.length,updatedAt:'2026-09-03T04:00:00Z'}}}];
   }
   assert.match(args[3],/^\d{14}[0-9a-f]{32}ff$/);
   assert.deepEqual(JSON.parse(args[0].conditions).deep_bid_type,[]);
   return [{result:{data:reject?{code:-1}:{code:0,data:{list:rows,page_info:{total_count:1}}}}}];
  }}
 };
 const context={chrome,crypto,URL,Date,Intl,Set,Error,setTimeout,clearTimeout,BidSyncCore:core};
 vm.runInNewContext(script,context);
 const sender={url:origin+'/bid-monitor.html',tab:{id:1},frameId:0};
 return {context,send:(command,customSender=sender)=>new Promise(resolve=>listener({type:'bid-sync',command,minutes:10,clientUser:'123',mainUserId:'456'},customSender,resolve)),
  fire:()=>alarmListener({name:'bi-bid-sync'}),get:()=>({storage,uploads,alarm,cookieReads}),
  settle:async()=>{for(let i=0;i<100;i++){await new Promise(r=>setImmediate(r));if(storage?.state==='ready'||storage?.state==='paused')return;}throw Error('Worker did not settle');}};
}
test('start uploads complete snapshot and alarm updates again without duplicates',async()=>{
 const app=setup();assert.ok(!(await app.send('start')).error);await app.settle();
 assert.equal(app.get().uploads.length,1);assert.equal(app.get().uploads[0].expectedUserId,'1');
 assert.equal(app.get().alarm.periodInMinutes,10);assert.equal(app.get().storage.enabled,true);
 app.fire();await app.settle();assert.equal(app.get().uploads.length,2);
 await app.send('stop');app.fire();await new Promise(r=>setImmediate(r));assert.equal(app.get().uploads.length,2);assert.equal(app.get().alarm,null);
});
test('upstream access rejection pauses without uploading or scheduling retries',async()=>{
 const app=setup({reject:true});await app.send('start');await app.settle();
 assert.equal(app.get().uploads.length,0);assert.equal(app.get().storage.enabled,false);assert.equal(app.get().alarm,null);
 assert.match(app.get().storage.error,/code=-1/);
});
test('restored scheduler checks bound website account before collecting',async()=>{
 const app=setup({switched:true,saved:{origin:'https://www.huanghaha.fun',userId:'1',enabled:true,minutes:10,generation:'old'}});
 await new Promise(r=>setImmediate(r));assert.equal(app.get().alarm.periodInMinutes,10);
 app.fire();await app.settle();assert.equal(app.get().uploads.length,0);assert.match(app.get().storage.error,/账户已切换/);
});
test('rejects messages from other websites and subframes',async()=>{
 const app=setup();const reply=await app.send('start',{url:'https://evil.example/bid-monitor.html',tab:{id:3},frameId:0});
 assert.match(reply.error,/未授权/);assert.equal(app.get().storage,null);
 const subframe=await app.send('start',{url:'https://www.huanghaha.fun/bid-monitor.html',tab:{id:1},frameId:1});assert.match(subframe.error,/未授权/);
});
test('detect returns only current user and does not enable or upload',async()=>{
 const app=setup();const result=await app.send('detect');assert.equal(result.clientUser,'123');
 assert.equal(app.get().storage,null);assert.equal(app.get().uploads.length,0);
});
test('interrupted running state is paused instead of appearing to run forever',async()=>{
 const app=setup({saved:{origin:'https://www.huanghaha.fun',userId:'1',enabled:true,state:'running',minutes:10,generation:'old'}});
 const result=await app.send('status');assert.equal(result.enabled,false);assert.match(result.error,/中断/);
});
test('HttpOnly API user ID is readable without document.cookie and only userId is requested',async()=>{
 const app=setup();const result=await app.context.bidChuangliangIdentity({id:2,url:'https://cl.mobgi.com/'});
 assert.equal(result.clientUser,'123');assert.deepEqual(Object.keys(result),['clientUser']);
 const [request]=app.get().cookieReads;
 assert.equal(request.name,'userId');assert.equal(request.storeId,'profile-store');
 assert.equal(request.url,'https://cli1.mobgi.com/Toutiao/Promotion/getList');
});
test('missing API cookie falls back to selected page path, still restricted to userId',async()=>{
 const {context}=setup();const reads=[];
 context.chrome.cookies.get=async details=>{reads.push(details);return details.url.includes('cli1.')?null:{value:'321'};};
 const result=await context.bidChuangliangIdentity({id:2,url:'https://cl.mobgi.com/plans'});
 assert.equal(result.clientUser,'321');assert.equal(reads[1].url,'https://cl.mobgi.com/plans');assert.ok(reads.every(r=>r.name==='userId'));
});
test('missing cookie is explicit and does not query upstream',async()=>{
 const app=setup();app.context.chrome.cookies.get=async()=>null;
 await app.send('start');await app.settle();assert.equal(app.get().uploads.length,0);assert.match(app.get().storage.error,/均未找到/);
});
test('account mismatch still blocks the upload',async()=>{
 const app=setup();app.context.chrome.cookies.get=async()=>({value:'987654'});
 await app.send('start');await app.settle();assert.equal(app.get().uploads.length,0);assert.match(app.get().storage.error,/987654.*123/);
});
test('Cookie store comes from selected tab, never the default or other profile',async()=>{
 const app=setup();app.context.chrome.cookies.getAllCookieStores=async()=>[{id:'regular',tabIds:[1]},{id:'incognito',tabIds:[2]}];
 await app.context.bidChuangliangIdentity({id:2,url:'https://cl.mobgi.com/'});
 assert.equal(app.get().cookieReads[0].storeId,'incognito');
 await assert.rejects(()=>app.context.bidChuangliangIdentity({id:9,url:'https://cl.mobgi.com/'}),/Cookie 存储/);
});
test('query no longer incorrectly demands a JavaScript readable cookie',async()=>{
 const {context}=setup();context.location={origin:'https://cl.mobgi.com'};
 context.document={get cookie(){throw Error('document.cookie must not be read');}};
 context.AbortSignal=AbortSignal;
 let sent;
 context.fetch=async(url,options)=>{sent={url,options};return {ok:true,json:async()=>({code:0})};};
 const result=await context.bidChuangliangPage({},'123','456',core.requestId());assert.equal(result.data.code,0);
 assert.equal(sent.options.credentials,'include');assert.equal(sent.options.headers.Cookie,undefined);
 assert.match(sent.options.headers['ff-request-id'],/^\d{14}[0-9a-f]{32}ff$/);
});
test('temporary gateway failure is retried with a new request ID',async()=>{
 const config={enabled:true,generation:'test',clientUser:'123'};
 const app=setup({saved:config});let calls=0;const ids=[];
 app.context.setTimeout=fn=>setTimeout(fn,0);
 app.context.chrome.scripting.executeScript=async({args})=>{
  ids.push(args[3]);calls++;return [{result:calls===1?{error:'HTTP 503',retryable:true}:{data:{code:0}}}];
 };
 const result=await app.context.bidQueryPage(config,{id:2,url:'https://cl.mobgi.com/'},[core.body('2026-09-03',1),'123','456',core.requestId()],Date.now()+10000);
 assert.equal(result.code,0);assert.equal(calls,2);assert.notEqual(ids[0],ids[1]);
});
test('HTTP authentication failure is never retried',async()=>{
 const config={enabled:true,generation:'test',clientUser:'123'};
 const app=setup({saved:config});let calls=0;
 app.context.chrome.scripting.executeScript=async()=>{calls++;return [{result:{error:'HTTP 401',retryable:false}}];};
 await assert.rejects(()=>app.context.bidQueryPage(config,{id:2,url:'https://cl.mobgi.com/'},[core.body('2026-09-03',1),'123','456',core.requestId()],Date.now()+10000),/401/);
 assert.equal(calls,1);
});
