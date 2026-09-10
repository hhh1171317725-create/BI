// Presentation controls only. Filtering and calculations stay in bid-monitor.js.
(() => {
  const report=document.getElementById('report');
  const toolbar=report.querySelector('.report-toolbar');
  const filters=[['search','关键词',''],['taskFilter','任务',''],['platformFilter','平台',''],['appTypeFilter','应用类型',''],['deepBidTypeFilter','深度出价类型',''],['deepExternalActionFilter','深度转化目标',''],['externalActionFilter','转化目标',''],['statusFilter','计划状态',''],['deepCpaBidMin','深度 CPA 最低',''],['deepCpaBidMax','深度 CPA 最高','']];
  const make=(tag,cls,text)=>{const el=document.createElement(tag);el.className=cls;if(text)el.textContent=text;return el;};
  for(const [id,label] of [['search','搜索计划 / 账户 / 优化师'],['viewMode','统计维度'],['taskFilter','任务'],['platformFilter','投放平台']]){
    const input=document.getElementById(id),wrapper=make('label','workspace-field',label);
    if(id==='search')wrapper.classList.add('workspace-search');
    input.before(wrapper);wrapper.append(input);
  }
  const summary=make('div','filter-summary');summary.id='filterSummary';
  const chips=make('div','filter-chips');
  const reset=make('button','filter-reset','清除全部筛选');reset.type='button';reset.id='clearReportFilters';
  summary.append(chips,reset);toolbar.after(summary);
  function refresh(){
    chips.replaceChildren();let count=0;
    for(const [id,label,empty] of filters){
      const input=document.getElementById(id);if(input.value===empty)continue;count++;
      const value=input.tagName==='SELECT'?input.selectedOptions[0]?.textContent:input.value;
      const chip=make('button','filter-chip',`${label}：${value} ×`);chip.type='button';chip.title=`清除${label}`;
      chip.setAttribute('aria-label',`清除${label}筛选`);
      chip.onclick=()=>{input.value=empty;input.dispatchEvent(new Event('input',{bubbles:true}));input.focus();};chips.append(chip);
    }
    if(!count)chips.append(make('span','filter-placeholder','未设置筛选 · 当前展示已加载数据'));
    reset.hidden=!count;
  }
  reset.onclick=()=>{
    filters.forEach(([id,,empty])=>document.getElementById(id).value=empty);
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
  tools.append(density,focus);report.querySelector('.table-wrap').before(tools);
  report.querySelector('.table-wrap').setAttribute('aria-label','计划表现数据表，点击表头排序，可横向滚动');
  refresh();
})();
