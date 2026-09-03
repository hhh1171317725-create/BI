'use strict';
const $=s=>document.querySelector(s),B=window.BidMonitor;
const today=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
$('#startDate').value=$('#endDate').value=$('#createdEnd').value=today();
const creationDate=new Date(today()+'T00:00:00Z');creationDate.setUTCDate(creationDate.getUTCDate()-3);
$('#createdStart').value=creationDate.toISOString().slice(0,10);
let raw=[],analyzed=[],visible=[],taskRules=[],page=1,range=null,source='',busy=false,abort;
const names={'task-missing':'未匹配任务','task-conflict':'多个任务匹配，请调整关键词','price-missing':'未配置有效结算价','missing':'字段缺失/非数值','no-register':'无注册，暂不判断','no-return':'无回传，暂不判断','abnormal':'回传超过 100%，核对口径','sample':'样本不足','pending':'当日待回补，暂不调价','loss-bid':'出价超过保本线','margin-bid':'未达目标毛利','within':'出价在理论上限内'};
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=v=>v===null||v===undefined?'--':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
function message(text,bad=false){$('#message').textContent=text;$('#message').className=bad?'error':'';}
async function api(path,options={}){
  const response=await fetch(path,options);if(response.status===401){location.replace('/login');throw Error('请先登录');}
  const text=await response.text();let data;try{data=JSON.parse(text);}catch{throw Error(`接口 HTTP ${response.status}，未返回 JSON，请确认后端已经部署`);}
  if(!response.ok)throw Error(data.error||data.message||`HTTP ${response.status}`);return data;
}
function setBusy(value){busy=value;$('#fetch').disabled=$('#import').disabled=value;$('#cancel').disabled=!value;}
function dates(){const start=$('#startDate').value,end=$('#endDate').value;if(!start||!end||start>end)throw Error('请选择有效的统计日期范围');return{start,end};}
function receive(rows,label,datesValue){
  if(!rows.length)throw Error('返回 0 条计划，保留原有结果');const next=rows.map(B.normalize);
  if(!next.some(r=>r.cost!==null&&r.registrations!==null&&r.conversions!==null&&r.bid!==null))throw Error('未识别到消耗、转化数、注册数和出价四个字段，请核对报表');
  raw=next;range=datesValue;source=label;page=1;render();message(`已读取 ${raw.length} 条计划`);
}
function render(){
  const margin=Number($('#margin').value),sample=Number($('#minSample').value);
  $('#source').textContent=range?`${source} · 统计区间 ${range.start} 至 ${range.end} · ${raw.length} 条计划（元）`:'尚未查询或导入数据';
  const current=range?range.end>=today():true;$('#lag').hidden=!current;
  if(!Number.isFinite(margin)||margin<0||margin>=100||!Number.isInteger(sample)||sample<1){
    analyzed=[];visible=[];$('#metrics').textContent='请填写有效毛利率和样本阈值';$('#rows').innerHTML='<tr><td colspan="17" class="empty">判断参数无效</td></tr>';
    $('#count').textContent='0 条';$('#export').disabled=$('#prev').disabled=$('#next').disabled=true;return;
  }
  analyzed=raw.map(r=>B.analyzeTask(r,taskRules,margin,sample,current));
  const chosen=$('#taskFilter').value;
  $('#taskFilter').innerHTML='<option value="">全部任务</option><option value="__unmatched">未匹配 / 冲突</option>'+taskRules.map((r,i)=>`<option value="task:${i}">${esc(r.name)}</option>`).join('');
  if([...$('#taskFilter').options].some(o=>o.value===chosen))$('#taskFilter').value=chosen;
  const selected=$('#taskFilter').value,q=$('#search').value.trim().toLowerCase(),filter=$('#filter').value,key=$('#sort').value;
  visible=analyzed.filter(r=>(!selected||(selected==='__unmatched'?!r.task:r.task===taskRules[Number(selected.slice(5))]?.name))&&
    (!q||[r.id,r.name,r.account,r.accountId,r.task].join(' ').toLowerCase().includes(q))&&(filter==='all'||r.status===filter))
    .sort((a,b)=>(b[key]??-Infinity)-(a[key]??-Infinity));
  const total=key=>visible.length&&visible.every(r=>r[key]!==null)?visible.reduce((n,r)=>n+r[key],0):null;
  const cost=total('cost'),reg=total('registrations'),conv=total('conversions');
  $('#metrics').innerHTML=[['实际消耗',fmt(cost)],['注册数',reg??'--'],['转化数',conv??'--'],['整体回传比例',reg>0&&conv!==null?fmt(conv/reg*100)+'%':'--'],['预估利润',fmt(total('projectedProfit'))],['预估 ROI',total('revenue')!==null&&total('projectedCost')>0?fmt(total('revenue')/total('projectedCost'))+' 倍':'--'],['实际消耗利润',fmt(total('profit'))]]
    .map(([k,v])=>`<div class="metric"><span>${k}</span><strong>${esc(v)}</strong></div>`).join('');
  const unpriced=analyzed.filter(r=>r.price===null).length;
  $('#pricingCoverage').textContent=raw.length?`${raw.length-unpriced} / ${raw.length} 条计划已匹配结算价${unpriced?'；未匹配或冲突的计划不计算利润':''}`:'';
  const size=Number($('#pageSize').value),pages=Math.max(1,Math.ceil(visible.length/size));page=Math.min(page,pages);
  $('#rows').innerHTML=visible.slice((page-1)*size,page*size).map(r=>`<tr><td>${esc(r.name||'未命名计划')}<small>${esc(r.id)}</small></td><td>${esc(r.account||'账户名称缺失')}<small>${esc(r.accountId)}</small></td><td>${esc(r.task||names[r.pricingStatus]||'未匹配')}</td><td>${fmt(r.price)}</td>${[r.cost,r.conversions,r.registrations].map(v=>`<td>${fmt(v)}</td>`).join('')}<td>${r.ratio===null?'--':fmt(r.ratio*100)+'%'}</td>${[r.bid,r.cpa,r.breakEven,r.ceiling,r.projectedCost].map(v=>`<td>${fmt(v)}</td>`).join('')}<td class="${r.projectedProfit<0?'bad':'good'}">${fmt(r.projectedProfit)}</td><td>${fmt(r.bidRoi)}</td><td class="${r.profit<0?'bad':'good'}">${fmt(r.profit)}</td><td class="${r.status==='loss-bid'?'bad':r.status==='within'?'good':'warn'}">${esc(names[r.status])}</td></tr>`).join('')||'<tr><td colspan="17" class="empty">没有符合条件的计划</td></tr>';
  $('#count').textContent=`${visible.length} 条`;$('#pageLabel').textContent=`第 ${page} / ${pages} 页`;$('#prev').disabled=page<=1;$('#next').disabled=page>=pages;$('#export').disabled=!visible.length;
}
$('#fetch').onclick=async()=>{
  if(busy)return;setBusy(true);abort=new AbortController();
  try{
    const selected=dates(),payload={startDate:selected.start,endDate:selected.end,cookie:$('#cookie').value.trim(),clientUser:$('#clientUser').value.trim(),mainUserId:$('#mainUserId').value.trim(),createdStart:$('#createdStart').value,createdEnd:$('#createdEnd').value};
    if(!payload.cookie)throw Error('请填写 Cookie；服务器同步可使用已保存凭据');
    let all=[],total=null;const ids=new Set();
    for(let p=1;p<=10000;p++){
      message(`正在读取第 ${p} 页，已读取 ${all.length}${total===null?'':' / '+total} 条`);
      const data=await api('/api/bid-monitor/page',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...payload,page:p,total}),signal:abort.signal});
      const n=Number(data.total);
      if(data.total==null||!Number.isSafeInteger(n)||n<0||n>1000000)throw Error('接口总条数异常或超出安全范围，未截断结果');
      if(total!==null&&total!==n)throw Error('分页期间计划总数变化，请重试');total=n;
      if(!Array.isArray(data.rows)||data.rows.length!==Math.min(100,Math.max(0,total-all.length)))throw Error('分页数据不完整，保留原有结果');
      for(const row of data.rows){const r=B.normalize(row),id=`${r.accountId}:${r.id}`;if(!r.id||ids.has(id))throw Error('分页出现缺失或重复计划，保留原有结果');ids.add(id);}
      all.push(...data.rows);if(all.length>=total)break;
    }
    if(all.length!==total)throw Error('尚未读取全部计划，保留原有结果');
    receive(all,'创量全量查询 '+new Date().toLocaleTimeString('zh-CN')+` · 计划创建 ${payload.createdStart||'不限'} 至 ${payload.createdEnd||'不限'}`,selected);
  }catch(error){message(error.name==='AbortError'?'已取消查询，原结果未改变':error.message,true);}finally{setBusy(false);}
};
$('#cancel').onclick=()=>abort?.abort();$('#import').onclick=()=>$('#file').click();
$('#file').onchange=async()=>{if(!$('#file').files.length||busy)return;setBusy(true);try{const selected=dates(),file=$('#file').files[0],form=new FormData();form.append('file',file);message('正在读取 Excel…');const data=await api('/api/bid-monitor/import',{method:'POST',body:form});receive(data.rows,`导入 ${file.name}`,selected);}catch(error){message(error.message,true);}finally{$('#file').value='';setBusy(false);}};
for(const id of ['margin','minSample','search','taskFilter','filter','sort','pageSize'])$('#'+id).addEventListener('input',()=>{page=1;render();});
for(const id of ['startDate','endDate','createdStart','createdEnd'])$('#'+id).onchange=()=>{if(raw.length)message('日期已修改，下方仍为原统计区间数据，请重新查询');};
$('#prev').onclick=()=>{page--;render();};$('#next').onclick=()=>{page++;render();};
$('#export').onclick=()=>{
  const cell=v=>'"'+String(v??'').replace(/^[=+@\-]/,"'$&").replaceAll('"','""')+'"';
  const rows=[['计划ID','计划名称','账户ID','账户名称','任务','结算单价','统计开始','统计结束','实际消耗','转化数','注册数','回传比例','当前出价','注册成本','理论保本价','目标出价上限','预估消耗','预估利润','预估ROI','实际消耗利润','判断'],
    ...visible.map(r=>[r.id,r.name,r.accountId,r.account,r.task,r.price,range.start,range.end,r.cost,r.conversions,r.registrations,r.ratio,r.bid,r.cpa,r.breakEven,r.ceiling,r.projectedCost,r.projectedProfit,r.bidRoi,r.profit,names[r.status]])];
  const url=URL.createObjectURL(new Blob(['\ufeff'+rows.map(row=>row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`出价监测_${range.start}_${range.end}.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
(async()=>{try{const session=await api('/api/session');if(!session.authenticated){location.replace('/login');return;}const permissions=await api('/api/tool-visibility');if(permissions.bidMonitor!==true){location.replace('/tools');return;}document.body.classList.add('ready');render();}catch(error){document.body.classList.add('ready');message(error.message,true);}})();
