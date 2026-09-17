// Archived warnings stay accessible after plans leave the live snapshot.
(() => {
  const open=document.createElement('button');open.type='button';open.id='openEndedWarnings';open.textContent='已结束赔付期预警';
  document.querySelector('.table-tools').append(open);
  const dialog=document.createElement('dialog');dialog.id='endedWarningsDialog';dialog.className='bid-dialog ended-warnings';
  dialog.setAttribute('aria-labelledby','endedWarningsTitle');
  dialog.innerHTML=`<header class="bid-dialog-head"><h2 id="endedWarningsTitle">已结束赔付期预警</h2><button type="button" class="dialog-close" aria-label="关闭过期预警">×</button></header>
    <p class="bid-dialog-note">创建当天算第 1 天，第 4 天结束。重新查询已归档计划从创建日至今的数据，累计消耗 &gt; 最新出价 × 7.2 且累计转化不足 6 个才展示；独立于主报表筛选。</p>
    <div class="ended-warning-controls"><input type="search" aria-label="搜索过期预警计划" placeholder="搜索计划、账户、优化师或 ID"><select aria-label="过期预警投放平台"><option value="">全部平台</option></select><button type="button" class="ended-reload">重新核验接口数据</button></div>
    <p class="ended-warning-status" role="status" aria-live="polite"></p><div class="bid-dialog-body"><div class="ended-warning-table"><table><thead><tr><th>计划 / 账户</th><th>赔付期结束</th><th>累计消耗</th><th>消耗门槛 / 判断出价</th><th>累计转化</th><th>还差转化</th><th>核验范围</th></tr></thead><tbody></tbody></table></div></div>
    <footer class="bid-dialog-footer"><span class="ended-page"></span><button type="button" class="ended-prev">上一页</button><button type="button" class="ended-next">下一页</button><button type="button" class="ended-close">关闭</button></footer>`;
  document.body.append(dialog);
  const search=dialog.querySelector('input'),platform=dialog.querySelector('select'),reload=dialog.querySelector('.ended-reload'),status=dialog.querySelector('[role=status]'),body=dialog.querySelector('tbody');
  const prev=dialog.querySelector('.ended-prev'),next=dialog.querySelector('.ended-next');
  let rows=[],pageIndex=0,loaded=false,loading=false,asOf='',checkedAt='',unverified=0,checked=0,generation=0,timer=null;
  dialog.addEventListener('close',()=>{generation++;clearTimeout(timer);loading=false;reload.disabled=false;search.disabled=false;platform.disabled=false;});
  dialog.querySelector('.dialog-close').onclick=dialog.querySelector('.ended-close').onclick=()=>dialog.close();
  const matches=()=>{const query=search.value.trim().toLowerCase();return rows.filter(r=>(!platform.value||r.platform===platform.value)&&(!query||r.search.includes(query)));};
  function draw(){
    const selected=matches();
    const pages=Math.max(1,Math.ceil(selected.length/50));pageIndex=Math.min(pageIndex,pages-1);
    body.innerHTML=selected.slice(pageIndex*50,pageIndex*50+50).map(r=>`<tr><td><strong>${esc(r.name||r.id)}</strong><small>${esc(r.platform)} · 计划 ${esc(r.id)}</small><small>${esc(r.account||r.accountId)} · ${esc(r.optimizer||'未返回优化师')}</small></td><td>${esc(r.period_end)}<small>已结束</small></td><td title="接口累计消耗：${esc(r.overall_cost)}">${fmt(r.overall_cost)}<small>超过门槛 ${fmt(r.overall_cost-r.warning_threshold)}</small></td><td title="判断使用接口字段 cpa_bid：${esc(r.bid)}；门槛：${esc(r.warning_threshold)}">${fmt(r.warning_threshold)}<small>出价 ${fmt(r.bid)} × 7.2</small></td><td>${fmt(r.overall_conversions)}</td><td><span class="compensation-badge">还差 ${esc(r.shortfall)} 个</span></td><td>接口累计已核验<small>${esc(r.first_date)} 至 ${esc(r.last_date)}（含今日）</small></td></tr>`).join('');
    if(!selected.length)body.innerHTML='<tr><td colspan="7">'+(loaded?'已核验数据中没有符合条件的计划':'等待接口核验，暂不展示旧归档预警')+'</td></tr>';
    if(loaded)status.textContent=`共 ${rows.length} 个预警计划，当前匹配 ${selected.length} 个 · 已核验 ${checked} 个已归档计划，截至 ${asOf}；核验时间 ${checkedAt}。${unverified?`${unverified} 个计划未取得完整接口数据，未参与判断。`:''}未曾归档的计划不在本次范围。`;
    dialog.querySelector('.ended-page').textContent=`第 ${pageIndex+1} / ${pages} 页`;prev.disabled=loading||pageIndex===0;next.disabled=loading||pageIndex>=pages-1;
  }
  async function load(refresh=false){
    if(loading)return;loading=true;reload.disabled=true;search.disabled=true;platform.disabled=true;prev.disabled=true;next.disabled=true;
    const current=++generation;rows=[];loaded=false;draw();open.textContent='已结束赔付期预警';
    status.textContent='正在向接口核验创建日至今的累计消耗和转化，包含后续回补数据…';
    async function poll(force){
    try{
      const data=await api('/api/bid-monitor/history/ended-warnings'+(force?'?refresh=true':''),{signal:AbortSignal.timeout(30000)});
      if(current!==generation)return;
      if(data.state==='running'){timer=setTimeout(()=>poll(false),1500);return;}
      if(data.state==='error')throw Error(data.error||'接口数据核验失败');
      if(data.state!=='ready'||!Array.isArray(data.rows)||data.rows.some(r=>r.verified!==true))throw Error('核验响应无效，请确认后端已更新');
      rows=data.rows.map(r=>{const normalized=B.normalize(r);return {...r,...normalized,search:[normalized.name,normalized.id,normalized.account,normalized.accountId,normalized.optimizer].join(' ').toLowerCase()};});
      asOf=data.asOf;checkedAt=new Date(data.checkedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'});unverified=data.unverifiedCount||0;checked=data.checkedCount||0;loaded=true;pageIndex=0;
      const selection=platform.value;platform.innerHTML='<option value="">全部平台</option>'+[...new Set(rows.map(r=>r.platform).filter(Boolean))].map(p=>`<option value="${esc(p)}">${esc(p)}</option>`).join('');platform.value=selection;
      open.textContent=`已结束赔付期预警（${rows.length}）`;draw();
    }catch(error){if(current!==generation)return;status.textContent=`核验失败：${error.message}。旧归档未用于本次预警，请重试。`;}
    if(current!==generation)return;
    loading=false;reload.disabled=false;search.disabled=false;platform.disabled=false;const pages=Math.max(1,Math.ceil(matches().length/50));prev.disabled=pageIndex===0;next.disabled=pageIndex>=pages-1;
    }
    await poll(refresh);
  }
  open.onclick=()=>{dialog.showModal();load();};reload.onclick=()=>load(true);
  search.oninput=platform.onchange=()=>{pageIndex=0;draw();};prev.onclick=()=>{pageIndex--;draw();};next.onclick=()=>{pageIndex++;draw();};draw();
})();
