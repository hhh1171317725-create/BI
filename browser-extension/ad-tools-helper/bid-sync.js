const BID_ALARM='bi-bid-sync';
const BID_KEY='bidSync';
const BID_HOSTS=new Set(['https://www.huanghaha.fun','https://huanghaha.fun']);
let bidRunning=false;
const bidRead=async()=> (await chrome.storage.local.get(BID_KEY))[BID_KEY];
const bidWrite=async value=>chrome.storage.local.set({[BID_KEY]:value});

// Both fetch functions execute in the corresponding site's own page context.
// Cookies remain in Chrome and are never included in messages or uploads.
async function bidWebsiteRequest(origin, path, payload) {
  if (location.origin!==origin || !['/bid-monitor','/bid-monitor.html'].includes(location.pathname))
    return {error:'网站页面已切换'};
  try {
    const response=await fetch('/api/bid-monitor/snapshot'+path, {
      method:payload?'POST':'GET', credentials:'same-origin', cache:'no-store',
      headers:payload?{'Content-Type':'application/json'}:{},
      body:payload?JSON.stringify(payload):undefined, signal:AbortSignal.timeout(20000)
    });
    if (!response.ok) return {error:response.status===401||response.status===403?'网站登录或工具权限已失效，请重新登录并启用同步':'网站保存接口失败 HTTP '+response.status};
    return {data:await response.json()};
  } catch { return {error:'网站接口无法访问，请检查部署和网络'}; }
}
async function bidChuangliangPage(body, clientUser, mainUserId) {
  if (location.origin!=='https://cl.mobgi.com') return {error:'创量页面已关闭或切换'};
  const currentUser=document.cookie.split(';').map(v=>v.trim()).find(v=>v.startsWith('userId='))?.slice(7);
  if (currentUser!==clientUser) return {error:'创量用户与 client-user 不一致或登录已失效，请核对后重新启用'};
  try {
    const response=await fetch('https://cli1.mobgi.com/Toutiao/Promotion/getList', {
      method:'POST',credentials:'include',headers:{'Content-Type':'application/json;charset=UTF-8',
        'Accept':'application/json, text/plain, */*','client-user':clientUser,'main-user-id':mainUserId,
        'ff-request-id':new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(new Date()).replace(/\D/g,'')+crypto.randomUUID().replaceAll('-','')},
      body:JSON.stringify(body),signal:AbortSignal.timeout(20000)
    });
    if (!response.ok) return {error:'创量接口 HTTP '+response.status+'，已暂停，请在创量页面检查登录和权限'};
    return {data:await response.json()};
  } catch { return {error:'创量浏览器请求失败，可能是网络、跨域限制或登录验证，请先确认创量页面可正常查询'}; }
}
async function bidExecute(tabId,func,args) {
  const result=await chrome.scripting.executeScript({target:{tabId},world:'MAIN',func,args});
  const value=result[0]?.result;
  if (!value || value.error) throw Error(value?.error || '浏览器未返回结果');
  return value.data;
}
async function bidLive(config) {
  const current=await bidRead();
  if (!current?.enabled || current.generation!==config.generation) throw Error('同步已停止或配置已改变');
}
async function bidRun() {
  if (bidRunning) return;
  const config=await bidRead();
  if (!config?.enabled) return;
  bidRunning=true;
  try {
    await bidWrite({...config,state:'running',error:'',startedAt:Date.now()});
    const tabs=await chrome.tabs.query({url:config.origin+'/*'});
    const bi=tabs.find(tab=>['/bid-monitor','/bid-monitor.html'].includes(new URL(tab.url).pathname));
    if (!bi) throw Error('请保持网站出价监测页面打开，再重新启用同步');
    const identity=await bidExecute(bi.id,bidWebsiteRequest,[config.origin,'/identity',null]);
    if (String(identity.userId)!==config.userId) throw Error('网站账户已切换，请用当前账户重新启用同步');
    const clTabs=await chrome.tabs.query({url:'https://cl.mobgi.com/*'});
    if (!clTabs.length) throw Error('请打开已登录的创量页面，再重新启用同步');
    const cl=clTabs.find(tab=>tab.active)||clTabs[0];
    const state={rows:[],ids:new Set(),total:null}, day=BidSyncCore.date(), deadline=Date.now()+240000;
    let complete=false;
    for (let page=1;page<=200;page++) {
      await bidLive(config);
      if (Date.now()>deadline) throw Error('本轮采集超过 4 分钟，未覆盖旧数据，请缩小数据量或使用导出报表');
      const data=await bidExecute(cl.id,bidChuangliangPage,[BidSyncCore.body(day,page),config.clientUser,config.mainUserId]);
      complete=BidSyncCore.append(state,BidSyncCore.parse(data));
      if (complete) break;
    }
    if (!complete || !state.rows.length) throw Error('没有完整计划数据，保留上次结果');
    if (day!==BidSyncCore.date()) throw Error('采集跨日，未保存混合日期的数据，请重新启用');
    await bidLive(config);
    const saved=await bidExecute(bi.id,bidWebsiteRequest,[config.origin,'', {expectedUserId:config.userId,date:day,rows:state.rows}]);
    await bidLive(config);
    await bidWrite({...config,state:'ready',lastSuccess:saved.updatedAt,count:saved.count,error:''});
  } catch (error) {
    const current=await bidRead();
    if (current?.generation===config.generation && current.enabled) {
      await bidWrite({...current,enabled:false,state:'paused',error:String(error.message||'同步失败').slice(0,300)});
      await chrome.alarms.clear(BID_ALARM);
    }
  } finally { bidRunning=false; }
}
async function bidRestore() {
  const config=await bidRead();
  if (config?.enabled && !await chrome.alarms.get(BID_ALARM))
    await chrome.alarms.create(BID_ALARM,{delayInMinutes:1,periodInMinutes:config.minutes});
}
chrome.alarms.onAlarm.addListener(alarm=>{if(alarm.name===BID_ALARM) void bidRun();});
chrome.runtime.onStartup.addListener(()=>void bidRestore());
chrome.runtime.onInstalled.addListener(()=>void bidRestore());
void bidRestore();

chrome.runtime.onMessage.addListener((message,sender,reply)=>{
  if (message?.type!=='bid-sync') return;
  (async()=>{
    const url=new URL(sender.url||'about:blank');
    if (!sender.tab?.id || sender.frameId!==0 || !BID_HOSTS.has(url.origin)
        || !['/bid-monitor','/bid-monitor.html'].includes(url.pathname)) throw Error('请求来源未授权');
    const identity=await bidExecute(sender.tab.id,bidWebsiteRequest,[url.origin,'/identity',null]);
    const userId=String(identity.userId);
    if (!/^\d+$/.test(userId)) throw Error('网站未返回用户身份，请更新服务器');
    let config=await bidRead();
    const own=config?.origin===url.origin && config.userId===userId;
    if (message.command==='stop') {
      // Stopping a shared browser scheduler never exposes the prior user's data.
      if (config) await bidWrite({...config,enabled:false,state:'stopped',generation:crypto.randomUUID()});
      await chrome.alarms.clear(BID_ALARM);
    } else if (message.command==='start') {
      if (bidRunning) throw Error('上一轮正在结束，请稍后再试');
      const minutes=Number(message.minutes);
      if (![5,10,15,30,60].includes(minutes)) throw Error('请选择有效更新间隔');
      if (!/^\d{1,30}$/.test(message.clientUser||'') || !/^\d{1,30}$/.test(message.mainUserId||'')) throw Error('请填写创量 client-user 和 main-user-id');
      config={origin:url.origin,userId,clientUser:message.clientUser,mainUserId:message.mainUserId,minutes,
        enabled:true,state:'waiting',generation:crypto.randomUUID(),error:'',lastSuccess:own?config.lastSuccess:null};
      await bidWrite(config);
      await chrome.alarms.create(BID_ALARM,{delayInMinutes:minutes,periodInMinutes:minutes});
      void bidRun();
    } else if (message.command!=='status') throw Error('未知操作');
    config=await bidRead();
    if (config?.origin!==url.origin || config?.userId!==userId) return {version:chrome.runtime.getManifest().version,state:'stopped',enabled:false};
    return {version:chrome.runtime.getManifest().version,enabled:config.enabled,state:config.state,
      error:config.error||'',lastSuccess:config.lastSuccess||null,count:config.count,minutes:config.minutes};
  })().then(reply).catch(error=>reply({error:error.message}));
  return true;
});
