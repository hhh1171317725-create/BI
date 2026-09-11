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
  const make=(tag,className='',text='')=>{const element=document.createElement(tag);element.className=className;if(text)element.textContent=text;return element;};
  const dimensionSelect=byId('strategyDimension'),dimensionLabel=dimensionSelect.closest('label'),dimensionTabs=make('div','strategy-dimension-tabs');
  dimensionTabs.setAttribute('role','tablist');dimensionTabs.setAttribute('aria-label','常用策略汇总维度');dimensionLabel.firstChild.textContent='更多维度';
  for(const [value,label] of [['accounts','账户'],['dates','日期'],['optimizers','优化师'],['tasks','任务'],['platforms','平台']]){const button=make('button','strategy-dimension-tab',label);button.type='button';button.dataset.dimension=value;button.setAttribute('role','tab');button.onclick=()=>{dimensionSelect.value=value;dimensionSelect.dispatchEvent(new Event('change',{bubbles:true}));};dimensionTabs.append(button);}
  dimensionLabel.before(dimensionTabs);
  function syncDimensionTabs(){for(const button of dimensionTabs.children){const active=button.dataset.dimension===dimensionSelect.value;button.classList.toggle('is-active',active);button.setAttribute('aria-selected',String(active));button.tabIndex=active?0:-1;}}
  const addDays=(iso,days)=>{const date=new Date(iso+'T00:00:00Z');date.setUTCDate(date.getUTCDate()+days);return date.toISOString().slice(0,10);};
  const addMonths=(iso,months)=>{const [year,month]=iso.split('-').map(Number),date=new Date(Date.UTC(year,month-1+months,1));return date.toISOString().slice(0,7)+'-01';};
  const historyFields=byId('strategyHistoryFields'),historyStart=byId('strategyHistoryStart'),historyEnd=byId('strategyHistoryEnd');
  const rangeField=make('div','ocean-range-field strategy-range-field'),rangeButton=make('button','ocean-range-button');
  rangeButton.type='button';rangeButton.id='strategyRangeButton';rangeButton.setAttribute('aria-haspopup','dialog');rangeButton.setAttribute('aria-expanded','false');
  const rangeText=make('span','ocean-range-text'),rangeIcon=make('span','ocean-range-icon');
  rangeIcon.innerHTML='<svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="2.5" y="4" width="15" height="13.5" rx="2"/><path d="M6 2v4M14 2v4M2.5 8h15"/></svg>';rangeIcon.setAttribute('aria-hidden','true');rangeButton.append(rangeText,rangeIcon);rangeField.append(rangeButton);historyFields.prepend(rangeField);
  const rangePopover=make('div','ocean-range-popover');rangePopover.id='strategyRangePicker';rangePopover.hidden=true;rangePopover.setAttribute('role','dialog');rangePopover.setAttribute('aria-label','选择策略数据日期范围');
  const rangePresets=make('div','ocean-range-presets'),calendarPane=make('div','ocean-calendar-pane'),calendarHeader=make('div','ocean-calendar-header'),calendarMonths=make('div','ocean-calendar-months'),calendarFooter=make('div','ocean-calendar-footer');
  let draftStart='',draftEnd='',calendarMonth=today().slice(0,7)+'-01';
  const navButton=(label,textValue,offset)=>{const button=make('button','ocean-calendar-nav',textValue);button.type='button';button.setAttribute('aria-label',label);button.onclick=()=>{calendarMonth=addMonths(calendarMonth,offset);renderCalendar();};return button;};
  const prevYear=navButton('上一年','«',-12),prevMonth=navButton('上个月','‹',-1),monthTitles=make('div','ocean-calendar-titles'),nextMonth=navButton('下个月','›',1),nextYear=navButton('下一年','»',12);
  calendarHeader.append(prevYear,prevMonth,monthTitles,nextMonth,nextYear);calendarPane.append(calendarHeader,calendarMonths,calendarFooter);rangePopover.append(rangePresets,calendarPane);document.body.append(rangePopover);
  const presetRanges=()=>{const current=today(),yesterday=addDays(current,-1),weekday=new Date(current+'T00:00:00Z').getUTCDay()||7;return [['今天',[current,current]],['昨天',[yesterday,yesterday]],['最近3天',[addDays(current,-2),current]],['最近7天',[addDays(current,-6),current]],['最近15天',[addDays(current,-14),current]],['最近30天',[addDays(current,-29),current]],['上周',[addDays(current,-weekday-6),addDays(current,-weekday)]],['本月',[current.slice(0,7)+'-01',current]]];};
  function chooseDate(date){
    if(date>today())return;
    if(!draftStart||draftEnd){draftStart=date;draftEnd='';}
    else if(date<draftStart){draftEnd=draftStart;draftStart=date;}else draftEnd=date;
    renderCalendar();
  }
  function buildMonth(month){
    const [year,monthNumber]=month.split('-').map(Number),section=make('section','ocean-calendar-month'),title=make('h3','',`${year}年 ${monthNumber}月`),week=make('div','ocean-calendar-week');
    for(const label of ['日','一','二','三','四','五','六'])week.append(make('span','',label));
    const grid=make('div','ocean-calendar-grid'),first=new Date(Date.UTC(year,monthNumber-1,1)),start=new Date(first);start.setUTCDate(1-first.getUTCDay());
    for(let index=0;index<42;index++){
      const cursor=new Date(start);cursor.setUTCDate(start.getUTCDate()+index);const date=cursor.toISOString().slice(0,10),button=make('button','ocean-calendar-day',String(cursor.getUTCDate()));button.type='button';button.dataset.date=date;
      const outside=cursor.getUTCMonth()!==monthNumber-1,future=date>today(),edge=date===draftStart||date===draftEnd,inside=draftEnd&&date>draftStart&&date<draftEnd;
      button.classList.toggle('is-outside',outside);button.classList.toggle('is-edge',edge);button.classList.toggle('is-in-range',Boolean(inside));button.classList.toggle('is-today',date===today());button.disabled=future;button.setAttribute('aria-label',`${date}${date===today()?'，今天':''}`);if(edge)button.setAttribute('aria-pressed','true');button.onclick=()=>chooseDate(date);grid.append(button);
    }
    section.append(title,week,grid);return section;
  }
  function renderCalendar(){
    const second=addMonths(calendarMonth,1);monthTitles.innerHTML=`<strong>${calendarMonth.slice(0,4)}年 ${Number(calendarMonth.slice(5,7))}月</strong><strong>${second.slice(0,4)}年 ${Number(second.slice(5,7))}月</strong>`;calendarMonths.replaceChildren(buildMonth(calendarMonth),buildMonth(second));
    rangePresets.replaceChildren();for(const [label,range] of presetRanges()){const button=make('button','ocean-range-preset',label);button.type='button';button.classList.toggle('is-active',draftStart===range[0]&&draftEnd===range[1]);button.onclick=()=>{draftStart=range[0];draftEnd=range[1];calendarMonth=range[0].slice(0,7)+'-01';renderCalendar();};rangePresets.append(button);}
    const selection=make('span','ocean-range-selection',draftEnd?`${draftStart} ~ ${draftEnd}`:draftStart?`已选 ${draftStart}，请选择结束日期`:'请选择开始日期'),cancel=make('button','','取消'),apply=make('button','primary','确定');cancel.type=apply.type='button';apply.id='strategyRangeApply';apply.disabled=!draftStart||!draftEnd;cancel.onclick=closeRange;apply.onclick=()=>{historyStart.value=draftStart;historyEnd.value=draftEnd;syncRangeButton();closeRange();void loadHistory();};calendarFooter.replaceChildren(selection,cancel,apply);
  }
  function positionRange(){if(rangePopover.hidden)return;const rect=rangeButton.getBoundingClientRect(),width=Math.min(760,window.innerWidth-16),left=Math.max(8,Math.min(rect.right-width,window.innerWidth-width-8));rangePopover.style.width=`${width}px`;rangePopover.style.left=`${left}px`;rangePopover.style.top=`${Math.min(rect.bottom+6,window.innerHeight-rangePopover.offsetHeight-8)}px`;}
  function openRange(){draftStart=historyStart.value;draftEnd=historyEnd.value;calendarMonth=(draftStart||today()).slice(0,7)+'-01';renderCalendar();rangePopover.hidden=false;rangeButton.setAttribute('aria-expanded','true');positionRange();rangePopover.querySelector('.ocean-range-preset.is-active,.ocean-calendar-day.is-edge')?.focus();}
  function closeRange(){rangePopover.hidden=true;rangeButton.setAttribute('aria-expanded','false');rangeButton.focus({preventScroll:true});}
  function syncRangeButton(){rangeText.textContent=historyStart.value&&historyEnd.value?`${historyStart.value}  ~  ${historyEnd.value}`:'选择日期范围';rangeButton.title='点击选择策略测试的数据日期范围';}
  rangeButton.onclick=()=>rangePopover.hidden?openRange():closeRange();
  document.addEventListener('pointerdown',event=>{if(!rangePopover.hidden&&!rangePopover.contains(event.target)&&!rangeButton.contains(event.target))closeRange();});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!rangePopover.hidden){event.preventDefault();closeRange();}});
  window.addEventListener('resize',positionRange);window.addEventListener('scroll',positionRange,true);syncRangeButton();
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
    setDataStatus(data.range?`跟随当前报表 ${data.range.start} 至 ${data.range.end} · ${(data.rows||[]).length} 条计划`:'当前报表尚无数据');
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
    refreshDataStatus();syncDimensionTabs();
    const strategy=strategies.find(item=>item.id===selectedId);if(!strategy){detail.innerHTML='<div class="strategy-empty">请选择一个策略查看数据</div>';return;}
    const data=selectedData(),keys=new Set((strategy.accounts||[]).map(account=>account.key)),dailyRows=(data.rows||[]).filter(row=>keys.has(B.accountIdentity(row))),planRows=B.mergePlanRows(dailyRows);
    const total=B.aggregateGroups(planRows,[],today())[0]||{plans:0,accounts:0,spendingPlans:0,cost:0,conversions:0,registrations:0,ratio:null,priced:0,commission:null,estimatedCompensation:null,cashCost:null,profit:null,estimatedRoi:null};
    const selectedDimension=byId('strategyDimension').value,groupDimensions=dimensions[selectedDimension]||dimensions.accounts,rows=groupDimensions.includes('statDate')?dailyRows:planRows,groups=B.aggregateGroups(rows,groupDimensions,today());
    const matched=new Set(dailyRows.map(row=>B.accountIdentity(row))).size,range=data.range,hasFinancial=planRows.some(row=>row.price!==null);
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
    historyLoading=true;button.disabled=true;rangeButton.disabled=true;refreshDataStatus();let failure='',warning='';
    try{
      const data=await window.loadBidHistoryRange(start,end);if(!Array.isArray(data.rows))throw Error('日期范围接口返回格式异常');
      historyRaw=data.rows.map(B.normalize);historyRange={start:data.startDate||start,end:data.endDate||end};historyReferences=null;draw();
      try{historyReferences=await window.loadBidHistoricalReferences?.(historyRaw);draw();if(!historyReferences?.complete)warning=`历史 ${historyRange.start} 至 ${historyRange.end} · ${historyRaw.length} 条计划日数据 · ${historyReferences?.size||0} / ${historyReferences?.totalDates||0} 个日期已关联收益口径`;}catch{warning=`历史 ${historyRange.start} 至 ${historyRange.end} · ${historyRaw.length} 条计划日数据 · 逐日任务、单价与 gap 关联暂不可用`;}
    }catch(error){failure='历史数据读取失败：'+error.message;setDataStatus(failure,true);}
    finally{historyLoading=false;button.disabled=false;rangeButton.disabled=false;if(warning)setDataStatus(warning,true);else if(!failure)refreshDataStatus();}
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
  byId('strategyHistoryLoad').onclick=()=>void loadHistory();dimensionSelect.onchange=()=>drawDetail();
  byId('strategyAccountSearch').oninput=event=>{const term=event.target.value.trim().toLowerCase();for(const label of byId('strategyAccountList').querySelectorAll('.strategy-account-choice'))label.hidden=Boolean(term)&&!label.dataset.search.includes(term);updateEditorCount();};
  byId('strategySelectVisible').onclick=()=>{for(const label of byId('strategyAccountList').querySelectorAll('.strategy-account-choice:not([hidden])')){const box=label.querySelector('input');box.checked=true;draftAccounts.add(box.value);}updateEditorCount();};
  byId('strategyClearAccounts').onclick=()=>{draftAccounts.clear();byId('strategyAccountList').querySelectorAll('input').forEach(box=>box.checked=false);updateEditorCount();};
  byId('strategySave').onclick=()=>{const name=byId('strategyName').value.trim(),note=byId('strategyNote').value.trim(),accounts=accountPayload();if(!name){byId('strategyEditorError').textContent='请填写策略名称';byId('strategyName').focus();return;}if(!accounts.length){byId('strategyEditorError').textContent='请至少选择一个账户';return;}const id=editor.dataset.strategyId||('strategy-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,8)),next={id,name,note,accounts},index=strategies.findIndex(item=>item.id===id);const updated=[...strategies];if(index<0)updated.push(next);else updated[index]=next;selectedId=id;void saveAll(updated,'策略已保存，数据会随快照自动更新。');};
  byId('strategyDelete').onclick=()=>{const id=editor.dataset.strategyId,strategy=strategies.find(item=>item.id===id);if(!strategy||!confirm(`删除策略“${strategy.name}”？`))return;void saveAll(strategies.filter(item=>item.id!==id),'策略已删除。');};
  document.addEventListener('bid:strategies-shared',event=>applyBundle(event.detail));
  document.addEventListener('bid:rendered',()=>{if(byId('strategyDataSource').value==='current')drawDetail();});
  applyBundle(window.bidStrategyBundle);
})();
