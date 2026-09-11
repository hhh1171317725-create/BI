// Presentation controls only. Filtering and calculations stay in bid-monitor.js.
(() => {
  const report=document.getElementById('report');
  const toolbar=report.querySelector('.report-toolbar');
  const filters=[['search','关键词',''],['taskFilter','任务',''],['optimizerFilter','优化师',''],['platformFilter','平台',''],['appTypeFilter','应用类型',''],['deepBidTypeFilter','深度出价类型',''],['deepExternalActionFilter','深度转化目标',''],['externalActionFilter','转化目标',''],['statusFilter','计划状态',''],['deepCpaBidMin','深度 CPA 最低',''],['deepCpaBidMax','深度 CPA 最高','']];
  const make=(tag,cls,text)=>{const el=document.createElement(tag);el.className=cls;if(text)el.textContent=text;return el;};
  for(const [id,label] of [['search','搜索计划 / 账户 / 优化师'],['viewMode','统计维度'],['taskFilterButton','任务'],['optimizerFilterButton','优化师'],['platformFilter','投放平台']]){
    const input=document.getElementById(id),wrapper=make('label','workspace-field',label);
    if(id==='search')wrapper.classList.add('workspace-search');
    input.before(wrapper);wrapper.append(input);
  }
  const searchHint=make('small','batch-search-hint','批量查找：多个计划 / 账户 ID 用空格或逗号分隔');searchHint.id='batchSearchHint';
  document.getElementById('search').after(searchHint);
  document.getElementById('search').setAttribute('aria-describedby','batchSearchHint');
  const watch=make('button','ocean-watch','盯盘助手');watch.type='button';watch.id='oceanWatch';watch.onclick=()=>{const trigger=document.querySelector('.data-pet-toggle');if(trigger)trigger.click();else document.getElementById('message').textContent='数据助手正在加载，请稍后重试。';};toolbar.prepend(watch);
  const summary=make('div','filter-summary');summary.id='filterSummary';
  const chips=make('div','filter-chips');
  const reset=make('button','filter-reset','清除全部筛选');reset.type='button';reset.id='clearReportFilters';
  summary.append(chips,reset);toolbar.after(summary);
  const controlDeck=make('section','ocean-control-deck');controlDeck.setAttribute('aria-label','报表视图与筛选');
  const levelBar=make('div','ocean-level-bar'),levelTitle=make('span','ocean-level-title','数据层级'),levelTabs=make('div','ocean-level-tabs');levelTabs.setAttribute('role','tablist');levelTabs.setAttribute('aria-label','常用统计维度');
  const levelOptions=[['plans','计划'],['accounts','账户'],['optimizers','优化师'],['tasks','任务'],['dates','日期']];
  for(const [value,label] of levelOptions){const button=make('button','ocean-level-tab',label);button.type='button';button.dataset.view=value;button.setAttribute('role','tab');button.onclick=()=>{const select=document.getElementById('viewMode');if(select.value===value)return;select.value=value;select.dispatchEvent(new Event('input',{bubbles:true}));};levelTabs.append(button);}
  const levelHint=make('span','ocean-level-hint','常用维度一键切换，其他组合在“统计维度”中选择');levelBar.append(levelTitle,levelTabs,levelHint);
  toolbar.before(controlDeck);controlDeck.append(levelBar,toolbar,summary,document.getElementById('historyStatus'));
  const historyToolbar=document.getElementById('historyToolbar'),datePresets=make('span','ocean-date-presets');datePresets.setAttribute('role','group');datePresets.setAttribute('aria-label','快捷日期');
  const chinaDate=offset=>{const date=new Date(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())+'T00:00:00Z');date.setUTCDate(date.getUTCDate()+offset);return date.toISOString().slice(0,10);};
  const setHistory=(start,end)=>{document.getElementById('historyStart').value=start;document.getElementById('historyEnd').value=end;if(start&&end)document.getElementById('historyLoad').click();else document.getElementById('historyToday').click();};
  for(const [label,range] of [['实时',null],['昨天',[-1,-1]],['近 7 天',[-7,-1]]]){const button=make('button','ocean-date-preset',label);button.type='button';button.dataset.range=label;button.onclick=()=>range?setHistory(chinaDate(range[0]),chinaDate(range[1])):setHistory('','');datePresets.append(button);}
  historyToolbar.before(datePresets);
  function refresh(){
    chips.replaceChildren();let count=0;
    for(const [id,label,empty] of filters){
      const input=document.getElementById(id),values=input.multiple?[...input.selectedOptions].map(option=>option.textContent):[];if(input.multiple?values.length===0:input.value===empty)continue;count++;
      const value=input.multiple?(values.length<=2?values.join('、'):`${values.slice(0,2).join('、')}等 ${values.length} 项`):input.tagName==='SELECT'?input.selectedOptions[0]?.textContent:input.value;
      const chip=make('button','filter-chip',`${label}：${value} ×`);chip.type='button';chip.title=`清除${label}`;
      chip.setAttribute('aria-label',`清除${label}筛选`);
      chip.onclick=()=>{if(input.multiple)for(const option of input.options)option.selected=false;else input.value=empty;input.dispatchEvent(new Event('input',{bubbles:true}));const trigger=document.getElementById(id+'Button');if(trigger)trigger.focus();else input.focus();};chips.append(chip);
    }
    if(!count)chips.append(make('span','filter-placeholder','未设置筛选 · 当前展示已加载数据'));
    reset.hidden=!count;
    const empty=report.querySelector('#rows .empty');
    if(empty){
      const note=make('p','empty-guidance',count?'试试减少筛选条件；清除筛选不会重新请求数据。':'请先查询或导入计划；如果正在查看单个账户，可返回账户汇总。');
      empty.append(note);
      if(count){const clear=make('button','','清除筛选，查看结果');clear.type='button';clear.onclick=()=>reset.click();empty.append(clear);}
    }
    const sort=report.querySelector('th[aria-sort="ascending"],th[aria-sort="descending"]');
    const guidance=report.querySelector('.table-guidance');
    if(guidance)guidance.textContent=sort?`当前按${sort.textContent.trim()}${sort.getAttribute('aria-sort')==='ascending'?'升序':'降序'} · 点击表头切换排序`:'点击表头排序 · 横向滚动查看更多指标';
    const view=document.getElementById('viewMode').value;
    for(const button of levelTabs.children){const active=button.dataset.view===view;button.classList.toggle('is-active',active);button.setAttribute('aria-selected',String(active));button.tabIndex=active?0:-1;}
    const start=document.getElementById('historyStart').value,end=document.getElementById('historyEnd').value,yesterday=chinaDate(-1),weekStart=chinaDate(-7);
    for(const button of datePresets.children){const active=button.dataset.range==='实时'?!start&&!end:button.dataset.range==='昨天'?start===yesterday&&end===yesterday:start===weekStart&&end===yesterday;button.classList.toggle('is-active',active);button.setAttribute('aria-pressed',String(active));}
    enhanceSelection();drawTableSummary();
  }
  reset.onclick=()=>{
    filters.forEach(([id,,empty])=>{const input=document.getElementById(id);if(input.multiple)for(const option of input.options)option.selected=false;else input.value=empty;});
    // One event renders once; do not clear columns, sort order or account drilldown.
    document.getElementById('search').dispatchEvent(new Event('input',{bubbles:true}));
    document.getElementById('search').focus();
  };
  document.addEventListener('bid:rendered',refresh);
  const gapStrip=make('div','gap-strip');
  const gapStatus=document.getElementById('gapStatus');gapStatus.before(gapStrip);
  gapStrip.append(gapStatus,document.getElementById('gapReload'));
  const tools=make('div','table-tools');
  tools.append(make('span','table-guidance','点击表头排序 · 横向滚动查看更多指标'));
  const tableSummary=make('div','ocean-table-summary');tableSummary.setAttribute('aria-live','polite');
  const batchBar=make('div','ocean-batch-bar');batchBar.hidden=true;
  const batchCount=make('strong','','已选择 0 条'),batchExport=make('button','','导出已选'),batchClear=make('button','','清空选择');for(const button of [batchExport,batchClear])button.type='button';batchBar.append(batchCount,batchExport,batchClear);
  const batchToggle=make('button','','批量选择');batchToggle.type='button';batchToggle.id='batchSelect';batchToggle.setAttribute('aria-pressed','false');
  let selectionMode=false;const selectedRows=new Map();
  function selectedSnapshot(row){return [...row.cells].slice(1).map(cell=>cell.innerText.trim().replace(/\n+/g,' / '));}
  function updateBatch(){batchBar.hidden=!selectionMode;batchCount.textContent=`已选择 ${selectedRows.size} 条`;batchExport.disabled=!selectedRows.size;batchClear.disabled=!selectedRows.size;batchToggle.textContent=selectionMode?'退出批量选择':'批量选择';batchToggle.setAttribute('aria-pressed',String(selectionMode));}
  function enhanceSelection(){
    const planView=document.getElementById('viewMode').value==='plans';batchToggle.hidden=!planView;if(!planView&&selectionMode){selectionMode=false;selectedRows.clear();}
    const head=document.querySelector('#tableHead tr'),bodyRows=[...document.querySelectorAll('#rows tr')];if(!selectionMode||!planView){updateBatch();return;}
    if(head&&!head.querySelector('.ocean-select-cell')){const th=make('th','ocean-select-cell'),all=make('input');all.type='checkbox';all.setAttribute('aria-label','选择当前页全部计划');th.append(all);head.prepend(th);all.onchange=()=>{for(const box of document.querySelectorAll('#rows .ocean-row-select')){box.checked=all.checked;box.dispatchEvent(new Event('change'));}};}
    for(const row of bodyRows){const link=row.querySelector('.plan-detail-link');if(!link||row.querySelector('.ocean-select-cell'))continue;const id=link.parentElement.querySelector('small')?.textContent.trim()||link.textContent.trim(),td=make('td','ocean-select-cell'),box=make('input');box.type='checkbox';box.className='ocean-row-select';box.checked=selectedRows.has(id);box.setAttribute('aria-label',`选择计划 ${link.textContent.trim()}`);td.append(box);row.prepend(td);box.onchange=()=>{if(box.checked)selectedRows.set(id,selectedSnapshot(row));else selectedRows.delete(id);updateBatch();};if(box.checked)selectedRows.set(id,selectedSnapshot(row));}
    const boxes=[...document.querySelectorAll('#rows .ocean-row-select')],all=document.querySelector('#tableHead .ocean-select-cell input');if(all){all.checked=boxes.length>0&&boxes.every(box=>box.checked);all.indeterminate=boxes.some(box=>box.checked)&&!all.checked;}updateBatch();
  }
  function drawTableSummary(){const summary=window.getPetReportContext?.().summary||{},value=(key,digits=2)=>Number.isFinite(summary[key])?Number(summary[key]).toLocaleString('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits}):'--';tableSummary.innerHTML=`<span>当前结果</span><strong>${value('计划数',0)} 条计划</strong><span>消耗 <b>${value('消耗')}</b></span><span>转化 <b>${value('转化数')}</b></span><span>注册 <b>${value('注册数')}</b></span><span>预估 ROI <b>${value('预估ROI',3)}</b></span>`;}
  batchToggle.onclick=()=>{selectionMode=!selectionMode;if(!selectionMode)selectedRows.clear();enhanceSelection();};batchClear.onclick=()=>{selectedRows.clear();document.querySelectorAll('.ocean-row-select').forEach(box=>box.checked=false);enhanceSelection();};
  batchExport.onclick=()=>{if(!selectedRows.size)return;const headers=[...document.querySelectorAll('#tableHead th')].slice(1).map(th=>th.textContent.trim().replace(/[⇅↑↓]/g,'')),cell=value=>'"'+String(value??'').replace(/^[=+@\-]/,"'$&").replaceAll('"','""')+'"',csv=[headers,...selectedRows.values()].map(row=>row.map(cell).join(',')).join('\r\n'),url=URL.createObjectURL(new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=`出价监测_已选${selectedRows.size}条.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
  const density=make('button','','紧凑行距');density.type='button';density.id='tableDensity';
  let compact=false;try{compact=localStorage.getItem('bid.table.compact')==='true';}catch{}
  function setDensity(){report.classList.toggle('compact-table',compact);density.setAttribute('aria-pressed',String(compact));density.textContent=compact?'标准行距':'紧凑行距';}
  density.onclick=()=>{compact=!compact;setDensity();try{localStorage.setItem('bid.table.compact',String(compact));}catch{}};
  setDensity();
  const focus=make('button','','专注看表');focus.type='button';focus.id='tableFocus';focus.setAttribute('aria-pressed','false');
  let focused=false,scrollY=0;
  function setFocus(value){if(value===focused)return;focused=value;if(value)scrollY=window.scrollY;document.body.classList.toggle('bid-table-focus',value);focus.textContent=value?'退出专注（Esc）':'专注看表';focus.setAttribute('aria-pressed',String(value));window.scrollTo({top:value?0:scrollY,behavior:'instant'});if(!value)focus.focus({preventScroll:true});}
  focus.onclick=()=>setFocus(!focused);
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&focused&&!document.querySelector('dialog[open]')){e.preventDefault();setFocus(false);}});
  window.addEventListener('hashchange',()=>setFocus(false));
  tools.append(batchToggle,density,focus);const reportTable=report.querySelector('.table-wrap');reportTable.before(tableSummary,tools,batchBar);
  reportTable.setAttribute('aria-label','计划表现数据表，点击表头排序，可横向滚动');
  const legend=make('p','table-value-legend','数值 0 表示已取得零值；-- 表示缺失或不适用。点击计划名称可查看数据来源与计算依据。');
  reportTable.after(legend);
  const listCard=make('div','ocean-list-card');controlDeck.before(listCard);for(const element of [controlDeck,document.getElementById('columnSettings'),document.getElementById('accountDrill'),document.getElementById('aggregateSettings'),gapStrip,tableSummary,tools,batchBar,reportTable,legend,report.querySelector('.pager')])listCard.append(element);
  refresh();
})();
