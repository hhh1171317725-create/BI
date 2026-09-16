// Archived warnings stay accessible after plans leave the live snapshot.
(() => {
  const open=document.createElement('button');open.type='button';open.id='openEndedWarnings';open.textContent='已结束赔付期预警';
  document.querySelector('.table-tools').append(open);
  const dialog=document.createElement('dialog');dialog.id='endedWarningsDialog';dialog.className='bid-dialog ended-warnings';
  dialog.setAttribute('aria-labelledby','endedWarningsTitle');
  dialog.innerHTML=`<header class="bid-dialog-head"><h2 id="endedWarningsTitle">已结束赔付期预警</h2><button type="button" class="dialog-close" aria-label="关闭过期预警">×</button></header>
    <p class="bid-dialog-note">创建当天算第 1 天，第 4 天结束。列出归档累计消耗 &gt; 最新归档出价 × 7.2、累计转化不足 6 个的计划；独立于主报表日期和筛选。</p>
    <div class="ended-warning-controls"><input type="search" aria-label="搜索过期预警计划" placeholder="搜索计划、账户、优化师或 ID"><select aria-label="过期预警投放平台"><option value="">全部平台</option></select><button type="button" class="ended-reload">刷新归档预警</button></div>
    <p class="ended-warning-status" role="status" aria-live="polite"></p><div class="bid-dialog-body"><div class="ended-warning-table"><table><thead><tr><th>计划 / 账户</th><th>赔付期结束</th><th>归档累计消耗</th><th>最低消耗</th><th>累计转化</th><th>还差转化</th><th>数据完整度</th></tr></thead><tbody></tbody></table></div></div>
    <footer class="bid-dialog-footer"><span class="ended-page"></span><button type="button" class="ended-prev">上一页</button><button type="button" class="ended-next">下一页</button><button type="button" class="ended-close">关闭</button></footer>`;
  document.body.append(dialog);
  const search=dialog.querySelector('input'),platform=dialog.querySelector('select'),reload=dialog.querySelector('.ended-reload'),status=dialog.querySelector('[role=status]'),body=dialog.querySelector('tbody');
  const prev=dialog.querySelector('.ended-prev'),next=dialog.querySelector('.ended-next');
  let rows=[],pageIndex=0,loaded=false,loading=false,asOf='';
  dialog.querySelector('.dialog-close').onclick=dialog.querySelector('.ended-close').onclick=()=>dialog.close();
  const matches=()=>{const query=search.value.trim().toLowerCase();return rows.filter(r=>(!platform.value||r.platform===platform.value)&&(!query||r.search.includes(query)));};
  function draw(){
    const selected=matches();
    const pages=Math.max(1,Math.ceil(selected.length/50));pageIndex=Math.min(pageIndex,pages-1);
    body.innerHTML=selected.slice(pageIndex*50,pageIndex*50+50).map(r=>`<tr><td><strong>${esc(r.name||r.id)}</strong><small>${esc(r.platform)} · 计划 ${esc(r.id)}</small><small>${esc(r.account||r.accountId)} · ${esc(r.optimizer||'未返回优化师')}</small></td><td>${esc(r.period_end)}<small>已结束</small></td><td>${fmt(r.overall_cost)}</td><td>${fmt(r.warning_threshold)}</td><td>${fmt(r.overall_conversions)}</td><td><span class="compensation-badge">还差 ${esc(r.shortfall)} 个</span></td><td>${r.archive_complete?'赔付期归档完整':'归档不完整，待核对'}<small>${esc(r.first_date)} 至 ${esc(r.last_date)}</small></td></tr>`).join('');
    if(!selected.length)body.innerHTML='<tr><td colspan="7">'+(loaded?'没有符合条件的归档计划':'请读取归档数据')+'</td></tr>';
    if(loaded)status.textContent=`共 ${rows.length} 个计划，当前匹配 ${selected.length} 个 · 读取至 ${asOf} 的已有归档。缺少归档的计划可能尚未显示，实际累计值需以媒体数据核对。`;
    dialog.querySelector('.ended-page').textContent=`第 ${pageIndex+1} / ${pages} 页`;prev.disabled=loading||pageIndex===0;next.disabled=loading||pageIndex>=pages-1;
  }
  async function load(){
    if(loading)return;loading=true;reload.disabled=true;search.disabled=true;platform.disabled=true;prev.disabled=true;next.disabled=true;
    status.textContent='正在汇总历史归档中的消耗与转化…';
    try{
      const data=await api('/api/bid-monitor/history/ended-warnings',{signal:AbortSignal.timeout(30000)});
      if(!Array.isArray(data.rows))throw Error('归档预警响应无效，请确认后端已更新');
      rows=data.rows.map(r=>{const normalized=B.normalize(r);return {...r,...normalized,search:[normalized.name,normalized.id,normalized.account,normalized.accountId,normalized.optimizer].join(' ').toLowerCase()};});
      asOf=data.asOf;loaded=true;pageIndex=0;
      const selection=platform.value;platform.innerHTML='<option value="">全部平台</option>'+[...new Set(rows.map(r=>r.platform).filter(Boolean))].map(p=>`<option value="${esc(p)}">${esc(p)}</option>`).join('');platform.value=selection;
      open.textContent=`已结束赔付期预警（${rows.length}）`;draw();
    }catch(error){status.textContent=`读取失败：${error.message}。${loaded?'下方保留上次读取结果。':'请点击刷新重试。'}`;}
    finally{loading=false;reload.disabled=false;search.disabled=false;platform.disabled=false;const pages=Math.max(1,Math.ceil(matches().length/50));prev.disabled=pageIndex===0;next.disabled=pageIndex>=pages-1;}
  }
  open.onclick=()=>{dialog.showModal();load();};reload.onclick=load;
  search.oninput=platform.onchange=()=>{pageIndex=0;draw();};prev.onclick=()=>{pageIndex--;draw();};next.onclick=()=>{pageIndex++;draw();};draw();
})();
