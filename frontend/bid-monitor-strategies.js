'use strict';
(() => {
  const byId=id=>document.getElementById(id),B=window.BidMonitor;
  const html=value=>String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const number=value=>Number.isFinite(value)?Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}):'--';
  const roi=value=>Number.isFinite(value)?Number(value).toLocaleString('zh-CN',{minimumFractionDigits:3,maximumFractionDigits:3}):'--';
  const percent=value=>Number.isFinite(value)?number(value*100)+'%':'--';
  const today=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
  const offsetDate=days=>{const date=new Date(today()+'T00:00:00Z');date.setUTCDate(date.getUTCDate()+days);return date.toISOString().slice(0,10);};
  const editor=byId('strategyEditor'),list=byId('strategyList'),detail=byId('strategyDetail'),status=byId('strategyStatus');
  const dimensions={
    accounts:['platform','account','accountId'],dates:['statDate'],optimizers:['optimizer'],tasks:['task'],platforms:['platform'],conversionTargets:['externalAction','deepExternalAction','appType'],
    dateAccounts:['statDate','platform','account','accountId'],dateOptimizers:['statDate','optimizer'],dateTasks:['statDate','task'],datePlatforms:['statDate','platform'],dateConversionTargets:['statDate','externalAction','deepExternalAction','appType']
  };
  const dimensionLabels={statDate:'数据日期',platform:'平台',account:'账户名称',accountId:'账户 ID',optimizer:'优化师',task:'任务',externalAction:'转化目标',deepExternalAction:'深度转化目标',appType:'应用类型'};
  let strategies=[],revision='',userId='',canManage=false,selectedId='',draftAccounts=new Set(),saving=false;
  let historyRaw=[],historyRange=null,historyReferences=null,historyLoading=false;

  byId('strategyHistoryStart').value=offsetDate(-7);byId('strategyHistoryEnd').value=offsetDate(-1);
  function currentData(){return window.getBidStrategyData?.()||{rows:[],range:null,source:''};}
  function selectedData(){
    if(byId('strategyDataSource').value!=='history')return currentData();
    const rows=window.analyzeBidStrategyRows?.(historyRaw,historyReferences)||historyRaw;
    return{rows,range:historyRange,source:'每日 00:30 历史归档',historical:true};
  }
  function allCatalogRows(){const current=currentData().rows||[],selected=selectedData().rows||[];return current===selected?current:[...current,...selected];}
  function catalog(extra=[]){
    const entries=new Map();
    for(const row of allCatalogRows()){
      const key=B.accountIdentity(row),existing=entries.get(key)||{key,label:row.account||'账户名称缺失',accountId:row.accountId||'',platform:row.platform||'平台缺失',cost:0,plans:0,optimizers:new Set()};
      existing.cost+=Number.isFinite(row.cost)?row.cost:0;existing.plans++;if(row.optimizer)existing.optimizers.add(row.optimizer);entries.set(key,existing);
    }
    for(const account of extra||[])if(account?.key&&!entries.has(account.key))entries.set(account.key,{...account,cost:0,plans:0,optimizers:new Set(),missing:true});
    return [...entries.values()].sort((a,b)=>b.cost-a.cost||a.label.localeCompare(b.label,'zh-CN'));
  }
  function setStatus(text,bad=false){status.textContent=text;status.className=bad?'error':'muted';}
  function setDataStatus(text,bad=false){const target=byId('strategyDataStatus');target.textContent=text;target.className=bad?'error':'muted';}
  function refreshDataStatus(){
    const data=selectedData();
    if(data.historical){const coverage=historyReferences instanceof Map?` · ${historyReferences.size} / ${historyReferences.totalDates} 个日期已关联收益口径`:'';setDataStatus(historyLoading?'正在读取历史归档与逐日收益口径…':historyRange?`${historyRange.end===today()?'历史 + 今日实时':'历史'} ${historyRange.start} 至 ${historyRange.end} · ${historyRaw.length} 条计划日数据${coverage}`:'请选择日期读取历史归档');return;}
    setDataStatus(data.range?`当前报表 ${data.range.start} 至 ${data.range.end} · ${(data.rows||[]).length} 条计划`:'当前报表尚无数据');
  }
  function applyBundle(bundle){
    if(!bundle)return;userId=String(bundle.userId||'');canManage=Boolean(bundle.canManage);strategies=Array.isArray(bundle.strategies)?bundle.strategies:[];revision=bundle.revision||'';
    if(!strategies.some(item=>item.id===selectedId))selectedId=strategies[0]?.id||'';
    setStatus(strategies.length?`${strategies.length} 个策略 · 点击策略查看对应账户数据`:(canManage?'还没有测试策略，点击“新建策略”开始配置。':'管理员还没有配置测试策略。'));
    draw();
  }
  function metric(label,value,note=''){return `<div class="strategy-metric"><span>${html(label)}</span><strong>${html(value)}</strong>${note?`<small>${html(note)}</small>`:''}</div>`;}
  function drawList(){
    list.innerHTML=strategies.map(strategy=>`<article class="strategy-card${strategy.id===selectedId?' is-active':''}"><button class="strategy-card-main" type="button" data-strategy-open="${html(strategy.id)}"><strong>${html(strategy.name)}</strong><small>${(strategy.accounts||[]).length} 个账户${strategy.note?' · '+html(strategy.note):''}</small></button>${canManage?`<button class="strategy-card-edit" type="button" data-strategy-edit="${html(strategy.id)}" aria-label="编辑${html(strategy.name)}">编辑</button>`:''}</article>`).join('')||'<div class="strategy-empty">暂无测试策略</div>';
  }
  function dimensionCell(row,key){return `<td class="dimension-cell">${html(row[key])}</td>`;}
  function drawDetail(){
    refreshDataStatus();
    const strategy=strategies.find(item=>item.id===selectedId);if(!strategy){detail.innerHTML='<div class="strategy-empty">请选择一个策略查看数据</div>';return;}
    const data=selectedData(),keys=new Set((strategy.accounts||[]).map(account=>account.key)),rows=(data.rows||[]).filter(row=>keys.has(B.accountIdentity(row)));
    const total=B.aggregateGroups(rows,[],today())[0]||{plans:0,accounts:0,spendingPlans:0,cost:0,conversions:0,registrations:0,ratio:null,priced:0,commission:null,estimatedCompensation:null,cashCost:null,profit:null,estimatedRoi:null};
    const selectedDimension=byId('strategyDimension').value,groupDimensions=dimensions[selectedDimension]||dimensions.accounts,groups=B.aggregateGroups(rows,groupDimensions,today());
    const matched=new Set(rows.map(row=>B.accountIdentity(row))).size,range=data.range,hasFinancial=rows.some(row=>row.price!==null);
    const metricHtml=metric('总消耗',number(total.cost),`${total.plans} 条计划`)+metric('预估赔付',number(total.estimatedCompensation))+metric('现金消耗',number(total.cashCost))+metric('转化数',number(total.conversions))+metric('注册数',number(total.registrations))+metric('回传比例',percent(total.ratio))+metric('账户数',String(total.accounts))+metric('有消耗计划',String(total.spendingPlans||0))+(hasFinancial?metric('预估佣金',number(total.commission),`${total.priced} / ${total.plans} 条计划可计算`)+metric('预估 ROI',roi(total.estimatedRoi)):'');
    const financialColumns=hasFinancial?[['预估佣金','commission',number],['预估 ROI','estimatedRoi',roi]]:[],metricColumns=[['计划数','plans',String],['账户数','accounts',String],['总消耗','cost',number],['转化数','conversions',number],['注册数','registrations',number],['回传比例','ratio',percent],['预估赔付','estimatedCompensation',number],['现金消耗','cashCost',number],...financialColumns];
    const table=rows.length?`<div class="table-wrap strategy-table"><table><thead><tr>${groupDimensions.map(key=>`<th>${html(dimensionLabels[key])}</th>`).join('')}${metricColumns.map(([label])=>`<th>${html(label)}</th>`).join('')}</tr></thead><tbody>${groups.map(group=>`<tr>${groupDimensions.map(key=>dimensionCell(group,key)).join('')}${metricColumns.map(([,key,format])=>`<td>${html(format(group[key]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`:`<div class="strategy-empty">${data.historical?'所选历史日期没有这些账户的数据。':'当前报表没有这些账户的数据，请先读取最新快照。'}</div>`;
    const historyNote=data.historical?(historyReferences instanceof Map&&historyReferences.complete?'历史策略数据已按每个数据日期分别关联任务、单价与 gap，佣金、赔付和 ROI 使用同一套逐日口径。':'历史投放数据已显示，正在关联各数据日期的任务、单价与 gap。'):'';
    detail.innerHTML=`<div class="strategy-detail-head"><div><h3>${html(strategy.name)}</h3><p class="muted">${html(strategy.note||'未填写测试说明')}${range?` · 数据 ${html(range.start)} 至 ${html(range.end)}`:''}</p></div><span class="strategy-coverage">${matched} / ${keys.size} 个账户有数据</span></div><div class="strategy-metrics">${metricHtml}</div>${historyNote?`<p class="muted strategy-financial-note">${html(historyNote)}</p>`:''}${table}`;
  }
  function draw(){drawList();drawDetail();}

  async function loadHistory(){
    if(historyLoading)return;const start=byId('strategyHistoryStart').value,end=byId('strategyHistoryEnd').value,button=byId('strategyHistoryLoad');
    if(!start||!end||start>end||end>today()){setDataStatus('请选择不晚于今天的有效日期范围',true);return;}
    historyLoading=true;button.disabled=true;refreshDataStatus();let failure='',warning='';
    try{
      const data=await window.loadBidHistoryRange(start,end);if(!Array.isArray(data.rows))throw Error('日期范围接口返回格式异常');
      historyRaw=data.rows.map(B.normalize);historyRange={start:data.startDate||start,end:data.endDate||end};historyReferences=null;draw();
      try{historyReferences=await window.loadBidHistoricalReferences?.(historyRaw);draw();if(!historyReferences?.complete)warning=`历史 ${historyRange.start} 至 ${historyRange.end} · ${historyRaw.length} 条计划日数据 · ${historyReferences?.size||0} / ${historyReferences?.totalDates||0} 个日期已关联收益口径`;}catch{warning=`历史 ${historyRange.start} 至 ${historyRange.end} · ${historyRaw.length} 条计划日数据 · 逐日任务、单价与 gap 关联暂不可用`;}
    }catch(error){failure='历史数据读取失败：'+error.message;setDataStatus(failure,true);}
    finally{historyLoading=false;button.disabled=false;if(warning)setDataStatus(warning,true);else if(!failure)refreshDataStatus();}
  }
  function updateEditorCount(){
    const visible=[...byId('strategyAccountList').querySelectorAll('.strategy-account-choice:not([hidden])')];
    byId('strategyEditorCount').textContent=`已选择 ${draftAccounts.size} 个账户 · 当前显示 ${visible.length} 个`;
  }
  function drawAccounts(extra=[]){
    const rows=catalog(extra);byId('strategyAccountList').innerHTML=rows.map(account=>`<label class="strategy-account-choice" data-search="${html([account.label,account.accountId,account.platform,...account.optimizers].join(' ').toLowerCase())}"><input type="checkbox" value="${html(account.key)}" ${draftAccounts.has(account.key)?'checked':''}><span>${html(account.label)}<small>${html(account.platform)} · ${html(account.accountId||'无账户ID')} · ${account.plans} 条计划 · 消耗 ${number(account.cost)}${account.missing?' · 当前无数据':''}</small></span></label>`).join('')||'<div class="strategy-empty">请先读取计划快照，再选择账户。</div>';
    byId('strategyAccountList').querySelectorAll('input[type="checkbox"]').forEach(box=>box.onchange=()=>{box.checked?draftAccounts.add(box.value):draftAccounts.delete(box.value);updateEditorCount();});updateEditorCount();
  }
  function openEditor(id=''){
    if(!canManage)return;const strategy=strategies.find(item=>item.id===id);editor.dataset.strategyId=strategy?.id||'';byId('strategyEditorTitle').textContent=strategy?'编辑策略':'新建策略';byId('strategyName').value=strategy?.name||'';byId('strategyNote').value=strategy?.note||'';byId('strategyAccountSearch').value='';byId('strategyEditorError').textContent='';byId('strategyDelete').hidden=!strategy;draftAccounts=new Set((strategy?.accounts||[]).map(account=>account.key));drawAccounts(strategy?.accounts);editor.showModal();byId('strategyName').focus();
  }
  function accountPayload(){const choices=catalog(strategies.flatMap(strategy=>strategy.accounts||[])),byKey=new Map(choices.map(account=>[account.key,account]));return [...draftAccounts].map(key=>{const account=byKey.get(key);return{key,label:account?.label||'未知账户',accountId:account?.accountId||'',platform:account?.platform||''};});}
  async function saveAll(next,success){
    if(saving)return;saving=true;byId('strategySave').disabled=true;byId('strategyDelete').disabled=true;byId('strategyEditorError').textContent='';
    try{
      const response=await fetch('/api/bid-monitor/server-sync/strategies',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedUserId:userId,revision,strategies:next}),signal:AbortSignal.timeout(20000)}),text=await response.text();let data;try{data=JSON.parse(text);}catch{throw Error(`接口 HTTP ${response.status}，未返回 JSON`);}if(!response.ok)throw Error(data.error||data.message||`HTTP ${response.status}`);
      strategies=data.strategies||[];revision=data.revision||'';window.bidStrategyBundle={userId,canManage,strategies,revision};if(!strategies.some(item=>item.id===selectedId))selectedId=strategies[0]?.id||'';editor.close();setStatus(success);draw();
    }catch(error){byId('strategyEditorError').textContent=error.message;}finally{saving=false;byId('strategySave').disabled=false;byId('strategyDelete').disabled=false;}
  }

  list.onclick=event=>{const edit=event.target.closest('[data-strategy-edit]'),open=event.target.closest('[data-strategy-open]');if(edit){openEditor(edit.dataset.strategyEdit);return;}if(open){selectedId=open.dataset.strategyOpen;draw();}};
  byId('strategyAdd').onclick=()=>openEditor();byId('strategyEditorClose').onclick=()=>editor.close();byId('strategyEditorCancel').onclick=()=>editor.close();
  byId('strategyDataSource').onchange=()=>{const historical=byId('strategyDataSource').value==='history';byId('strategyHistoryFields').hidden=!historical;if(historical&&!historyRange)void loadHistory();else drawDetail();};
  byId('strategyHistoryLoad').onclick=()=>void loadHistory();byId('strategyDimension').onchange=()=>drawDetail();
  byId('strategyAccountSearch').oninput=event=>{const term=event.target.value.trim().toLowerCase();for(const label of byId('strategyAccountList').querySelectorAll('.strategy-account-choice'))label.hidden=Boolean(term)&&!label.dataset.search.includes(term);updateEditorCount();};
  byId('strategySelectVisible').onclick=()=>{for(const label of byId('strategyAccountList').querySelectorAll('.strategy-account-choice:not([hidden])')){const box=label.querySelector('input');box.checked=true;draftAccounts.add(box.value);}updateEditorCount();};
  byId('strategyClearAccounts').onclick=()=>{draftAccounts.clear();byId('strategyAccountList').querySelectorAll('input').forEach(box=>box.checked=false);updateEditorCount();};
  byId('strategySave').onclick=()=>{const name=byId('strategyName').value.trim(),note=byId('strategyNote').value.trim(),accounts=accountPayload();if(!name){byId('strategyEditorError').textContent='请填写策略名称';byId('strategyName').focus();return;}if(!accounts.length){byId('strategyEditorError').textContent='请至少选择一个账户';return;}const id=editor.dataset.strategyId||('strategy-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,8)),next={id,name,note,accounts},index=strategies.findIndex(item=>item.id===id);const updated=[...strategies];if(index<0)updated.push(next);else updated[index]=next;selectedId=id;void saveAll(updated,'策略已保存，数据会随快照自动更新。');};
  byId('strategyDelete').onclick=()=>{const id=editor.dataset.strategyId,strategy=strategies.find(item=>item.id===id);if(!strategy||!confirm(`删除策略“${strategy.name}”？`))return;void saveAll(strategies.filter(item=>item.id!==id),'策略已删除。');};
  document.addEventListener('bid:strategies-shared',event=>applyBundle(event.detail));
  document.addEventListener('bid:rendered',()=>{if(byId('strategyDataSource').value==='current')drawDetail();});
  applyBundle(window.bidStrategyBundle);
})();
