'use strict';
let pricingOwner='',pricingRevision='',pricingDirty=false,pricingBusy=false;
function pricingStatus(text,bad=false){$('#pricingStatus').textContent=text;$('#pricingStatus').className=bad?'error':'muted';}
function pricingDraw(){
  $('#pricingRows').innerHTML=taskRules.map((r,i)=>`<tr><td><input data-index="${i}" data-key="name" aria-label="任务名称 ${i+1}" maxlength="80" value="${esc(r.name)}"></td><td><input data-index="${i}" data-key="keyword" aria-label="账户关键词 ${i+1}" maxlength="80" value="${esc(r.keyword)}"></td><td><input data-index="${i}" data-key="price" aria-label="结算单价 ${i+1}" type="number" min="0.000001" max="1000000" step="0.000001" value="${esc(r.price)}"></td><td><button data-remove="${i}" title="删除任务">删除</button></td></tr>`).join('')||'<tr><td colspan="4" class="empty">暂无任务价格</td></tr>';
}
function pricingChanged(){pricingDirty=true;$('#taskFilter').value='';pricingStatus('任务价格有未保存的修改');render();}
async function pricingLoad(){
  if(pricingBusy)return;
  if(pricingDirty&&!confirm('放弃未保存的任务价格修改，重新读取？'))return;
  pricingBusy=true;$('#pricingFields').disabled=true;
  try{
    const data=await api('/api/bid-monitor/server-sync/pricing',{signal:AbortSignal.timeout(15000)});
    if(!data.userId||!Array.isArray(data.rules))throw Error('任务配置返回格式异常');
    if(pricingOwner&&pricingOwner!==data.userId){location.reload();return;}
    pricingOwner=data.userId;pricingRevision=data.revision;taskRules=data.rules;pricingDirty=false;
    pricingDraw();render();pricingStatus('任务价格已从服务器读取');
  }catch(error){pricingStatus(error.message,true);}finally{pricingBusy=false;$('#pricingFields').disabled=!pricingOwner;}
}
$('#pricingRows').addEventListener('input',event=>{const el=event.target;if(el.dataset.key){taskRules[Number(el.dataset.index)][el.dataset.key]=el.value;pricingChanged();}});
$('#pricingRows').addEventListener('click',event=>{const button=event.target.closest('[data-remove]');if(button){taskRules.splice(Number(button.dataset.remove),1);pricingDraw();pricingChanged();}});
$('#pricingAdd').onclick=()=>{if(taskRules.length>=50){pricingStatus('最多配置 50 个任务',true);return;}taskRules.push({name:'',keyword:'',price:''});pricingDraw();pricingChanged();$('#pricingRows tr:last-child input').focus();};
$('#pricingSave').onclick=async()=>{
  if(pricingBusy||!pricingOwner)return;pricingBusy=true;$('#pricingFields').disabled=true;
  try{
    const data=await api('/api/bid-monitor/server-sync/pricing',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedUserId:pricingOwner,revision:pricingRevision,rules:taskRules}),signal:AbortSignal.timeout(15000)});
    if(data.userId!==pricingOwner){location.reload();return;}
    taskRules=data.rules;pricingRevision=data.revision;pricingDirty=false;pricingDraw();render();pricingStatus('任务价格已保存');
  }catch(error){pricingStatus(error.message,true);}finally{pricingBusy=false;$('#pricingFields').disabled=false;}
};
$('#pricingReload').onclick=pricingLoad;
const pricingTimer=setInterval(()=>{if(document.body.classList.contains('ready')){clearInterval(pricingTimer);void pricingLoad();}},200);
