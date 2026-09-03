'use strict';
let syncStamp='',syncOwner='',syncPolling=false,syncAction=false,syncRevision=0,syncHydrated=false,syncDirty=false,syncState={};
function syncText(value,bad=false){$('#syncStatus').textContent=value;$('#syncStatus').className=bad?'error':'muted';}
for(const id of ['cookie','clientUser','mainUserId','syncMinutes','syncCreatedDays'])$('#'+id).addEventListener('input',()=>{syncDirty=true;});
function syncIdentity(id){
  if(!id)throw Error('服务器未返回当前网站账户，请重新登录');
  if(syncOwner&&syncOwner!==String(id)){location.reload();throw Error('网站账户已切换，正在刷新');}
  syncOwner=String(id);
}
async function syncCommand(command){
  const path='/api/bid-monitor/server-sync';
  if(command==='status')return api(path,{signal:AbortSignal.timeout(15000)});
  if(!syncOwner)throw Error('请等待服务器状态读取完成');
  const input={expectedUserId:syncOwner};
  if(command==='start')Object.assign(input,{minutes:Number($('#syncMinutes').value),createdDays:Number($('#syncCreatedDays').value),
    cookie:$('#cookie').value.trim(),clientUser:$('#clientUser').value.trim(),mainUserId:$('#mainUserId').value.trim()});
  return api(path+'/'+command,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input),signal:AbortSignal.timeout(20000)});
}
async function syncLoad(manual=false){
  if(busy)return;
  const response=await api('/api/bid-monitor/snapshot',{signal:AbortSignal.timeout(15000)});
  syncIdentity(response.userId);
  const snapshot=response.snapshot;
  if(!snapshot?.updatedAt){if(manual)syncText('当前网站账户还没有成功同步的数据');return;}
  if(!manual&&(snapshot.updatedAt===syncStamp||(raw.length&&!source.startsWith('同步快照'))))return;
  receive(snapshot.rows,'同步快照 '+new Date(snapshot.updatedAt).toLocaleString('zh-CN')+
    (snapshot.selection==='created_window_all'?' · 全部计划（'+snapshot.rows.length+' 条）':snapshot.selection==='spend_desc_top_200'?' · 历史前 200 条快照':' · 历史数据')+
    (snapshot.createdStart?' · 计划创建 '+snapshot.createdStart+' 至 '+snapshot.createdEnd:''),{start:snapshot.date,end:snapshot.date});
  syncStamp=snapshot.updatedAt;
  if(manual)syncText('已读取 '+new Date(snapshot.updatedAt).toLocaleString('zh-CN')+' 的快照');
}
function syncShow(result){
  syncIdentity(result.userId);syncState=result;
  if(!syncHydrated){
    if(!syncDirty)for(const [id,key] of [['clientUser','clientUser'],['mainUserId','mainUserId'],['syncMinutes','minutes'],['syncCreatedDays','createdDays']])
      if(result[key]!=null)$('#'+id).value=result[key];
    syncHydrated=true;
  }
  $('#credentialStatus').textContent=result.configured?'已加密保存':'未保存';
  $('#syncStart').disabled=syncAction;
  $('#syncStop').disabled=syncAction||!result.enabled;
  $('#syncRun').disabled=syncAction||!result.enabled||['running','waiting'].includes(result.state);
  $('#syncForget').disabled=syncAction||!result.configured;
  const names={waiting:'已排队',running:'正在读取全部计划',ready:'服务器定时同步已开启',paused:'同步已暂停',stopped:'未开启定时同步'};
  const last=result.lastSuccess?'；最近成功：'+new Date(result.lastSuccess).toLocaleString('zh-CN'):'';
  syncText(result.error?result.error+last:(names[result.state]||'未开启定时同步')+last+
    (result.progress?'；'+result.progress:'')+(result.enabled?'；间隔 '+result.minutes+' 分钟；前 3 天至今天创建的全部计划':''),Boolean(result.error));
}
async function syncRefresh(){
  if(syncPolling||syncAction||document.hidden||!document.body.classList.contains('ready'))return;
  syncPolling=true;const revision=syncRevision;
  try{
    const result=await syncCommand('status');if(revision!==syncRevision)return;
    syncShow(result);
    if(result.lastSuccess&&result.lastSuccess!==syncStamp)await syncLoad();
  }catch(error){if(revision===syncRevision)syncText(error.message,true);}finally{syncPolling=false;}
}
for(const [id,command] of [['syncStart','start'],['syncRun','run'],['syncStop','stop'],['syncForget','forget']])$('#'+id).onclick=async()=>{
  if(command==='start'&&!confirm('将按当前网站账户加密保存创量登录凭据，并在服务器定时读取数据。关闭浏览器后仍会继续。确认保存并启用？'))return;
  if(command==='forget'&&!confirm('停止定时同步并清除保存的创量凭据？已有数据快照会保留。'))return;
  syncAction=true;syncRevision++;
  for(const button of ['syncStart','syncRun','syncStop','syncForget'])$('#'+button).disabled=true;
  syncText('正在更新服务器配置…');
  try{
    const result=await syncCommand(command);syncAction=false;syncShow(result);
    if(command==='start'||command==='forget')$('#cookie').value='';
  }catch(error){syncAction=false;syncShow(syncState);syncText(error.message,true);}
  finally{syncAction=false;setTimeout(()=>void syncRefresh(),1000);}
};
$('#syncLoad').onclick=()=>syncLoad(true).catch(error=>syncText(error.message,true));
setInterval(syncRefresh,5000);
setTimeout(()=>void syncRefresh(),500);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)void syncRefresh();});
