const BID_ALARM='bi-bid-sync';
const BID_KEY='bidSync';
const BID_HOSTS=new Set(['https://www.huanghaha.fun','https://huanghaha.fun']);
let bidRunning=false;
const bidRead=async()=> (await chrome.storage.local.get(BID_KEY))[BID_KEY];
const bidWrite=async value=>chrome.storage.local.set({[BID_KEY]:value});

// Both fetch functions execute in the corresponding site's own page context.
// Session cookies remain in Chrome. Only the numeric userId is read below.
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
async function bidChuangliangIdentity(tab) {
  if (new URL(tab.url).origin!=='https://cl.mobgi.com') throw Error('请打开已登录的创量页面');
  if (!chrome.cookies?.get) throw Error('缺少 Cookie 读取权限，请重新加载插件 1.9.4 并允许新增权限');
  const stores=await chrome.cookies.getAllCookieStores();
  const store=stores.find(item=>item.tabIds.includes(tab.id));
  if (!store) throw Error('无法确定创量标签页的 Cookie 存储，请在同一 Chrome 用户资料中重新打开创量');
  // The API host is authoritative: its cookie can be HttpOnly or host/path scoped.
  // Never enumerate cookie values or read authentication/session cookie names.
  for (const url of ['https://cli1.mobgi.com/Toutiao/Promotion/getList',tab.url]) {
    const cookie=await chrome.cookies.get({url,name:'userId',storeId:store.id});
    if (!cookie) continue;
    if (!/^\d{1,30}$/.test(cookie.value)) throw Error('创量 userId 格式异常，请在创量重新确认登录账户');
    return {clientUser:cookie.value};
  }
  throw Error('创量网页及接口域名均未找到 userId Cookie；未发出数据请求。请提供当前成功请求中的 client-user 和 main-user-id（不要发送 Cookie 或密码），以核对身份来源');
}
async function bidVerifyChuangliangUser(tab,expected) {
  const identity=await bidChuangliangIdentity(tab);
  if (identity.clientUser!==expected) throw Error(`当前创量用户为 ${identity.clientUser}，但 client-user 填写为 ${expected}。请点击“读取当前创量用户”，核对 main-user-id 后重新启用`);
}
async function bidChuangliangPage(body, clientUser, mainUserId, requestId) {
  if (location.origin!=='https://cl.mobgi.com') return {error:'创量页面已关闭或切换'};
  if (!/^\d{14}[0-9a-f]{32}ff$/.test(requestId||'')) return {error:'请求编号格式无效，请更新插件'};
  try {
    const response=await fetch('https://cli1.mobgi.com/Toutiao/Promotion/getList', {
      method:'POST',credentials:'include',headers:{'Content-Type':'application/json;charset=UTF-8',
        'Accept':'application/json, text/plain, */*','client-user':clientUser,'main-user-id':mainUserId,
        'ff-request-id':requestId},
      body:JSON.stringify(body),signal:AbortSignal.timeout(20000)
    });
    if (!response.ok) return {error:'创量接口 HTTP '+response.status, retryable:[502,503,504].includes(response.status)};
    return {data:await response.json()};
  } catch (error) { return {error:error.name==='SyntaxError'?'创量返回的不是 JSON，未保存本轮数据':'创量浏览器请求失败，请确认网络及创量页面可正常查询',retryable:['TypeError','TimeoutError','AbortError'].includes(error.name)}; }
}
async function bidExecute(tabId,func,args) {
  let timer;
  let result;
  try {
    result=await Promise.race([
      chrome.scripting.executeScript({target:{tabId},world:'MAIN',func,args}),
      new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('页面超过 25 秒没有响应，请打开对应标签页，确认未休眠、未出现登录验证后重试')),25000);})
    ]);
  } finally { clearTimeout(timer); }
  const value=result[0]?.result;
  if (!value || value.error) {
    const error=Error(value?.error || '浏览器未返回结果');error.retryable=value?.retryable===true;throw error;
  }
  return value.data;
}
async function bidLive(config) {
  const current=await bidRead();
  if (!current?.enabled || current.generation!==config.generation) throw Error('同步已停止或配置已改变');
}
async function bidProgress(config,progress) {
  const current=await bidRead();
  if (!current?.enabled || current.generation!==config.generation) throw Error('同步已停止或配置已改变');
  await bidWrite({...current,progress,heartbeat:Date.now()});
}
async function bidQueryPage(config,tab,args,deadline) {
  for (let attempt=0;attempt<3;attempt++) {
    await bidLive(config);
    await bidVerifyChuangliangUser(tab,config.clientUser);
    try { return await bidExecute(tab.id,bidChuangliangPage,args); }
    catch (error) {
      const delay=2000*(attempt+1);
      if (!error.retryable || attempt===2 || Date.now()+delay>=deadline) throw error;
      await bidProgress(config,`${error.message}；第 ${args[0].page} 页准备重试 ${attempt+1}/2`);
      await new Promise(resolve=>setTimeout(resolve,delay));
      args[3]=BidSyncCore.requestId();
    }
  }
}
async function bidRun() {
  if (bidRunning) return;
  const config=await bidRead();
  if (!config?.enabled) return;
  bidRunning=true;
  try {
    await bidWrite({...config,state:'running',error:'',startedAt:Date.now(),heartbeat:Date.now(),progress:'正在检查网站登录'});
    const tabs=await chrome.tabs.query({url:config.origin+'/*'});
    const bi=tabs.find(tab=>Boolean(tab.incognito)===Boolean(config.incognito) && ['/bid-monitor','/bid-monitor.html'].includes(new URL(tab.url).pathname));
    if (!bi) throw Error('请保持网站出价监测页面打开，再重新启用同步');
    const identity=await bidExecute(bi.id,bidWebsiteRequest,[config.origin,'/identity',null]);
    if (String(identity.userId)!==config.userId) throw Error('网站账户已切换，请用当前账户重新启用同步');
    const clTabs=(await chrome.tabs.query({url:'https://cl.mobgi.com/*'})).filter(tab=>Boolean(tab.incognito)===Boolean(config.incognito));
    if (!clTabs.length) throw Error('请打开已登录的创量页面，再重新启用同步');
    const cl=clTabs.find(tab=>tab.active)||clTabs[0];
    const state={rows:[],ids:new Set(),total:null}, day=BidSyncCore.date(), deadline=Date.now()+240000;
    let complete=false;
    for (let page=1;page<=2;page++) {
      await bidLive(config);
      if (Date.now()>deadline) throw Error('本轮采集超过 4 分钟，未覆盖旧数据，请缩小数据量或使用导出报表');
      await bidProgress(config,`正在读取消耗前 200 条，第 ${page}/2 页，已读取 ${state.rows.length}${state.total===null?'':' / '+Math.min(200,state.total)} 条`);
      const data=await bidQueryPage(config,cl,[BidSyncCore.body(day,page,config.createdDays??7,state.total),config.clientUser,config.mainUserId,BidSyncCore.requestId()],deadline);
      complete=BidSyncCore.append(state,BidSyncCore.parse(data));
      if (complete) break;
    }
    if (!complete || !state.rows.length) throw Error('没有完整计划数据，保留上次结果');
    if (day!==BidSyncCore.date()) throw Error('采集跨日，未保存混合日期的数据，请重新启用');
    await bidLive(config);
    await bidVerifyChuangliangUser(cl,config.clientUser);
    await bidProgress(config,`已读取 ${state.rows.length} 条，正在保存到网站`);
    const saved=await bidExecute(bi.id,bidWebsiteRequest,[config.origin,'', {expectedUserId:config.userId,date:day,...BidSyncCore.createdRange(day,config.createdDays??7),selection:'spend_desc_top_200',upstreamTotal:state.total,rows:state.rows}]);
    await bidLive(config);
    await bidWrite({...config,state:'ready',lastSuccess:saved.updatedAt,count:saved.count,error:'',progress:`本轮成功同步消耗排名前 ${saved.count} 条计划`,heartbeat:Date.now()});
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
    if (message.command==='detect') {
      const tabs=(await chrome.tabs.query({url:'https://cl.mobgi.com/*'})).filter(tab=>Boolean(tab.incognito)===Boolean(sender.tab.incognito));
      if (!tabs.length) throw Error('请在同一 Chrome 用户资料中打开已登录的创量页面');
      const tab=tabs.find(item=>item.active)||tabs[0];
      return {version:chrome.runtime.getManifest().version,...await bidChuangliangIdentity(tab)};
    } else if (message.command==='stop') {
      // Stopping a shared browser scheduler never exposes the prior user's data.
      if (config) await bidWrite({...config,enabled:false,state:'stopped',generation:crypto.randomUUID()});
      await chrome.alarms.clear(BID_ALARM);
    } else if (message.command==='start') {
      if (bidRunning) throw Error('上一轮正在结束，请稍后再试');
      const minutes=Number(message.minutes);
      if (![5,10,15,30,60].includes(minutes)) throw Error('请选择有效更新间隔');
      const createdDays=Number(message.createdDays??7);
      if (![7,14,30,90].includes(createdDays)) throw Error('请选择有效的计划创建范围');
      if (!/^\d{1,30}$/.test(message.clientUser||'') || !/^\d{1,30}$/.test(message.mainUserId||'')) throw Error('请填写创量 client-user 和 main-user-id');
      config={origin:url.origin,userId,clientUser:message.clientUser,mainUserId:message.mainUserId,minutes,createdDays,incognito:Boolean(sender.tab.incognito),
        enabled:true,state:'waiting',generation:crypto.randomUUID(),error:'',lastSuccess:own?config.lastSuccess:null};
      await bidWrite(config);
      await chrome.alarms.create(BID_ALARM,{delayInMinutes:minutes,periodInMinutes:minutes});
      void bidRun();
    } else if (message.command!=='status') throw Error('未知操作');
    config=await bidRead();
    if (config?.origin!==url.origin || config?.userId!==userId) return {version:chrome.runtime.getManifest().version,state:'stopped',enabled:false};
    if (message.command==='status' && config.enabled && config.state==='running' && !bidRunning) {
      config={...config,enabled:false,state:'paused',error:'上次同步被浏览器中断，请保持页面打开后重新启用'};
      await bidWrite(config);
      await chrome.alarms.clear(BID_ALARM);
    }
    return {version:chrome.runtime.getManifest().version,enabled:config.enabled,state:config.state,
      error:config.error||'',lastSuccess:config.lastSuccess||null,count:config.count,minutes:config.minutes,
      progress:config.progress||'',heartbeat:config.heartbeat||null,createdDays:config.createdDays??7};
  })().then(reply).catch(error=>reply({error:error.message}));
  return true;
});
