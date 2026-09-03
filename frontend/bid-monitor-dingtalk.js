'use strict';
let dingOwner='',dingConfig={},dingBusy=false,dingDirty=false;
function dingStatus(text,bad=false){$('#dingStatus').textContent=text;$('#dingStatus').className=bad?'error':'muted';}
function dingShow(data,fill=false){
  if(!data.userId||!Array.isArray(data.availableTasks))throw Error('推送配置返回格式异常，请确认后端已更新');
  if(dingOwner&&dingOwner!==data.userId){location.reload();throw Error('网站账户已切换');}
  if(!fill&&!dingDirty&&(dingConfig.revision!==data.revision||dingConfig.pricingRevision!==data.pricingRevision))fill=true;
  dingOwner=data.userId;dingConfig=data;
  if(fill){
    $('#dingTime').value=data.time||'18:00';$('#dingKeyword').value=data.keyword||'';
    $('#dingEnabled').checked=data.configured?data.enabled:true;$('#dingClearSecret').checked=false;
    $('#dingWebhook').value=$('#dingSecret').value='';
    const selected=new Set(data.tasks||[]),available=new Set(data.availableTasks);
    $('#dingTasks').innerHTML=[...new Set([...data.availableTasks,...selected])].map(name=>`<label class="ding-check"><input type="checkbox" value="${esc(name)}" ${selected.has(name)?'checked':''}>${esc(name)}${available.has(name)?'':'（已删除）'}</label>`).join('')||'<span class="muted">暂无已保存任务</span>';
    dingDirty=false;
  }
  const next=data.enabled&&data.dueAt?'；下次推送：'+new Date(data.dueAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'';
  dingStatus((data.configured?'机器人已加密保存':'机器人未配置')+'；'+(data.enabled?'每天 '+data.time+' 推送':'定时推送未开启')+next+'；'+(data.lastResult||'尚未发送'),['failed','uncertain'].includes(data.state));
  $('#dingFields').disabled=dingBusy;
}
async function dingLoad(){
  if(dingBusy)return;if(dingDirty&&!confirm('放弃未保存的推送配置，重新读取？'))return;
  dingBusy=true;$('#dingFields').disabled=true;
  try{dingShow(await api('/api/bid-monitor/dingtalk',{signal:AbortSignal.timeout(15000)}),true);}
  catch(error){dingStatus(error.message,true);}finally{dingBusy=false;$('#dingFields').disabled=!dingOwner;}
}
$('#dingFields').addEventListener('input',()=>{dingDirty=true;dingStatus('推送配置有未保存的修改');});
async function dingAction(action){
  if(dingBusy||!dingOwner)return;
  if(action!=='save'&&action!=='forget'&&dingDirty){dingStatus('请先保存推送配置',true);return;}
  if(action==='send'&&!confirm('将向已保存的钉钉群发送所选任务的 TOP5，每个任务一条消息。请确认任务和群机器人无误；手动发送可能与当天定时消息重复。'))return;
  if(action==='forget'&&!confirm('停止定时推送并清除当前网站账户的机器人凭据？'))return;
  let payload={expectedUserId:dingOwner};
  if(action==='save')Object.assign(payload,{revision:dingConfig.revision,pricingRevision:dingConfig.pricingRevision,
    webhook:$('#dingWebhook').value.trim(),secret:$('#dingSecret').value.trim(),keyword:$('#dingKeyword').value.trim(),
    time:$('#dingTime').value,enabled:$('#dingEnabled').checked,clearSecret:$('#dingClearSecret').checked,
    tasks:[...document.querySelectorAll('#dingTasks input:checked')].map(el=>el.value)});
  dingBusy=true;$('#dingFields').disabled=true;dingStatus(action==='send'?'正在发送，请勿重复点击…':'正在处理…');
  try{
    const data=await api('/api/bid-monitor/dingtalk'+(action==='save'?'':'/'+action),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),signal:AbortSignal.timeout(action==='send'?240000:20000)});
    if(data.userId!==dingOwner){location.reload();throw Error('网站账户已切换');}
    if(action==='preview'){$('#dingPreview').textContent=data.messages.map(m=>m.text).join('\n\n--------------------\n\n');$('#dingPreview').hidden=false;dingStatus('预览已生成，尚未发送到群');}
    else{dingShow(data,action==='save'||action==='forget');$('#dingPreview').hidden=true;}
  }catch(error){dingStatus(action==='send'?'发送未完成或结果未确认，请先检查群消息。'+error.message:error.message,true);}
  finally{dingBusy=false;$('#dingFields').disabled=false;}
}
for(const [id,action] of [['dingSave','save'],['dingPreviewButton','preview'],['dingSend','send'],['dingForget','forget']])$('#'+id).onclick=()=>dingAction(action);
$('#dingReload').onclick=dingLoad;
window.addEventListener('bid-pricing-saved',()=>{if(!dingDirty)void dingLoad();else dingStatus('任务价格已更新，请重新读取推送配置后保存');});
const dingInit=setInterval(()=>{if(document.body.classList.contains('ready')){clearInterval(dingInit);void dingLoad();}},250);
setInterval(async()=>{
  if(!dingOwner||dingBusy||dingDirty||document.hidden)return;
  dingBusy=true;
  try{const data=await api('/api/bid-monitor/dingtalk',{signal:AbortSignal.timeout(10000)});if(!dingDirty)dingShow(data);}
  catch(error){if(!dingDirty)dingStatus(error.message,true);}finally{dingBusy=false;if(dingOwner)$('#dingFields').disabled=false;}
},15000);
