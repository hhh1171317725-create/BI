// Workspace presentation and reusable local filter presets.
(() => {
  const report=document.getElementById('report'),deck=report.querySelector('.ocean-control-deck');
  const make=(tag,cls,text)=>{const el=document.createElement(tag);el.className=cls||'';if(text)el.textContent=text;return el;};
  const button=(text,id)=>{const el=make('button','',text);el.type='button';if(id)el.id=id;return el;};
  const level=deck.querySelector('.ocean-level-bar'),toolbar=deck.querySelector('.report-toolbar');
  level.querySelector('.ocean-level-hint').remove();
  const view=document.getElementById('viewMode');
  view.closest('label').classList.add('console-dimension');
  level.append(view.closest('label'),toolbar.querySelector('.ocean-range-field'));
  const actions=toolbar.querySelector('.report-actions');
  const presets=make('div','console-presets'),select=make('select');select.id='savedBidFilters';select.setAttribute('aria-label','已保存的筛选方案');
  const save=button('保存筛选','saveBidFilters'),remove=button('删除方案','deleteBidFilters');
  presets.append(select,save,remove);actions.before(presets);
  const status=make('span','console-preset-status');status.id='bidPresetStatus';status.setAttribute('role','status');presets.append(status);
  const ids=['search','taskFilter','optimizerFilter','platformFilter','appTypeFilter','deepBidTypeFilter','deepExternalActionFilter','externalActionFilter','statusFilter','deepCpaBidMin','deepCpaBidMax'];
  const key='bid-filter-presets-v1';let saved=[];
  try{const stored=JSON.parse(localStorage.getItem(key)||'[]');if(Array.isArray(stored))saved=stored.filter(p=>p&&typeof p.name==='string'&&p.filters&&typeof p.filters==='object').slice(0,12);}catch{}
  function draw(){select.replaceChildren(new Option('常用筛选方案',''));saved.forEach((p,i)=>select.add(new Option(p.name,String(i))));remove.disabled=true;}
  function persist(next){try{localStorage.setItem(key,JSON.stringify(next));saved=next;draw();return true;}catch{status.textContent='浏览器无法保存方案，请检查存储设置。';return false;}}
  function capture(){return Object.fromEntries(ids.map(id=>{const el=document.getElementById(id);return[id,el.multiple?[...el.selectedOptions].map(o=>o.textContent):el.value];}));}
  for(const id of ids)document.getElementById(id).addEventListener('input',()=>{select.value='';remove.disabled=true;status.textContent='';});
  const dialog=make('dialog','bid-dialog console-save-dialog');dialog.id='saveBidFilterDialog';
  dialog.innerHTML='<form method="dialog"><header class="bid-dialog-head"><h2>保存筛选方案</h2><button type="button" class="dialog-close" aria-label="关闭保存方案">×</button></header><div class="bid-dialog-body"><p class="muted">保存任务、优化师、平台和其他筛选条件。使用方案时沿用当前日期；仅保存在本浏览器。</p><label>方案名称<input id="bidFilterPresetName" maxlength="30" required placeholder="例如：广点通重点账户"></label><p class="console-save-error" role="alert"></p></div><footer class="bid-dialog-footer"><button type="button" class="console-cancel">取消</button><button type="submit" class="primary">保存</button></footer></form>';
  document.body.append(dialog);const name=dialog.querySelector('input'),error=dialog.querySelector('.console-save-error');
  for(const el of dialog.querySelectorAll('.dialog-close,.console-cancel'))el.onclick=()=>dialog.close();
  save.onclick=()=>{name.value='';error.textContent='';dialog.showModal();name.focus();};
  dialog.querySelector('form').onsubmit=event=>{event.preventDefault();const title=name.value.trim();if(!title){error.textContent='请输入方案名称。';return;}if(saved.some(p=>p.name===title)){error.textContent='已有同名方案，请换一个名称。';return;}if(saved.length>=12){error.textContent='最多保存 12 个方案，请先删除不用的方案。';return;}if(persist([...saved,{name:title,filters:capture()}])){dialog.close();select.value=String(saved.length-1);remove.disabled=false;status.textContent='筛选方案已保存';}};
  select.onchange=()=>{
    remove.disabled=select.value==='';if(select.value==='')return;
    const preset=saved[Number(select.value)];if(!preset)return;
    // Reject incomplete restores, rather than silently expanding the query.
    for(const id of ids){const el=document.getElementById(id),value=preset.filters[id];if(el.multiple&&(!Array.isArray(value)||value.some(text=>![...el.options].some(o=>o.textContent===text)))||el.tagName==='SELECT'&&!el.multiple&&value&&![...el.options].some(o=>o.value===value)){status.textContent='方案中的部分选项不在当前数据中，未应用；请先加载对应数据。';return;}}
    ids.forEach(id=>{const el=document.getElementById(id),value=preset.filters[id];if(el.multiple)for(const option of el.options)option.selected=value.includes(option.textContent);else el.value=typeof value==='string'?value:'';});
    compensationOnly=false;selectedAccount=null;page=1;render();status.textContent='已应用：'+preset.name;
  };
  remove.onclick=()=>{if(select.value==='')return;const index=Number(select.value);if(persist(saved.filter((_,i)=>i!==index)))status.textContent='已删除方案，当前筛选仍保留';};
  draw();
  const overview=report.querySelector('.ocean-overview-card'),heading=overview.querySelector('.report-heading');
  heading.firstElementChild.append(document.getElementById('message'));
  const explanation=make('details','console-explanation'),explanationTitle=make('summary','','数据说明与计算范围');
  explanation.append(explanationTitle);for(const id of ['source','lag','summaryCoverage']){const el=document.getElementById(id);if(el)explanation.append(el);}overview.append(explanation);
  const fold=button('收起汇总','toggleBidOverview');fold.setAttribute('aria-expanded','true');heading.append(fold);
  fold.onclick=()=>{const collapsed=overview.classList.toggle('console-overview-collapsed');fold.textContent=collapsed?'展开汇总':'收起汇总';fold.setAttribute('aria-expanded',String(!collapsed));};
  const dataStatus=make('details','console-data-status');dataStatus.id='bidDataStatus';
  const statusSummary=make('summary'),statusTitle=make('span','','数据状态与计算口径'),statusIndicator=make('span','console-status-indicator');
  statusIndicator.setAttribute('role','status');statusIndicator.setAttribute('aria-live','polite');
  statusSummary.append(statusTitle,statusIndicator);dataStatus.append(statusSummary);
  const historyStatus=document.getElementById('historyStatus'),gapStatus=document.getElementById('gapStatus');
  const gapStrip=gapStatus.closest('.gap-strip');gapStrip.before(dataStatus);dataStatus.append(historyStatus,gapStrip);
  const revenueCoverage=make('p','console-revenue-coverage');
  revenueCoverage.id='bidRevenueCoverage';revenueCoverage.setAttribute('role','status');
  dataStatus.after(revenueCoverage);
  let lastFailure=false;
  function updateDataStatus(){
    const text=historyStatus.textContent+' '+gapStatus.textContent,failed=/失败|异常/.test(text),loading=/正在|读取中|更新中/.test(text);
    const rows=typeof filteredRows==='undefined'?[]:filteredRows;
    const financialReady=!historyMode||historyFinancialReady;
    const missing=financialReady?rows.filter(row=>row.price===null):[];
    const label=failed?'需要处理':loading||!financialReady?'更新中':missing.length?`收益待关联 ${missing.length} 条`:raw.length?'已加载':'等待数据';
    if(statusIndicator.textContent!==label)statusIndicator.textContent=label;
    dataStatus.dataset.state=failed?'error':loading?'loading':'ready';
    if(failed&&!lastFailure)dataStatus.open=true;lastFailure=failed;
    revenueCoverage.hidden=!rows.length||!financialReady||!missing.length||loading&&!gapData;
    if(!revenueCoverage.hidden){
      const noTask=missing.filter(row=>!row.task).length;
      const noPrice=missing.filter(row=>row.basePrice===null).length;
      const noGap=missing.filter(row=>row.gap===null).length;
      const reasons=[noTask&&`任务未识别 ${noTask}`,noPrice&&`结算单价缺失 ${noPrice}`,noGap&&`gap 缺失 ${noGap}`].filter(Boolean).join('、');
      const dates=gapData?`单价查询起点 ${gapData.priceDate}；gap 使用 ${gapData.start} 至 ${gapData.end} 的有效日报。`:'';
      revenueCoverage.textContent=`当前结果 ${missing.length} / ${rows.length} 条计划无法计算 ROI：${reasons}（原因可能重叠）。${dates}点击计划名查看具体缺失原因。`;
    }
  }
  const statusObserver=new MutationObserver(updateDataStatus);
  for(const element of [historyStatus,gapStatus,document.getElementById('pricingCoverage')])statusObserver.observe(element,{childList:true,subtree:true,characterData:true});
  updateDataStatus();
  const tabs=level.querySelector('.ocean-level-tabs');
  tabs.addEventListener('keydown',event=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;const buttons=[...tabs.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);if(index<0)return;event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?buttons.length-1:(index+(event.key==='ArrowRight'?1:-1)+buttons.length)%buttons.length;buttons[next].click();buttons[next].focus();});
  const search=document.getElementById('search');search.type='search';
  document.addEventListener('keydown',event=>{if(event.key!=='/'||event.ctrlKey||event.metaKey||event.altKey||event.target.closest('input,textarea,select,[contenteditable="true"]')||document.querySelector('dialog[open]')||report.hidden)return;event.preventDefault();search.focus();search.select();});
  document.body.classList.add('bid-console');
})();
