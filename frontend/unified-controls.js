/* Shared, draft-first report controls. Credentials and edit forms are never included. */
(() => {
  'use strict';
  const route = location.pathname.replace(/\.html$/, '').replace(/\/index$/, '/').replace(/\/$/, '') || '/';
  if(!['/','/jd','/jd-low-activity','/adpflux','/account-vault','/tools'].includes(route))return;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const storeKey = 'bi-report-columns-v1:' + route;
  let preferences = {};
  try { preferences = JSON.parse(localStorage.getItem(storeKey) || '{}') || {}; } catch {}
  if(typeof preferences!=='object'||Array.isArray(preferences))preferences={};
  const save = () => { try { localStorage.setItem(storeKey, JSON.stringify(preferences)); } catch {} };
  const labelOf = input => {
    const label = input.closest('label')?.cloneNode(true);
    label?.querySelectorAll('input,select,button').forEach(element => element.remove());
    return input.dataset.tableColumn || label?.textContent.trim() || input.getAttribute('aria-label') || input.title || input.placeholder || input.id;
  };
  const visible = element => {
    if(!element || element.disabled)return false;
    // Reveal only our own presentation wrapper for the check. Permission wrappers stay hidden.
    const collapsed=element.closest('.uc-collapsed-filter');collapsed?.classList.remove('uc-collapsed-filter');
    const result=element.getClientRects().length>0 && getComputedStyle(element).visibility!=='hidden';
    collapsed?.classList.add('uc-collapsed-filter');return result;
  };
  const dialog = document.createElement('dialog');
  dialog.className = 'uc-dialog'; dialog.id = 'unifiedControlsDialog';
  document.body.append(dialog);
  function open(title, content, apply, reset) {
    dialog.innerHTML = `<div class="uc-head"><div><h2 id="uc-title">${title}</h2><p>调整后点击应用，取消不会改变当前结果</p></div><button type="button" class="uc-close" aria-label="关闭">×</button></div>${content}<p class="uc-error" role="alert"></p><div class="uc-footer"><button type="button" class="uc-reset">恢复默认</button><span></span><button type="button" class="uc-cancel">取消</button><button type="button" class="uc-apply">应用</button></div>`;
    dialog.setAttribute('aria-labelledby', 'uc-title');
    dialog.querySelector('.uc-close').onclick = dialog.querySelector('.uc-cancel').onclick = () => dialog.close();
    dialog.querySelector('.uc-reset').onclick = reset;
    dialog.querySelector('.uc-apply').onclick = () => { if (apply() !== false) dialog.close(); };
    dialog.showModal();
    dialog.scrollTop=0;
  }
  function columnDialog(config) {
    const catalog = config.columns, fixed = new Set(config.fixed || []), valid = new Set(catalog.map(c => c.key));
    let selected = [...new Set([...fixed, ...config.selected])].filter(key => valid.has(key));
    const normalize = () => { selected = [...catalog.filter(c => fixed.has(c.key) && c.key !== 'actions').map(c => c.key), ...selected.filter(k => !fixed.has(k)), ...catalog.filter(c => fixed.has(c.key) && c.key === 'actions').map(c => c.key)]; };
    normalize();
    const draw = () => {
      const query = dialog.querySelector('.uc-search').value.trim().toLowerCase();
      const choices = catalog.filter(c => c.label.toLowerCase().includes(query));
      dialog.querySelector('.uc-choices').innerHTML = choices.map(c => `<label><input type="checkbox" data-key="${esc(c.key)}" ${selected.includes(c.key)?'checked':''} ${fixed.has(c.key)?'disabled':''}><span>${esc(c.label)}</span>${fixed.has(c.key)?'<small>固定</small>':''}</label>`).join('') || '<p class="uc-empty">没有匹配的字段</p>';
      dialog.querySelector('.uc-count').textContent = `已选 ${selected.length} / ${catalog.length}`;
      dialog.querySelector('.uc-selected').innerHTML = selected.map(key => {const c = catalog.find(c => c.key === key), movable = !fixed.has(key), i = selected.indexOf(key);return `<div class="uc-selected-item" data-key="${esc(key)}"><span>${esc(c.label)}</span><div>${movable?`<button type="button" data-move="-1" aria-label="上移${esc(c.label)}" ${i===0||fixed.has(selected[i-1])?'disabled':''}>↑</button><button type="button" data-move="1" aria-label="下移${esc(c.label)}" ${i===selected.length-1||fixed.has(selected[i+1])?'disabled':''}>↓</button><button type="button" data-remove aria-label="移除${esc(c.label)}">×</button>`:'<small>固定列</small>'}</div></div>`;}).join('');
    };
    open('自定义列', '<div class="uc-columns"><section><input class="uc-search" type="search" placeholder="搜索字段名称" aria-label="搜索字段名称"><h3>可选字段</h3><div class="uc-choices"></div></section><section><h3 class="uc-count"></h3><p class="uc-help">按上下箭头调整表格顺序</p><div class="uc-selected"></div></section></div>', () => config.apply(selected), () => { selected = [...new Set([...fixed, ...(config.defaults || catalog.map(c => c.key))])]; normalize(); draw(); });
    dialog.querySelector('.uc-search').oninput = draw;
    dialog.querySelector('.uc-choices').onchange = event => { const key = event.target.dataset.key; if (!key || fixed.has(key)) return; selected = event.target.checked ? [...selected,key] : selected.filter(k => k !== key); normalize(); draw(); };
    dialog.querySelector('.uc-selected').onclick = event => { const button = event.target.closest('button'); if (!button || button.disabled) return; const key = button.closest('[data-key]').dataset.key, i = selected.indexOf(key); if (button.hasAttribute('data-remove')) selected.splice(i,1); else {const j = i + Number(button.dataset.move); if (j>=0 && j<selected.length && !fixed.has(selected[j])) [selected[i],selected[j]] = [selected[j],selected[i]];} draw(); };
    draw();
  }
  // New report tables use this method for both rendering and export.
  function tableColumns(target, scope, columns, fixed, rerender) {
    const catalog = columns.map(c => Array.isArray(c) ? {key:c[0],label:c[1]} : {key:c,label:c});
    const valid = new Set(catalog.map(c => c.key));
    const selected = [...new Set([...fixed, ...(Array.isArray(preferences[scope]) ? preferences[scope] : catalog.map(c => c.key))])].filter(k => valid.has(k));
    if (target) {
      let button = target.querySelector(':scope > .uc-columns-button');
      if (!button) {button = document.createElement('button');button.type='button';button.className='uc-button uc-columns-button';target.append(button);}
      button.textContent = `自定义列 ${selected.length}/${catalog.length}`;
      button.setAttribute('aria-haspopup','dialog');
      button.onclick = () => columnDialog({columns:catalog,fixed,selected,apply:next => {preferences[scope]=next;save();rerender();}});
    }
    return selected.map(key => columns.find(c => (Array.isArray(c) ? c[0] : c) === key));
  }
  window.BIControls = {tableColumns};
  function enhanceNativeColumns() {
    if (route === '/bid-monitor') return;
    document.querySelectorAll('details.column-menu').forEach(menu => {
      if (menu.dataset.ucReady) return;
      const inputs = [...menu.querySelectorAll('input[type=checkbox]')];
      if (!inputs.length) return;
      menu.dataset.ucReady='true'; menu.classList.add('uc-native-columns');menu.open=false;
      const button=document.createElement('button');button.type='button';button.className='uc-button uc-columns-button';
      button.setAttribute('aria-haspopup','dialog');
      const refreshLabel=()=>button.textContent=`自定义列 ${menu.querySelectorAll('input:checked').length}/${menu.querySelectorAll('input[type=checkbox]').length}`;
      refreshLabel();menu.after(button);
      // Account mapping updates the existing menu in place; daily tables replace it.
      const localObserver=new MutationObserver(refreshLabel);localObserver.observe(menu,{childList:true,subtree:true});
      button.onclick=()=>{
        const current=[...menu.querySelectorAll('input[type=checkbox]')], keyOf=input=>input.dataset.tableColumn || input.value;
        const catalog=current.map(input=>({key:keyOf(input),label:labelOf(input)}));
        const fixed=current.filter(input=>input.disabled).map(keyOf);
        let selected=current.filter(input=>input.checked).map(keyOf);
        const savedOrder=menu.closest('[data-selected-columns]') || menu.querySelector('[data-selected-columns]');
        try {const order=JSON.parse(savedOrder?.dataset.selectedColumns || 'null');if(Array.isArray(order))selected=[...order.filter(key=>selected.includes(key)),...selected.filter(key=>!order.includes(key))];} catch {}
        let defaults;
        try {defaults=JSON.parse(savedOrder?.dataset.defaultColumns || 'null');} catch {}
        columnDialog({columns:catalog,fixed,selected,defaults,apply:next=>{
          if(!menu.isConnected){dialog.querySelector('.uc-error').textContent='数据已刷新，请关闭后重新选择列。';return false;}
          // Reorder original checkbox nodes, then let the page persist and render normally.
          const parent=current[0].closest('label').parentElement;
          const order=[...next,...catalog.map(c=>c.key).filter(k=>!next.includes(k))];
          for(const key of order){const input=current.find(input=>keyOf(input)===key);input.checked=next.includes(key);parent.append(input.closest('label'));}
          current[0].dispatchEvent(new Event('change',{bubbles:true}));refreshLabel();
        }});
      };
    });
  }
  const filterGroups = {
    '/':[['#filterPanel .filters',['start','end','accountId'],'apply'],['#drillPanel > .filters',['drillSelect','optimizerSelect','projectSelect','taskSelect']]],
    '/jd':[['#filterPanel .filters',['start','end','accountId','excludeUnknownOptimizer'],'apply'],['#drillPanel .filters',['drillSelect','optimizerSelect','projectSelect','taskSelect']]],
    '/jd-low-activity':[['.filter-band .filters',['start','end','account','task'],'apply']],
    '/adpflux':[['.filter-grid',['start','end','query','accountStatus','spendingOnly'],'apply']],
    '/account-vault':[['.toolbar-actions',['dataStart','dataEnd','sortField','sortDirection','operatorFilter']]],
    '/tools':[['.tool-filters',['toolSearch','toolCategory']]],
  };
  function enhanceFilters() {
    for (const [selector,ids,submitId] of filterGroups[route] || []) {
      const group=document.querySelector(selector);if(!group || group.dataset.ucReady)continue;
      group.dataset.ucReady='true';group.classList.add('uc-filterbar');
      const button=document.createElement('button');button.type='button';button.className='uc-button uc-more-filters';button.textContent='更多筛选';group.append(button);
      button.setAttribute('aria-haspopup','dialog');
      const collapsedIds={'/jd':['excludeUnknownOptimizer'],'/adpflux':['accountStatus','spendingOnly'],'/jd-low-activity':['task'],'/account-vault':['sortField','sortDirection']}[route]||[];
      for(const id of collapsedIds){const input=document.getElementById(id);if(input && group.contains(input))(input.closest('label')||input).classList.add('uc-collapsed-filter');}
      if(route==='/account-vault')group.insertBefore(button,group.querySelector('#columnMenu'));
      button.onclick=()=>{
        const originals=ids.map(id=>document.getElementById(id)).filter(visible);
        if(!originals.length)return;
        open('更多筛选','<div class="uc-filter-fields"></div>',()=>{
          if(submitId && document.getElementById(submitId)?.disabled){dialog.querySelector('.uc-error').textContent='当前查询尚未完成，请稍后应用。';return false;}
          const drafts=[...dialog.querySelectorAll('[data-original]')];
          const start=drafts.find(d=>/^(start|dataStart)$/.test(d.dataset.original)),end=drafts.find(d=>/^(end|dataEnd)$/.test(d.dataset.original));
          if(start?.value && end?.value && start.value>end.value){dialog.querySelector('.uc-error').textContent='开始日期不能晚于结束日期';return false;}
          for(const draft of drafts)if(!draft.checkValidity()){draft.reportValidity();return false;}
          if(start?.dataset.original==='dataStart' && (Date.parse(end.value)-Date.parse(start.value))/86400000>366){dialog.querySelector('.uc-error').textContent='查询日期范围不能超过 367 天';return false;}
          const changed=[];
          for(const draft of drafts){const original=document.getElementById(draft.dataset.original);if(!visible(original))continue;if(original.value!==draft.value || original.checked!==draft.checked){original.value=draft.value;original.checked=draft.checked;changed.push(original);}}
          if(submitId)document.getElementById(submitId)?.click();
          else if(ids.includes('drillSelect') && changed.length)document.getElementById('drillSelect').dispatchEvent(new Event('change',{bubbles:true}));
          else for(const original of changed){if(original.id==='dataEnd' && changed.some(input=>input.id==='dataStart'))continue;original.dispatchEvent(new Event(original.matches('select,[type=date],[type=checkbox]')?'change':'input',{bubbles:true}));}
        },()=>{
          for(const draft of dialog.querySelectorAll('[data-original]')){const original=document.getElementById(draft.dataset.original);if(draft.type==='date')continue;if(draft.tagName==='SELECT'){draft.selectedIndex=0;}else if(draft.type==='checkbox')draft.checked=original.defaultChecked;else draft.value='';}
          dialog.querySelector('.uc-error').textContent='已重置筛选草稿，日期保持不变；点击应用生效。';
        });
        const fields=dialog.querySelector('.uc-filter-fields');
        for(const original of originals){const label=document.createElement('label'),span=document.createElement('span'),draft=original.cloneNode(true);span.textContent=labelOf(original);draft.id='uc-draft-'+original.id;draft.dataset.original=original.id;draft.removeAttribute('name');draft.removeAttribute('onchange');draft.removeAttribute('oninput');draft.value=original.value;draft.checked=original.checked;label.append(span,draft);fields.append(label);}
        // These native bounds refer to the old range, not the two-field draft.
        const draftStart=fields.querySelector('[data-original="dataStart"]'),draftEnd=fields.querySelector('[data-original="dataEnd"]');
        if(draftStart&&draftEnd){draftStart.max=draftEnd.max;draftEnd.removeAttribute('min');draftStart.required=draftEnd.required=true;}
      };
    }
  }
  let scheduled=false;
  const observer=new MutationObserver(()=>{if(scheduled)return;scheduled=true;queueMicrotask(()=>{scheduled=false;enhanceNativeColumns();enhanceFilters();});});
  observer.observe(document.body,{childList:true,subtree:true});
  enhanceNativeColumns();enhanceFilters();
})();
