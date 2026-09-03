'use strict';
const syncRequests=new Map();
let syncStamp='',syncPolling=false,syncAction=false,syncRevision=0;
function syncText(value,bad=false){$('#syncStatus').textContent=value;$('#syncStatus').className=bad?'error':'muted';}
window.addEventListener('message',event=>{
  if(event.source!==window||event.origin!==location.origin||event.data?.source!=='bi-bid-extension')return;
  const pending=syncRequests.get(event.data.requestId);if(!pending)return;
  clearTimeout(pending.timer);syncRequests.delete(event.data.requestId);pending.resolve(event.data);
});
function syncCommand(command){return new Promise((resolve,reject)=>{
  const requestId=crypto.randomUUID();
  const timer=setTimeout(()=>{syncRequests.delete(requestId);reject(Error('插件未及时返回状态，请更新到 1.9.1 并刷新页面；确认网站登录及网络正常'));},30000);
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
function syncShow(result){
  $('#syncStop').disabled=syncAction||!result.enabled;
  $('#syncStart').disabled=syncAction||result.state==='running'||result.state==='waiting';
  const names={waiting:'准备开始同步',running:'正在同步',ready:'定时同步已开启',paused:'同步已暂停',stopped:'未开启定时同步'};
  const last=result.lastSuccess?'；最近成功：'+new Date(result.lastSuccess).toLocaleString('zh-CN'):'';
  syncText(result.error?result.error+last:
    (names[result.state]||'未开启定时同步')+(result.progress?'；'+result.progress:'')+last+
    (result.enabled?'；间隔 '+result.minutes+' 分钟':''),Boolean(result.error));
}
async function syncRefresh(){
  if(syncPolling||syncAction||document.hidden||!document.body.classList.contains('ready'))return;
  syncPolling=true;
  const revision=syncRevision;
  try{
    const result=await syncCommand('status');
    if(revision!==syncRevision)return;
    syncShow(result);
    if(result.lastSuccess && result.lastSuccess!==syncStamp)await syncLoad();
  }catch(error){if(revision===syncRevision){syncText(error.message,true);$('#syncStart').disabled=false;$('#syncStop').disabled=false;}}finally{syncPolling=false;}
}
for(const [id,command] of [['syncStart','start'],['syncStop','stop']])$('#'+id).onclick=async()=>{
  syncAction=true;syncRevision++;
  $('#syncStart').disabled=$('#syncStop').disabled=true;
  syncText(command==='start'?'正在联系插件，检查网站登录…':'正在停止同步…');
  try{
    const result=await syncCommand(command);
    syncAction=false;syncShow(result);
    if(result.lastSuccess && result.lastSuccess!==syncStamp)await syncLoad();
  }catch(error){syncText(error.message,true);$('#syncStart').disabled=false;$('#syncStop').disabled=false;}
  finally{syncAction=false;setTimeout(()=>void syncRefresh(),500);}
};
$('#syncLoad').onclick=()=>syncLoad(true).catch(error=>syncText(error.message,true));
$('#syncDetect').onclick=async()=>{
  syncAction=true;syncRevision++;$('#syncDetect').disabled=true;
  syncText('正在读取已打开的创量页面用户…');
  try{
    const result=await syncCommand('detect');if(result.error)throw Error(result.error);
    if(!/^\d{1,30}$/.test(result.clientUser||''))throw Error('插件未返回用户 ID，请更新到 1.9.1');
    $('#clientUser').value=result.clientUser;
    $('#clientUser').closest('details').open=true;
    syncText('已填写当前创量用户 '+result.clientUser+'；请核对同一账户的 main-user-id，然后启用同步');
  }catch(error){syncText(error.message,true);}
  finally{syncAction=false;$('#syncDetect').disabled=false;}
};
setInterval(syncRefresh,3000);
setTimeout(()=>void syncRefresh(),1000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)void syncRefresh();});
