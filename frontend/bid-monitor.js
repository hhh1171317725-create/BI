'use strict';
const $=s=>document.querySelector(s),B=window.BidMonitor;
const today=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
$('#startDate').value=$('#endDate').value=$('#createdEnd').value=today();
const creationDate=new Date(today()+'T00:00:00Z');creationDate.setUTCDate(creationDate.getUTCDate()-3);
$('#createdStart').value=creationDate.toISOString().slice(0,10);
let raw=[],analyzed=[],visible=[],taskRules=[],page=1,range=null,source='',busy=false,followSync=true,abort;
const names={'task-missing':'未匹配任务','task-conflict':'多个任务匹配，请调整关键词','price-missing':'未配置有效结算价','missing':'字段缺失/非数值','no-register':'无注册，暂不判断','no-return':'无回传，暂不判断','abnormal':'回传超过 100%，核对口径','sample':'样本不足','pending':'当日待回补，暂不调价','loss-bid':'出价超过保本线','margin-bid':'未达目标毛利','within':'出价在理论上限内'};
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=v=>v===null||v===undefined?'--':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const fmtPercent=v=>Number.isFinite(v)?fmt(v*100)+'%':'--';
function message(text,bad=false){$('#message').textContent=text;$('#message').className=bad?'error':'';}
async function api(path,options={}){
  const response=await fetch(path,options);if(response.status===401){location.replace('/login');throw Error('请先登录');}
  const text=await response.text();let data;try{data=JSON.parse(text);}catch{throw Error(`接口 HTTP ${response.status}，未返回 JSON，请确认后端已经部署`);}
  if(!response.ok)throw Error(data.error||data.message||`HTTP ${response.status}`);return data;
}
function setBusy(value){busy=value;$('#fetch').disabled=$('#import').disabled=value;$('#cancel').disabled=!value;}
function dates(){const start=$('#startDate').value,end=$('#endDate').value;if(!start||!end||start>end)throw Error('请选择有效的统计日期范围');return{start,end};}
function receive(rows,label,datesValue,live=false){
  if(!rows.length)throw Error('返回 0 条计划，保留原有结果');const next=rows.map(B.normalize);
  if(!next.some(r=>r.cost!==null&&r.registrations!==null&&r.conversions!==null&&r.bid!==null))throw Error('未识别到消耗、转化数、注册数和出价四个字段，请核对报表');
  raw=next;range=datesValue;source=label;followSync=live;page=1;render();message(`已读取 ${raw.length} 条计划`);
}
function render(){
  $('#source').textContent=range?`${source} · 统计区间 ${range.start} 至 ${range.end} · ${raw.length} 条计划（元）`:'尚未查询或导入数据';
  const current=range?range.end>=today():true;$('#lag').hidden=!current;
  analyzed=raw.map(r=>B.analyzeTask(r,taskRules,0,20,current));
  const chosen=$('#taskFilter').value;
  $('#taskFilter').innerHTML='<option value="">全部任务</option><option value="__unmatched">未匹配 / 冲突</option>'+taskRules.map((r,i)=>`<option value="task:${i}">${esc(r.name)}</option>`).join('');
  if([...$('#taskFilter').options].some(o=>o.value===chosen))$('#taskFilter').value=chosen;
  const selected=$('#taskFilter').value,q=$('#search').value.trim().toLowerCase(),filter=$('#filter').value,key=$('#sort').value;
  visible=analyzed.filter(r=>(!selected||(selected==='__unmatched'?!r.task:r.task===taskRules[Number(selected.slice(5))]?.name))&&
    (!q||[r.id,r.name,r.account,r.accountId,r.optimizer,r.task].join(' ').toLowerCase().includes(q))&&(filter==='all'||(filter==='available'?r.bidProfitRate!==null:r.bidProfitRate===null)))
    .sort((a,b)=>(b[key]??-Infinity)-(a[key]??-Infinity));
  const summary=B.summarizeCash(visible);
  $('#metrics').innerHTML=[['出价利润率',fmtPercent(summary.bidProfitRate)]]
    .map(([label,value])=>`<div class="metric"><span>${label}</span><strong>${value}</strong></div>`).join('');
  const unpriced=analyzed.filter(r=>r.price===null).length;
  $('#pricingCoverage').textContent=raw.length?`${raw.length-unpriced} / ${raw.length} 条计划已匹配结算价${unpriced?'；未匹配或冲突的计划不计算出价利润率':''}`:'';
  const size=Number($('#pageSize').value),pages=Math.max(1,Math.ceil(visible.length/size));page=Math.min(page,pages);
  $('#rows').innerHTML=visible.slice((page-1)*size,page*size).map(r=>`<tr><td>${esc(r.name||'未命名计划')}<small>${esc(r.id)}</small></td><td>${esc(r.account||'账户名称缺失')}<small>${esc(r.accountId)}</small></td><td>${esc(r.optimizer||'--')}</td><td>${esc(r.task||names[r.pricingStatus]||'未匹配')}</td><td>${fmt(r.price)}</td>${[r.cost,r.conversions,r.registrations].map(v=>`<td>${fmt(v)}</td>`).join('')}<td>${r.ratio===null?'--':fmt(r.ratio*100)+'%'}</td><td>${fmt(r.bid)}</td><td>${fmt(r.breakEvenBid)}</td><td title="盈亏线出价：${fmt(r.breakEvenBid)}" class="${r.bidProfitRate===null?'':r.bidProfitRate<0?'bad':'good'}">${fmtPercent(r.bidProfitRate)}</td></tr>`).join('')||'<tr><td colspan="12" class="empty">没有符合条件的计划</td></tr>';
  $('#count').textContent=`${visible.length} 条`;$('#pageLabel').textContent=`第 ${page} / ${pages} 页`;$('#prev').disabled=page<=1;$('#next').disabled=page>=pages;$('#export').disabled=!visible.length;
}
$('#fetch').onclick=async()=>{
  if(busy)return;setBusy(true);abort=new AbortController();
  try{
    const selected=dates(),payload={startDate:selected.start,endDate:selected.end,createdStart:$('#createdStart').value,createdEnd:$('#createdEnd').value};
    const prepared=await syncPrepareQuery(abort.signal);
    payload.expectedUserId=prepared.userId;payload.queryRevision=prepared.queryRevision;
    let all=[],total=null;const ids=new Set();
    for(let p=1;p<=4;p++){
      message(`正在读取第 ${p} 页，已读取 ${all.length}${total===null?'':' / '+Math.min(400,total)} 条`);
      const data=await api('/api/bid-monitor/server-sync/page',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...payload,page:p,total}),signal:abort.signal});
      const n=Number(data.total);
      if(data.total==null||!Number.isSafeInteger(n)||n<0)throw Error('接口总条数异常');
      if(total!==null&&total!==n)throw Error('分页期间计划总数变化，请重试');total=n;
      if(!Array.isArray(data.rows)||data.rows.length!==Math.min(100,Math.max(0,Math.min(400,total)-all.length)))throw Error('分页数据不完整，保留原有结果');
      for(const row of data.rows){const r=B.normalize(row),id=`${r.accountId}:${r.id}`;if(!r.id||ids.has(id))throw Error('分页出现缺失或重复计划，保留原有结果');ids.add(id);}
      all.push(...data.rows);if(all.length>=Math.min(400,total))break;
    }
    if(all.length!==Math.min(400,total))throw Error('消耗前 400 条读取不完整，保留原有结果');
    const current=today(),start=new Date(current+'T00:00:00Z');start.setUTCDate(start.getUTCDate()-3);
    const live=selected.start===current&&selected.end===current&&payload.createdStart===start.toISOString().slice(0,10)&&payload.createdEnd===current;
    receive(all,'创量查询 · 消耗降序前 400 条 '+new Date().toLocaleTimeString('zh-CN')+` · 计划创建 ${payload.createdStart||'不限'} 至 ${payload.createdEnd||'不限'}`,selected,live);
  }catch(error){message(error.name==='AbortError'?'已取消查询，原结果未改变':error.message,true);}finally{setBusy(false);}
};
$('#cancel').onclick=()=>abort?.abort();$('#import').onclick=()=>$('#file').click();
$('#file').onchange=async()=>{if(!$('#file').files.length||busy)return;setBusy(true);try{const selected=dates(),file=$('#file').files[0],form=new FormData();form.append('file',file);message('正在读取 Excel…');const data=await api('/api/bid-monitor/import',{method:'POST',body:form});receive(data.rows,`导入 ${file.name}`,selected);}catch(error){message(error.message,true);}finally{$('#file').value='';setBusy(false);}};
for(const id of ['search','taskFilter','filter','sort','pageSize'])$('#'+id).addEventListener('input',()=>{page=1;render();});
for(const id of ['startDate','endDate','createdStart','createdEnd'])$('#'+id).onchange=()=>{if(raw.length)message('日期已修改，下方仍为原统计区间数据，请重新查询');};
$('#prev').onclick=()=>{page--;render();};$('#next').onclick=()=>{page++;render();};
$('#export').onclick=()=>{
  const cell=v=>'"'+String(v??'').replace(/^[=+@\-]/,"'$&").replaceAll('"','""')+'"';
  const rows=[['计划ID','计划名称','账户ID','账户名称','优化师','任务','结算单价','统计开始','统计结束','总消耗','转化数','注册数','回传比例','当前出价','佣金','规则赠款','现金消耗','盈亏线出价','出价利润率'],
    ...visible.map(r=>[r.id,r.name,r.accountId,r.account,r.optimizer,r.task,r.price,range.start,range.end,r.cost,r.conversions,r.registrations,r.ratio,r.bid,r.commission,r.grant,r.cashCost,r.breakEvenBid,fmtPercent(r.bidProfitRate)])];
  const url=URL.createObjectURL(new Blob(['\ufeff'+rows.map(row=>row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`出价监测_${range.start}_${range.end}.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
(async()=>{try{const session=await api('/api/session');if(!session.authenticated){location.replace('/login');return;}const permissions=await api('/api/tool-visibility');if(permissions.bidMonitor!==true){location.replace('/tools');return;}document.body.classList.add('ready');render();}catch(error){document.body.classList.add('ready');message(error.message,true);}})();
