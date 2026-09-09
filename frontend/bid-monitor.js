'use strict';
const $=s=>document.querySelector(s),B=window.BidMonitor;
const today=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
$('#startDate').value=$('#endDate').value=$('#createdEnd').value=today();
const creationDate=new Date(today()+'T00:00:00Z');creationDate.setUTCDate(creationDate.getUTCDate()-3);
$('#createdStart').value=creationDate.toISOString().slice(0,10);
let raw=[],analyzed=[],visible=[],aggregateRows=[],taskRules=[],page=1,range=null,source='',busy=false,followSync=true,abort,sortKey='cost',sortDirection='desc';
let gapData=null,gapGeneration=0,selectedAccount=null;
const aggregateColumnStorage='bid-monitor-aggregate-columns-v1';
let aggregateSettings={};
try{const saved=JSON.parse(localStorage.getItem(aggregateColumnStorage)||'{}');if(saved&&typeof saved==='object'&&!Array.isArray(saved))aggregateSettings=saved;}catch{}
function currentAggregateColumns(view){const selected=aggregateSettings[view];return Array.isArray(selected)?selected.map(key=>aggregateColumns.find(column=>column[1]===key)).filter(Boolean):aggregateColumns;}
function saveAggregateSettings(){try{localStorage.setItem(aggregateColumnStorage,JSON.stringify(aggregateSettings));}catch{message('浏览器未允许保存展示列，本次选择仍然有效。');}}
function drawAggregateColumns(view){
  $('#aggregateSettings').hidden=view==='plans';if(view==='plans')return;
  const columns=currentAggregateColumns(view),keys=new Set(columns.map(([,key])=>key));
  $('#aggregateColumnChoices').innerHTML=aggregateColumns.map(([label,key])=>`<label class="ding-check"><input type="checkbox" data-aggregate-column="${key}" ${keys.has(key)?'checked':''}>${label}</label>`).join('');
}
function aggregateCell(row,key){
  const priceDependent=['commission','profit','estimatedRoi','bidProfitRate'].includes(key);
  const title=priceDependent?` title="仅汇总已取得日报/手动单价及 gap 的 ${row.priced} / ${row.plans} 条计划"`:
    ['estimatedCompensation','cashCost'].includes(key)?' title="该指标不依赖结算单价，汇总全部计划"':'';
  const tone=['profit','bidProfitRate'].includes(key)&&row[key]!==null?(row[key]<0?'bad':'good'):'';
  const value=key==='priced'?`${row.priced} / ${row.plans}`:['plans','todayPlans','spendingPlans','accounts'].includes(key)?row[key]:key==='estimatedRoi'?fmtRoi(row[key]):['ratio','bidProfitRate'].includes(key)?fmtPercent(row[key]):fmt(row[key]);
  return `<td${title} class="${tone}">${value}</td>`;
}
const cachedAnalysis=B.createAnalysisCache();
let valueFilterRows=null,filteredAnalysis=null,filteredKey='',filteredRows=[],qualityScope=[],groupCache=new Map();
function dataQuality(row){
  if(!row.task||!Number.isFinite(row.price))return 'incomplete';
  return ['bid-return','inferred'].includes(row.taskSource)?'estimated':'linked';
}
async function loadGap(){
  if(!range)return;
  const generation=++gapGeneration,anchor=range.end;
  gapData=null;render();$('#gapStatus').title='';$('#gapStatus').textContent='正在关联统计结束日前第4天至第2天的大航海账户数据…';
  try{
    const result=await api('/api/bid-monitor/gap?endDate='+encodeURIComponent(anchor),{signal:AbortSignal.timeout(30000)});
    if(generation!==gapGeneration)return;
    if(result.anchor!==anchor||!result.accounts||typeof result.accounts!=='object')throw Error('gap返回格式异常');
    gapData=result;render();$('#gapStatus').textContent=`gap区间：${result.start} 至 ${result.end} · 日报单价日期：${result.priceDate||'未返回'} · 点击计划查看依据`;
    $('#gapStatus').title=result.basis||'';
  }catch(error){if(generation!==gapGeneration)return;gapData=null;render();$('#gapStatus').textContent='gap读取失败：'+error.message+'；相关收益指标暂不计算，请重试。';}
}
function gapTitle(id,row){
  const canonical=value=>String(value??'').replace(/\.0+$/,'').replace(/^0+(?=\d)/,'');
  const account=Object.entries(gapData?.accounts||{}).find(([key])=>canonical(key)===canonical(id))?.[1];
  const task=Object.entries(gapData?.tasks||{}).find(([key])=>key.trim().toLowerCase()===String(row?.referenceTask||'').trim().toLowerCase())?.[1];
  const data=row?.gapSource==='task-reference'?task:account,source=row?.gapSource==='task-reference'?`同任务“${row.referenceTask}”参考 gap`:'账户 gap';
  const detail=data?.days?.map(d=>`${d.date}：结算${d.settlements??'--'} / 注册${d.registrations??'--'} = ${d.ratio??'--'}${d.reason?`（${d.reason}）`:''}`).join('；');
  return row?.gap===null?(row?.gapReason||'无可计算的 gap'):`${source}${data?`，${data.validDays}/3天有效${detail?'；'+detail:''}`:''}`;
}
const names={'task-missing':'未匹配任务','task-conflict':'多个任务匹配，请调整关键词','price-missing':'未配置有效结算价','missing':'字段缺失/非数值','no-register':'无注册，暂不判断','no-return':'无回传，暂不判断','abnormal':'回传超过 100%，核对口径','sample':'样本不足','pending':'当日待回补，暂不调价','loss-bid':'出价超过保本线','margin-bid':'未达目标毛利','within':'出价在理论上限内'};
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=v=>v===null||v===undefined?'--':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const fmtPercent=v=>Number.isFinite(v)?fmt(v*100)+'%':'--';
const fmtRoi=v=>Number.isFinite(v)?Number(v).toLocaleString('zh-CN',{minimumFractionDigits:3,maximumFractionDigits:3}):'--';
const textSortKeys=new Set(['name','platform','account','accountId','optimizer','task','priceSource','appType','deepBidType','deepExternalAction','externalAction','planStatus']);
const planColumns=[['计划','name'],['账户','account'],['优化师','optimizer'],['任务','task'],['结算单价','basePrice'],['单价来源','priceSource'],['总消耗','cost'],['转化数','conversions'],['注册数','registrations'],['回传比例','ratio'],['当前出价','bid'],['预估 ROI','estimatedRoi'],['盈亏线出价','breakEvenBid'],['出价利润率','bidProfitRate'],['gap','gap'],['实际单价','price']];
const optionalColumns=[['平台','platform'],['应用类型','appType'],['深度出价类型','deepBidType'],['深度 CPA 出价','deepCpaBid'],['深度转化目标','deepExternalAction'],['转化目标','externalAction'],['计划状态','planStatus']];
const optionalTextKeys=['platform','appType','deepBidType','deepExternalAction','externalAction','planStatus'];
const optionalColumnStorage='bid-monitor-visible-columns-v1';
let visibleOptionalColumns=new Set();
try{visibleOptionalColumns=new Set(JSON.parse(localStorage.getItem(optionalColumnStorage)||'[]').filter(key=>optionalColumns.some(column=>column[1]===key)));}catch{}
let planDisplayKeys=null;
try{const saved=JSON.parse(localStorage.getItem('bid-plan-display-columns-v2')||'null');if(Array.isArray(saved)){const valid=new Set([...planColumns,...optionalColumns].map(column=>column[1]));planDisplayKeys=['name',...new Set(saved.filter(key=>key!=='name'&&valid.has(key)))];}}catch{}
const activePlanColumns=()=>planDisplayKeys?planDisplayKeys.map(key=>[...planColumns,...optionalColumns].find(column=>column[1]===key)).filter(Boolean):[...planColumns,...optionalColumns.filter(column=>visibleOptionalColumns.has(column[1]))];
function planCell(r,key,index){
  if(key==='name')return `<td><button type="button" class="plan-detail-link" data-plan-detail="${index}" title="查看计划数据与计算依据">${esc(r.name||'未命名计划')}</button><small>${esc(r.id)}</small></td>`;
  if(key==='account')return `<td>${esc(r.account||'账户名称缺失')}<small>${esc(r.accountId)}</small></td>`;
  if(key==='task'){const linked=['inferred','daily-report','plan-name','bid-return'].includes(r.taskSource),detail=r.inference||{};const title=r.taskSource==='daily-report'?`查询 ${detail.taskDate||'历史'} 大航海日报：账户任务名 ${detail.reportedTaskName||'--'}`:r.taskSource==='plan-name'?'账户日报未提供任务，按计划/账户名称精确识别日报任务':r.taskSource==='bid-return'?`当前出价 × 回传比例估算结算金额 ${fmt(detail.estimatedSettlementPrice)}，最接近该任务实际单价 ${fmt(detail.matchedActualPrice)}`:r.taskSource==='inferred'?`旧数据兼容：按 ${detail.settlementPriceDate||'历史'} 日报结算单价识别任务`:r.missingReason||'';const suffix=r.taskSource==='daily-report'?'（日报）':r.taskSource==='plan-name'?'（名称识别）':r.taskSource==='bid-return'?'（出价回传估算）':r.taskSource==='inferred'?'（历史识别）':'';return `<td${title?` title="${esc(title)}"`:''}>${esc((r.task||names[r.pricingStatus]||'未匹配')+(linked?suffix:''))}</td>`;}
  const priceSource={manual:'手动设置','daily-account':`${r.priceDate} 账户日报`,'daily-task':`${r.priceDate} 同任务日报参考`}[r.priceSource];
  if(key==='priceSource')return `<td title="${esc(r.missingReason||priceSource||'暂无可用来源')}">${esc(priceSource||'--')}</td>`;
  const title=key==='gap'?gapTitle(r.accountId,r):key==='basePrice'?(priceSource||r.priceReason||r.missingReason):key==='price'?(priceSource?`${priceSource}单价 × ${r.gapSource==='task-reference'?'同任务参考 gap':'账户 gap'}`:r.missingReason):key==='estimatedRoi'?(r[key]===null?r.missingReason:`预估赔付金额：${fmt(r.estimatedCompensation)}`):key==='bidProfitRate'?(r[key]===null?r.missingReason:`盈亏线出价：${fmt(r.breakEvenBid)}`):r[key]===null?r.missingReason:'';
  const value=['ratio','bidProfitRate'].includes(key)?fmtPercent(r[key]):['estimatedRoi','gap'].includes(key)?fmtRoi(r[key]):textSortKeys.has(key)?esc(r[key]||'--'):fmt(r[key]);
  const tone=key==='bidProfitRate'&&r[key]!==null?(r[key]<0?'bad':'good'):'';
  return `<td title="${esc(title)}" class="${tone}">${value}</td>`;
}
const aggregateColumns=[['计划数','plans'],['今日新上','todayPlans'],['有消耗计划','spendingPlans'],['账户数','accounts'],['价格匹配','priced'],['总消耗','cost'],['转化数','conversions'],['注册数','registrations'],['回传比例','ratio'],['佣金','commission'],['预估赔付','estimatedCompensation'],['现金消耗','cashCost'],['现金利润','profit'],['预估 ROI','estimatedRoi'],['出价利润率','bidProfitRate']];
const dimensionLabels={platform:'平台',account:'账户名称',accountId:'账户ID',optimizer:'优化师',task:'任务',externalAction:'转化目标',deepExternalAction:'深度转化目标',appType:'应用类型'};
const viewDimensions=view=>view==='conversionTargets'?['externalAction','deepExternalAction','appType']:view==='optimizerTasks'?['optimizer','task']:view==='tasks'?['task']:view==='accounts'?['account','accountId']:['optimizer'];
function sortHeader(label,key){const state=key===sortKey?sortDirection:'none',aria=state==='asc'?'ascending':state==='desc'?'descending':'none',description=state==='asc'?'当前升序':state==='desc'?'当前降序':'点击排序';return `<th aria-sort="${aria}"><button class="sort-header" type="button" data-sort-key="${key}" data-sort-state="${state}" aria-label="按${label}排序，${description}">${label}</button></th>`;}
function sortRows(rows){return rows.sort((a,b)=>{const left=a[sortKey],right=b[sortKey],leftMissing=left===null||left===undefined||Number.isNaN(left),rightMissing=right===null||right===undefined||Number.isNaN(right);if(leftMissing||rightMissing)return leftMissing===rightMissing?0:leftMissing?1:-1;const result=typeof left==='number'&&typeof right==='number'?left-right:String(left).localeCompare(String(right),'zh-CN',{numeric:true});return sortDirection==='asc'?result:-result;});}
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
  raw=next;range=datesValue;source=label;followSync=live;page=1;const loading=loadGap();message(`已读取 ${raw.length} 条计划`);return loading;
}
function optionValue(value){return `<option value="${esc(value)}">${esc(value||'未填写')}</option>`;}
function drawValueFilters(){
  if(valueFilterRows===analyzed)return;
  valueFilterRows=analyzed;
  for(const [key,id] of [['platform','platformFilter'],['appType','appTypeFilter'],['deepBidType','deepBidTypeFilter'],['deepExternalAction','deepExternalActionFilter'],['externalAction','externalActionFilter'],['planStatus','statusFilter']]){
    const select=$('#'+id),chosen=select.value,values=[...new Set(analyzed.map(row=>row[key]).filter(value=>value!==''))].sort((a,b)=>a.localeCompare(b,'zh-CN'));
    select.innerHTML='<option value="">全部</option>'+values.map(optionValue).join('');
    if([...select.options].some(option=>option.value===chosen))select.value=chosen;
  }
}
function optionalCell(row,key){return key==='deepCpaBid'?fmt(row[key]):esc(row[key]||'--');}
function render(){
  $('#source').textContent=range?`${source} · 统计区间 ${range.start} 至 ${range.end} · ${raw.length} 条计划（元）`:'尚未查询或导入数据';
  for(const checkbox of document.querySelectorAll('#columnSettings input[data-column]'))checkbox.checked=visibleOptionalColumns.has(checkbox.dataset.column);
  const current=range?range.end>=today():true;$('#lag').hidden=!current;
  analyzed=cachedAnalysis(raw,taskRules,current,gapData?.accounts,gapData);
  drawValueFilters();
  const chosen=$('#taskFilter').value;
  const configuredTasks=taskRules.map((rule,index)=>({name:rule.name,value:`task:${index}`})),configuredNames=new Set(configuredTasks.map(item=>item.name));
  const automaticTasks=[...new Set(analyzed.map(row=>row.task).filter(Boolean))].filter(name=>!configuredNames.has(name)).sort((a,b)=>a.localeCompare(b,'zh-CN')).map(name=>({name,value:`auto:${encodeURIComponent(name)}`}));
  $('#taskFilter').innerHTML='<option value="">全部任务</option><option value="__unmatched">未匹配 / 冲突</option>'+[...configuredTasks,...automaticTasks].map(item=>`<option value="${esc(item.value)}">${esc(item.name)}${item.value.startsWith('auto:')?'（日报自动）':''}</option>`).join('');
  if([...$('#taskFilter').options].some(o=>o.value===chosen))$('#taskFilter').value=chosen;
  const viewMode=$('#viewMode').value,aggregateMode=viewMode!=='plans',dimensions=viewDimensions(viewMode);
  drawAggregateColumns(viewMode);
  const displayedMetrics=currentAggregateColumns(viewMode);
  $('#accountDrill').hidden=!selectedAccount;$('#accountDrillLabel').textContent=selectedAccount?'当前账户：'+selectedAccount.label:'';
  const availableKeys=new Set((aggregateMode?[...dimensions.map(d=>[d,d]),...displayedMetrics]:activePlanColumns()).map(column=>column[1]));if(!availableKeys.has(sortKey)){sortKey='cost';sortDirection='desc';}
  const selected=$('#taskFilter').value,q=$('#search').value.trim().toLowerCase(),filter=$('#filter').value;
  const filterId=key=>key==='planStatus'?'statusFilter':key+'Filter';
  const quality=$('#dataQualityFilter').value;
  const filterKey=JSON.stringify([selectedAccount?.key,selected,q,filter,quality,...optionalTextKeys.map(key=>$('#'+filterId(key)).value),$('#deepCpaBidMin').value,$('#deepCpaBidMax').value]);
  if(filteredAnalysis!==analyzed||filteredKey!==filterKey){
  const selectedTask=selected.startsWith('task:')?taskRules[Number(selected.slice(5))]?.name:selected.startsWith('auto:')?decodeURIComponent(selected.slice(5)):'';
  qualityScope=analyzed.filter(r=>(!selectedAccount||B.accountIdentity(r)===selectedAccount.key)&&(!selected||(selected==='__unmatched'?!r.task:r.task===selectedTask))&&
    (!q||[r.id,r.name,r.account,r.accountId,r.optimizer,r.task,...optionalTextKeys.map(key=>r[key]),r.deepCpaBid].join(' ').toLowerCase().includes(q))&&
    (filter==='all'||(filter==='available'?r.bidProfitRate!==null:r.bidProfitRate===null))&&
    optionalTextKeys.every(key=>{const id=filterId(key);return !$('#'+id).value||r[key]===$('#'+id).value;})&&
    (!$('#deepCpaBidMin').value||Number.isFinite(r.deepCpaBid)&&r.deepCpaBid>=Number($('#deepCpaBidMin').value))&&
    (!$('#deepCpaBidMax').value||Number.isFinite(r.deepCpaBid)&&r.deepCpaBid<=Number($('#deepCpaBidMax').value)));
    filteredRows=quality==='all'?qualityScope:qualityScope.filter(row=>dataQuality(row)===quality);
    filteredAnalysis=analyzed;filteredKey=filterKey;groupCache.clear();
  }
  visible=[...filteredRows];
  const summary=B.summarizeCash(visible.filter(row=>row.price!==null));
  $('#metrics').innerHTML=[['出价利润率',fmtPercent(summary.bidProfitRate)],['预估 ROI',fmtRoi(summary.estimatedRoi)]]
    .map(([label,value])=>`<div class="metric"><span>${label}</span><strong>${value}</strong></div>`).join('');
  const unpriced=analyzed.filter(r=>r.price===null).length;
  const manualCount=analyzed.filter(r=>r.priceSource==='manual').length,dailyCount=analyzed.filter(r=>r.priceSource==='daily-account'||r.priceSource==='daily-task').length,taskGapCount=analyzed.filter(r=>r.gapSource==='task-reference').length,estimateCount=analyzed.filter(r=>r.taskSource==='bid-return').length;
  $('#pricingCoverage').textContent=raw.length?`${raw.length-unpriced} / ${raw.length} 条计划可计算（手动单价 ${manualCount}，前天日报单价 ${dailyCount}${taskGapCount?`，同任务参考 gap ${taskGapCount}`:''}${estimateCount?`，出价回传估算任务 ${estimateCount}`:''}）${unpriced?'；其余计划可将鼠标停在“--”上查看缺失原因':''}`:'';
  const groupKey=viewMode+':'+today();
  if(aggregateMode&&!groupCache.has(groupKey))groupCache.set(groupKey,B.aggregateGroups(filteredRows,dimensions,today()));
  aggregateRows=aggregateMode?sortRows([...groupCache.get(groupKey)]):[];if(!aggregateMode)sortRows(visible);
  const displayRows=aggregateMode?aggregateRows:visible,size=Number($('#pageSize').value),pages=Math.max(1,Math.ceil(displayRows.length/size));page=Math.min(page,pages);
  if(aggregateMode){
    const dimensionHeaders=dimensions.map(d=>sortHeader(dimensionLabels[d],d)).join('');
    $('#tableHead').innerHTML='<tr>'+dimensionHeaders+displayedMetrics.map(column=>sortHeader(...column)).join('')+'</tr>';
    $('#rows').innerHTML=displayRows.slice((page-1)*size,page*size).map(r=>`<tr>${dimensions.map(d=>`<td>${viewMode==='accounts'&&d==='account'?`<button type="button" class="account-drill-link" data-account-key="${esc(r.accountKey)}" data-account-label="${esc(r.account+' · '+r.accountId)}">${esc(r[d])}</button>`:esc(r[d])}</td>`).join('')}${displayedMetrics.map(([,key])=>aggregateCell(r,key)).join('')}</tr>`).join('')||`<tr><td colspan="${dimensions.length+displayedMetrics.length}" class="empty">没有符合条件的汇总数据</td></tr>`;
  }else{
    $('#tableHead').innerHTML='<tr>'+activePlanColumns().map(column=>sortHeader(...column)).join('')+'</tr>';
    $('#rows').innerHTML=displayRows.slice((page-1)*size,page*size).map((r,index)=>`<tr>${activePlanColumns().map(([,key])=>planCell(r,key,(page-1)*size+index)).join('')}</tr>`).join('')||`<tr><td colspan="${activePlanColumns().length}" class="empty">没有符合条件的计划</td></tr>`;
  }
  const unit=viewMode==='accounts'?'个账户':viewMode==='optimizers'?'名优化师':viewMode==='tasks'?'个任务':viewMode==='optimizerTasks'?'个优化师 × 任务组合':viewMode==='conversionTargets'?'个目标组合':'条';
  $('#count').textContent=aggregateMode?`${displayRows.length} ${unit}（${visible.length} 条计划）`:`${displayRows.length} 条`;$('#pageLabel').textContent=`第 ${page} / ${pages} 页`;$('#prev').disabled=page<=1;$('#next').disabled=page>=pages;$('#export').disabled=!displayRows.length;
  document.dispatchEvent(new CustomEvent('bid:rendered'));
}
$('#fetch').onclick=async()=>{
  if(busy)return;setBusy(true);abort=new AbortController();
  try{
    const selected=dates(),payload={startDate:selected.start,endDate:selected.end,createdStart:$('#createdStart').value,createdEnd:$('#createdEnd').value};
    const prepared=await syncPrepareQuery(abort.signal);
    payload.expectedUserId=prepared.userId;payload.queryRevision=prepared.queryRevision;
    const current=today(),start=new Date(current+'T00:00:00Z');start.setUTCDate(start.getUTCDate()-3);
    const live=selected.start===current&&selected.end===current&&payload.createdStart===start.toISOString().slice(0,10)&&payload.createdEnd===current;
    if(live){
      message('正在读取并保存全部计划，完成后列表与机器人使用同一份快照…');
      const data=await api('/api/bid-monitor/server-sync/query-snapshot',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),signal:abort.signal});
      syncIdentity(data.userId);
      if(!data.snapshot?.updatedAt||!Array.isArray(data.snapshot.rows))throw Error('服务器未确认快照保存成功，请重新查询');
      receive(data.snapshot.rows,'已保存同步快照 '+new Date(data.snapshot.updatedAt).toLocaleString('zh-CN')+
        (data.snapshot.duplicateRows?` · 已去除 ${data.snapshot.duplicateRows} 条上游重复记录`:''),{start:data.snapshot.date,end:data.snapshot.date},true);
      syncStamp=data.snapshot.updatedAt;return;
    }
    let all=[],combinedTotal=0,duplicates=0;const ids=new Set();
    for(const platform of ['byte','gdt']){
      let total=null,received=0;const label=platform==='gdt'?'广点通':'字节';
      for(let p=1;total===null||received<total;p++){
        if(p>1000)throw Error(`${label}计划总数超过 100000 条，请缩小计划创建日期范围`);
        message(`正在读取${label}第 ${p} 页，已读取 ${received}${total===null?'':' / '+total} 条`);
        const data=await api('/api/bid-monitor/server-sync/page',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...payload,platform,page:p,total}),signal:abort.signal});
        const n=Number(data.total);
        if(data.total==null||!Number.isSafeInteger(n)||n<0)throw Error(`${label}接口总条数异常`);
        if(total!==null&&total!==n)throw Error(`${label}分页期间计划总数变化，请重试`);total=n;
        if(total>100000)throw Error(`${label}计划总数超过 100000 条，请缩小计划创建日期范围`);
        if(!Array.isArray(data.rows)||data.rows.length!==Math.min(100,Math.max(0,total-received)))throw Error(`${label}分页数据不完整，保留原有结果`);
        for(const row of data.rows){const r=B.normalize(row),id=`${platform}:${r.accountId}:${r.id}`;if(!r.id)throw Error(`${label}分页出现计划 ID 缺失，保留原有结果`);if(ids.has(id)){duplicates++;continue;}ids.add(id);all.push(row);}
        received+=data.rows.length;
      }
      combinedTotal+=total;
    }
    if(all.length>100000)throw Error('字节与广点通计划合计超过 100000 条，请缩小计划创建日期范围');
    if(!all.length)throw Error('字节和广点通均未返回计划，保留原有结果');
    receive(all,'创量查询 · 字节 + 广点通 · 全部 '+new Date().toLocaleTimeString('zh-CN')+(duplicates?` · 已去除 ${duplicates} 条上游重复记录`:'')+` · 上游 ${combinedTotal} 条 · 计划创建 ${payload.createdStart||'不限'} 至 ${payload.createdEnd||'不限'}`,selected,live);
  }catch(error){message(error.name==='AbortError'?'已取消查询，原结果未改变':error.message,true);}finally{setBusy(false);}
};
$('#cancel').onclick=()=>abort?.abort();$('#import').onclick=()=>$('#file').click();
$('#file').onchange=async()=>{if(!$('#file').files.length||busy)return;setBusy(true);try{const selected=dates(),file=$('#file').files[0],form=new FormData();form.append('file',file);message('正在读取 Excel…');const data=await api('/api/bid-monitor/import',{method:'POST',body:form});receive(data.rows,`导入 ${file.name}`,selected);}catch(error){message(error.message,true);}finally{$('#file').value='';setBusy(false);}};
for(const id of ['search','viewMode','taskFilter','filter','dataQualityFilter','pageSize','platformFilter','appTypeFilter','deepBidTypeFilter','deepExternalActionFilter','externalActionFilter','statusFilter','deepCpaBidMin','deepCpaBidMax'])$('#'+id).addEventListener('input',()=>{if(id==='viewMode')selectedAccount=null;page=1;render();});
$('#columnSettings').addEventListener('change',event=>{
  const checkbox=event.target.closest('input[data-column]');if(!checkbox)return;
  checkbox.checked?visibleOptionalColumns.add(checkbox.dataset.column):visibleOptionalColumns.delete(checkbox.dataset.column);
  localStorage.setItem(optionalColumnStorage,JSON.stringify([...visibleOptionalColumns]));page=1;render();
});
$('#aggregateColumnChoices').onchange=()=>{const view=$('#viewMode').value;aggregateSettings[view]=[...document.querySelectorAll('[data-aggregate-column]:checked')].map(input=>input.dataset.aggregateColumn);saveAggregateSettings();render();};
$('#aggregateColumnReset').onclick=()=>{delete aggregateSettings[$('#viewMode').value];saveAggregateSettings();render();};
$('#rows').onclick=event=>{const button=event.target.closest('[data-account-key]');if(!button)return;selectedAccount={key:button.dataset.accountKey,label:button.dataset.accountLabel};$('#viewMode').value='plans';page=1;render();};
$('#accountDrillBack').onclick=()=>{selectedAccount=null;$('#viewMode').value='accounts';page=1;render();};
$('#tableHead').onclick=event=>{const button=event.target.closest('.sort-header');if(!button)return;const nextKey=button.dataset.sortKey;if(nextKey===sortKey)sortDirection=sortDirection==='desc'?'asc':'desc';else{sortKey=nextKey;sortDirection=textSortKeys.has(nextKey)?'asc':'desc';}page=1;render();};
for(const id of ['startDate','endDate','createdStart','createdEnd'])$('#'+id).onchange=()=>{if(raw.length)message('日期已修改，下方仍为原统计区间数据，请重新查询');};
$('#prev').onclick=()=>{page--;render();};$('#next').onclick=()=>{page++;render();};
$('#export').onclick=()=>{
  const cell=v=>'"'+String(v??'').replace(/^[=+@\-]/,"'$&").replaceAll('"','""')+'"';
  const viewMode=$('#viewMode').value,aggregateMode=viewMode!=='plans',dimensions=viewDimensions(viewMode);
  const visibleOptional=optionalColumns.filter(column=>column[1]!=='platform'&&visibleOptionalColumns.has(column[1]));
  const exportMetrics=currentAggregateColumns(viewMode);
  const exportLabel={todayPlans:'今日新上计划数',spendingPlans:'有消耗计划数',priced:'已匹配价格计划数',estimatedCompensation:'预估赔付金额',estimatedRoi:'预估ROI'};
  const priceSourceLabel=r=>({manual:'手动设置','daily-account':`${r.priceDate} 账户日报`,'daily-task':`${r.priceDate} 同任务日报参考`}[r.priceSource]||'');
  let rows=aggregateMode?[[...dimensions.map(d=>dimensionLabels[d]),'统计开始','统计结束',...exportMetrics.map(([label,key])=>exportLabel[key]||label)],
    ...aggregateRows.map(r=>[...dimensions.map(d=>r[d]),range.start,range.end,...exportMetrics.map(([,key])=>key==='estimatedRoi'?fmtRoi(r[key]):key==='bidProfitRate'?fmtPercent(r[key]):r[key])])]:[['计划ID','计划名称','平台','账户ID','账户名称','优化师','任务','任务来源','结算单价','单价来源','统计开始','统计结束','总消耗','转化数','注册数','回传比例','当前出价','佣金','预估赔付金额','预估ROI','规则赠款','现金消耗','盈亏线出价','出价利润率','gap','实际单价'],
    ...visible.map(r=>[r.id,r.name,r.platform,r.accountId,r.account,r.optimizer,r.task,r.taskSource==='daily-report'?'大航海日报任务名':r.taskSource==='plan-name'?'计划/账户名称识别':r.taskSource==='bid-return'?'出价×回传比例估算':r.taskSource==='inferred'?'历史结算单价反推':r.task?'账户名匹配':'',r.basePrice,priceSourceLabel(r),range.start,range.end,r.cost,r.conversions,r.registrations,r.ratio,r.bid,r.commission,r.estimatedCompensation,fmtRoi(r.estimatedRoi),r.grant,r.cashCost,r.breakEvenBid,fmtPercent(r.bidProfitRate),r.gap,r.price,...visibleOptional.map(column=>r[column[1]])])];
  if(!aggregateMode)rows[0].push(...visibleOptional.map(column=>column[0]));
  if(!aggregateMode&&planDisplayKeys){const columns=activePlanColumns();rows=[['计划ID','账户ID','统计开始','统计结束',...columns.map(column=>column[0])],...visible.map(r=>[r.id,r.accountId,range.start,range.end,...columns.map(([,key])=>['ratio','bidProfitRate'].includes(key)?fmtPercent(r[key]):['estimatedRoi','gap'].includes(key)?fmtRoi(r[key]):r[key])])];}
  const labels={plans:'计划明细',accounts:'账户汇总',optimizers:'优化师汇总',tasks:'任务汇总',optimizerTasks:'优化师任务汇总',conversionTargets:'转化目标组合汇总'};
  const url=URL.createObjectURL(new Blob(['\ufeff'+rows.map(row=>row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`出价监测_${labels[viewMode]}_${range.start}_${range.end}.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
(async()=>{try{const session=await api('/api/session');if(!session.authenticated){location.replace('/login');return;}const permissions=await api('/api/tool-visibility');if(permissions.bidMonitor!==true){location.replace('/tools');return;}const shared=await api('/api/bid-monitor/shared-report',{signal:AbortSignal.timeout(20000)});bidSharedAccess(shared);document.body.classList.add('ready');render();await bidApplyShared(shared);}catch(error){document.body.classList.add('ready');message(error.message,true);}})();

$('#gapReload').onclick=()=>void loadGap();

// Only report fields are exposed to the assistant; configuration credentials stay out.
window.getPetReportContext=()=>{
  const fields={id:'计划ID',name:'计划',platform:'平台',accountId:'账户ID',account:'账户',optimizer:'优化师',task:'任务',priceSource:'单价来源',cost:'消耗',conversions:'转化数',registrations:'注册数',commission:'佣金',cashCost:'现金消耗',profit:'现金利润',estimatedRoi:'预估ROI',bidProfitRate:'出价利润率',bid:'当前出价',gap:'gap',basePrice:'结算单价',price:'实际单价',externalAction:'转化目标',deepExternalAction:'深度转化目标',appType:'应用类型',plans:'计划数',accounts:'账户数',priced:'价格匹配计划数'};
  const pick=row=>{const profit=row.pricedCashCost!==undefined?row.profit:Number.isFinite(row.commission)&&Number.isFinite(row.cashCost)?row.commission-row.cashCost:null;const item={...row,profit};return Object.fromEntries(Object.entries(fields).filter(([key])=>item[key]!==undefined).map(([key,label])=>[label,item[key]]));};
  const ranked=[...visible].sort((a,b)=>(b.cost||0)-(a.cost||0));
  const totals=B.aggregateGroups(visible,[],today())[0]||{plans:0,accounts:0,cost:0,conversions:0,registrations:0,priced:0};
  const filters=Object.fromEntries(['search','taskFilter','filter','platformFilter','appTypeFilter','deepBidTypeFilter','deepExternalActionFilter','externalActionFilter','statusFilter','deepCpaBidMin','deepCpaBidMax'].map(id=>[id,$('#'+id).value]));
  return {mode:'bid',reportType:'出价监测',range:range?[range.start,range.end]:[],loaded:!!range,source,
    filters:JSON.stringify({...filters,account:selectedAccount?.label||''}),view:$('#viewMode').value,
    summary:pick(totals),plans:ranked.slice(0,30).map(pick),
    anomalies:ranked.filter(row=>row.bidProfitRate!==null&&row.bidProfitRate<0||row.estimatedRoi!==null&&row.estimatedRoi<1||row.cost>0&&!row.registrations).slice(0,20).map(pick),
    gapRange:gapData?[gapData.start,gapData.end]:[],unpriced:visible.filter(row=>row.price===null).length};
};
