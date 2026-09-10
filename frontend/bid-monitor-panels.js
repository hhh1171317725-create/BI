// OceanEngine-inspired controls; no external scripts, branding or account data.
(() => {
 const make=(tag,cls,text)=>{const element=document.createElement(tag);element.className=cls;if(text)element.textContent=text;return element;};
 const report=document.getElementById('report');
 function dialog(id,title,description){
  const element=make('dialog','bid-dialog');element.id=id;element.setAttribute('aria-labelledby',id+'Title');
  const header=make('header','bid-dialog-head');const heading=make('h2','',title);heading.id=id+'Title';
  const close=make('button','dialog-close','×');close.type='button';close.setAttribute('aria-label','关闭'+title);close.onclick=()=>element.close();header.append(heading,close);
  const note=make('p','bid-dialog-note',description),body=make('div','bid-dialog-body'),footer=make('footer','bid-dialog-footer');
  const reset=make('button','dialog-reset','恢复默认'),cancel=make('button','','取消'),apply=make('button','primary','确认');
  for(const button of [reset,cancel,apply])button.type='button';cancel.onclick=()=>element.close();footer.append(reset,cancel,apply);
  element.append(header,note,body,footer);document.body.append(element);return {element,body,reset,apply};
 }
 const filter=dialog('bidFilterDialog','更多筛选','设置筛选条件，点击确认后应用到当前已加载的数据。');
 filter.body.classList.add('bid-filter-fields');filter.reset.textContent='重置条件';
 const filterIds=['appTypeFilter','deepBidTypeFilter','deepExternalActionFilter','externalActionFilter','statusFilter','deepCpaBidMin','deepCpaBidMax'];
 const filterError=make('p','bid-dialog-error');filterError.setAttribute('role','alert');filter.element.querySelector('footer').before(filterError);
 const more=make('button','more-filters','▽ 更多筛选');more.id='openBidFilters';more.type='button';
 report.querySelector('.report-toolbar').insertBefore(more,document.getElementById('export'));
 more.onclick=()=>{
  filter.body.replaceChildren();filterError.textContent='';
  for(const id of filterIds){const input=document.getElementById(id),label=make('label','',input.closest('label').childNodes[0].textContent.trim()),draft=input.cloneNode(true);draft.id='draft-'+id;draft.value=input.value;draft.disabled=false;label.append(draft);filter.body.append(label);}
  filter.element.showModal();
 };
 filter.reset.onclick=()=>{filter.body.querySelectorAll('input,select').forEach(input=>input.value='');filterError.textContent='';};
 filter.apply.onclick=()=>{
  const min=document.getElementById('draft-deepCpaBidMin'),max=document.getElementById('draft-deepCpaBidMax');
  if(!min.checkValidity()||!max.checkValidity()||(min.value!==''&&max.value!==''&&Number(min.value)>Number(max.value))){filterError.textContent='请输入非负出价，最低出价不能大于最高出价。';min.focus();return;}
  for(const id of filterIds)document.getElementById(id).value=document.getElementById('draft-'+id).value;
  document.getElementById(filterIds[0]).dispatchEvent(new Event('input',{bubbles:true}));filter.element.close();
 };
 const chooser=dialog('bidColumnsDialog','自定义列','按当前统计维度保存。左侧选择字段，右侧调整顺序；基础维度列固定保留。');
 chooser.body.classList.add('column-layout');
 const available=make('section','column-available'),chosen=make('section','column-chosen');
 const search=make('input','column-search');search.type='search';search.placeholder='搜索指标名称';search.setAttribute('aria-label','搜索指标名称');
 const choices=make('div','column-choices'),selectedTitle=make('h3'),selectedList=make('div','column-selected');
 available.append(search,choices);chosen.append(selectedTitle,selectedList);chooser.body.append(available,chosen);
 const open=make('button','column-config','☷ 选择列');open.type='button';open.id='openBidColumns';report.querySelector('.table-tools').append(open);
 let view,catalog=[],fixed=[],selection=[],defaults=[],useDefaults=false;
 const currentPlan=()=>activePlanColumns().map(([,key])=>key);
 function draw(){
  choices.replaceChildren();selectedList.replaceChildren();selectedTitle.textContent=`已选 ${selection.length} 列`;
  const term=search.value.trim().toLowerCase(),visible=catalog.filter(([label])=>label.toLowerCase().includes(term));
  const groups=[['基础信息',([,key])=>fixed.includes(key)||textSortKeys.has(key)],['投放指标',([,key])=>['plans','todayPlans','spendingPlans','accounts','cost','conversions','registrations','ratio','bid','deepCpaBid'].includes(key)],['收益与成本',()=>true]];
  const used=new Set();
  for(const [title,predicate] of groups){const items=visible.filter(column=>!used.has(column[1])&&predicate(column));if(!items.length)continue;choices.append(make('h3','',title));
   for(const [label,key] of items){used.add(key);const row=make('label','column-choice'),box=make('input');box.type='checkbox';box.dataset.columnKey=key;box.checked=selection.includes(key);box.disabled=fixed.includes(key);row.append(box,document.createTextNode(label+(box.disabled?'（固定）':'')));choices.append(row);
    box.onchange=()=>{useDefaults=false;selection=box.checked?[...selection,key]:selection.filter(value=>value!==key);draw();choices.querySelector(`[data-column-key="${key}"]`)?.focus();};
   }
  }
  if(!visible.length)choices.append(make('p','column-no-results','没有匹配的指标，试试其他关键词。'));
  selection.forEach((key,index)=>{const row=make('div','selected-column'),label=catalog.find(column=>column[1]===key)?.[0]||key;row.append(make('span','',label));
   if(fixed.includes(key))row.append(make('small','','固定'));
   else for(const [text,action,disabled] of [['↑','up',index<=fixed.length],['↓','down',index===selection.length-1],['×','remove',false]]){
    const button=make('button','',text);button.type='button';button.disabled=disabled;button.setAttribute('aria-label',({up:'上移',down:'下移',remove:'移除'})[action]+label);button.onclick=()=>{useDefaults=false;if(action==='remove')selection.splice(index,1);else{const target=index+(action==='up'?-1:1);[selection[index],selection[target]]=[selection[target],selection[index]];}draw();};row.append(button);
   }
   selectedList.append(row);
  });
 }
 open.onclick=()=>{view=document.getElementById('viewMode').value;fixed=view==='plans'?['name']:viewDimensions(view);catalog=view==='plans'?[...planColumns,...optionalColumns]:[...fixed.map(key=>[dimensionLabels[key],key]),...aggregateColumns];defaults=view==='plans'?planColumns.map(([,key])=>key):catalog.map(([,key])=>key);selection=view==='plans'?currentPlan():[...fixed,...currentAggregateColumns(view).map(([,key])=>key)];useDefaults=false;search.value='';draw();chooser.element.showModal();search.focus();};
 search.oninput=draw;
 chooser.reset.onclick=()=>{selection=[...defaults];useDefaults=true;search.value='';draw();};
 chooser.apply.onclick=()=>{
  if(document.getElementById('viewMode').value!==view){chooser.element.close();return;}
  if(view==='plans'){
   planDisplayKeys=useDefaults?null:[...selection];visibleOptionalColumns=new Set(useDefaults?[]:selection.filter(key=>optionalColumns.some(column=>column[1]===key)));
   try{if(planDisplayKeys)localStorage.setItem('bid-plan-display-columns-v2',JSON.stringify(planDisplayKeys));else localStorage.removeItem('bid-plan-display-columns-v2');localStorage.setItem(optionalColumnStorage,JSON.stringify([...visibleOptionalColumns]));}catch{message('浏览器无法保存展示列，本次设置仍有效。');}
  }else{if(useDefaults)delete aggregateSettings[view];else aggregateSettings[view]=selection.filter(key=>!fixed.includes(key));saveAggregateSettings();}
  page=1;render();chooser.element.close();
 };
 function indicators(){const count=filterIds.filter(id=>document.getElementById(id).value!=='').length;more.textContent='▽ 更多筛选'+(count?`（${count}）`:'');const mode=document.getElementById('viewMode').value,total=mode==='plans'?activePlanColumns().length:viewDimensions(mode).length+currentAggregateColumns(mode).length;open.textContent=`☷ 选择列（${total}）`;}
 document.addEventListener('bid:rendered',indicators);indicators();
 document.body.classList.add('bid-compact-controls');
})();
