'use strict';
const syncRequests=new Map();
let syncStamp='',syncPolling=false;
function syncText(value,bad=false){$('#syncStatus').textContent=value;$('#syncStatus').className=bad?'error':'muted';}
window.addEventListener('message',event=>{
  if(event.source!==window||event.origin!==location.origin||event.data?.source!=='bi-bid-extension')return;
  const pending=syncRequests.get(event.data.requestId);if(!pending)return;
  clearTimeout(pending.timer);syncRequests.delete(event.data.requestId);pending.resolve(event.data);
});
function syncCommand(command){return new Promise((resolve,reject)=>{
  const requestId=crypto.randomUUID();
  const timer=setTimeout(()=>{syncRequests.delete(requestId);reject(Error('未连接投放工具助手 1.9.0，请更新插件后刷新此页'));},25000);
  syncRequests.set(requestId,{resolve,timer});
  window.postMessage({source:'bi-bid-page',requestId,command,minutes:Number($('#syncMinutes').value),
    clientUser:$('#clientUser').value.trim(),mainUserId:$('#mainUserId').value.trim()},location.origin);
});}
async function syncLoad(manual=false){
  if(busy)return;
  const response=await api('/api/bid-monitor/snapshot');
  const snapshot=response.snapshot;
  if(!snapshot?.updatedAt){if(manual)syncText('当前网站账户还没有成功同步的数据');return;}
  if(!manual && (snapshot.updatedAt===syncStamp || (raw.length && !source.startsWith('浏览器同步'))))return;
  receive(snapshot.rows,'浏览器同步 '+new Date(snapshot.updatedAt).toLocaleString('zh-CN'),{start:snapshot.date,end:snapshot.date});
  syncStamp=snapshot.updatedAt;
  if(manual)syncText('已读取 '+new Date(snapshot.updatedAt).toLocaleString('zh-CN')+' 的快照');
}
async function syncRefresh(){
  if(syncPolling||document.hidden||!document.body.classList.contains('ready'))return;
  syncPolling=true;
  try{
    const result=await syncCommand('status');
    if(result.error){
      $('#syncStop').disabled=!result.enabled;
      syncText(result.error+(result.lastSuccess?'；最近成功：'+new Date(result.lastSuccess).toLocaleString('zh-CN'):''),true);
      if(result.lastSuccess && result.lastSuccess!==syncStamp)await syncLoad();
      return;
    }
    $('#syncStop').disabled=!result.enabled;
    const names={waiting:'等待同步',running:'正在同步',ready:'定时同步已开启',paused:'同步已暂停',stopped:'未开启定时同步'};
    syncText((names[result.state]||'未开启定时同步')+(result.lastSuccess?'；最近成功：'+new Date(result.lastSuccess).toLocaleString('zh-CN'):'')+(result.enabled?'；间隔 '+result.minutes+' 分钟':''));
    if(result.lastSuccess!==syncStamp)await syncLoad();
  }catch(error){syncText(error.message,true);}finally{syncPolling=false;}
}
for(const [id,command] of [['syncStart','start'],['syncStop','stop']])$('#'+id).onclick=async()=>{
  $('#'+id).disabled=true;
  try{
    const result=await syncCommand(command);if(result.error)throw Error(result.error);
    syncText(command==='start'?'已启用，正在采集当天数据':'已停止后续同步');
  }catch(error){syncText(error.message,true);}finally{$('#'+id).disabled=false;}
};
$('#syncLoad').onclick=()=>syncLoad(true).catch(error=>syncText(error.message,true));
setInterval(syncRefresh,20000);
setTimeout(()=>void syncRefresh(),1000);
