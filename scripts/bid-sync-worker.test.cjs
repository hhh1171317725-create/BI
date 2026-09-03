const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const crypto=require('node:crypto');
const core=require('../browser-extension/ad-tools-helper/bid-sync-core.js');
const script=fs.readFileSync(require.resolve('../browser-extension/ad-tools-helper/bid-sync.js'),'utf8');
function setup({reject=false,switched=false,saved=null}={}) {
 let listener,alarmListener,storage=saved,uploads=[],alarm=null;
 const rows=[{promotion_id:'123',media_account_id:'999',stat_cost:10,convert_cnt:2,active_register:4,cpa_bid:5}];
 const origin='https://www.huanghaha.fun';
 const chrome={
  storage:{local:{get:async()=>({bidSync:storage}),set:async obj=>{storage=obj.bidSync;}}},
  alarms:{get:async()=>alarm,create:async(name,options)=>{alarm={name,...options};},clear:async()=>{alarm=null;},onAlarm:{addListener:fn=>{alarmListener=fn;}}},
  runtime:{getManifest:()=>({version:'1.9.1'}),onStartup:{addListener(){}},onInstalled:{addListener(){}},onMessage:{addListener:fn=>{listener=fn;}}},
  tabs:{query:async({url})=>url.includes('cl.mobgi')?[{id:2,url:'https://cl.mobgi.com/'}]:[{id:1,url:origin+'/bid-monitor.html'}]},
  scripting:{executeScript:async({func,args})=>{
   if(func.name==='bidChuangliangIdentity')return [{result:{data:{clientUser:'123'}}}];
   if(func.name==='bidWebsiteRequest') {
    if(args[1]==='/identity')return [{result:{data:{userId:switched?'2':'1'}}}];
    uploads.push(args[2]);return [{result:{data:{count:args[2].rows.length,updatedAt:'2026-09-03T04:00:00Z'}}}];
   }
   return [{result:{data:reject?{code:-1}:{code:0,data:{list:rows,total_count:1}}}}];
  }}
 };
 const context={chrome,crypto,URL,Date,Intl,Set,Error,setTimeout,clearTimeout,BidSyncCore:core};
 vm.runInNewContext(script,context);
 const sender={url:origin+'/bid-monitor.html',tab:{id:1},frameId:0};
 return {context,send:(command,customSender=sender)=>new Promise(resolve=>listener({type:'bid-sync',command,minutes:10,clientUser:'123',mainUserId:'456'},customSender,resolve)),
  fire:()=>alarmListener({name:'bi-bid-sync'}),get:()=>({storage,uploads,alarm}),
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
 assert.match(app.get().storage.error,/拒绝/);
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
test('actual page identity reader returns only user ID, not session cookie',()=>{
 const {context}=setup();context.location={origin:'https://cl.mobgi.com'};
 context.document={cookie:'userId=987654; chuangliang_session=private-session'};
 const result=context.bidChuangliangIdentity();assert.equal(result.data.clientUser,'987654');
 assert.ok(!JSON.stringify(result).includes('private-session'));
 context.document.cookie='';assert.match(context.bidChuangliangIdentity().error,/不能据此判断登录失效/);
});
test('actual page query distinguishes mismatch and unreadable identity without querying',async()=>{
 const {context}=setup();context.location={origin:'https://cl.mobgi.com'};
 context.document={cookie:'userId=987654'};
 context.fetch=()=>{throw Error('must not reach network');};
 const result=await context.bidChuangliangPage({},'123','456');assert.match(result.error,/987654.*123/);
 context.document.cookie='';assert.match((await context.bidChuangliangPage({},'123','456')).error,/没有可读取/);
});
