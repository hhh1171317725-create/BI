// Read-only report insights. Uses the same filtered rows and formulas as the table/export.
(() => {
  const cards=$('#dataQualityCards'),filter=$('#dataQualityFilter');
  const categories=[['all','全部计划','当前筛选范围内的计划'],['linked','常规关联','日报 / 名称关联，单价和 gap 可用'],['estimated','估算关联','按价格推测任务，需结合业务核对'],['incomplete','待补齐','任务未识别，或缺少单价 / gap']];
  function drawCards(){
    const stats=Object.fromEntries(categories.map(([key])=>[key,{count:0,cost:0}]));
    for(const row of qualityScope){for(const key of ['all',dataQuality(row)]){stats[key].count++;stats[key].cost+=Number.isFinite(row.cost)?row.cost:0;}}
    cards.innerHTML=categories.map(([key,label,note])=>`<button type="button" class="quality-card quality-${key}" data-quality="${key}" aria-pressed="${filter.value===key}" title="${note}"><span>${label}</span><strong>${stats[key].count.toLocaleString('zh-CN')}<small>条</small></strong><small>消耗 ${fmt(stats[key].cost)}</small></button>`).join('');
  }
  cards.onclick=event=>{const button=event.target.closest('[data-quality]');if(!button)return;const key=button.dataset.quality;filter.value=filter.value===key?'all':key;filter.dispatchEvent(new Event('input',{bubbles:true}));cards.querySelector(`[data-quality="${key}"]`)?.focus({preventScroll:true});};
  document.addEventListener('bid:rendered',drawCards);drawCards();

  const dialog=document.createElement('dialog');dialog.id='bidPlanDetail';dialog.className='bid-dialog bid-plan-detail';dialog.setAttribute('aria-labelledby','bidPlanDetailTitle');
  dialog.innerHTML='<header class="bid-dialog-head"><h2 id="bidPlanDetailTitle">计划数据与计算依据</h2><button type="button" class="dialog-close" aria-label="关闭计划详情">×</button></header><p class="bid-dialog-note"></p><div class="bid-dialog-body"></div><footer class="bid-dialog-footer"><button type="button">关闭</button></footer>';
  document.body.append(dialog);dialog.querySelectorAll('button').forEach(button=>button.onclick=()=>dialog.close());
  const field=(label,value)=>`<div><dt>${esc(label)}</dt><dd>${esc(value??'--')}</dd></div>`;
  const sourceLabels={'account-name':'账户名称关键词','daily-report':'大航海日报任务','plan-name':'计划 / 账户名称识别','bid-return':'出价 × 回传比例估算','inferred':'历史价格推测'};
  $('#rows').addEventListener('click',event=>{
    const button=event.target.closest('[data-plan-detail]');if(!button||$('#viewMode').value!=='plans')return;
    const row=visible[Number(button.dataset.planDetail)];if(!row)return;
    const detail=row.inference||{},estimated=['bid-return','inferred'].includes(row.taskSource);
    const cashProfit=Number.isFinite(row.commission)&&Number.isFinite(row.cashCost)?row.commission-row.cashCost:null;
    const issues=[];
    if(!row.task)issues.push(names[row.pricingStatus]||'尚未识别任务，请核对日报任务名或账户关键词。');
    if(row.missingReason)issues.push(row.missingReason);
    if(!Number.isFinite(row.bidProfitRate)&&!row.missingReason)issues.push(names[row.status]||'出价利润率暂无有效结果，请检查出价、注册数、转化数与实际单价。');
    const priceSource={manual:'手动设置（优先）','daily-account':`${row.priceDate} 账户日报`,'daily-task':`${row.priceDate} 同任务日报参考`}[row.priceSource]||'暂无有效单价';
    dialog.querySelector('.bid-dialog-note').textContent=`统计区间 ${range?.start||'--'} 至 ${range?.end||'--'} · 打开时的数据快照；刷新后可重新打开查看。`;
    dialog.querySelector('.bid-dialog-body').innerHTML=`
      <div class="plan-detail-identity"><h3>${esc(row.name||'未命名计划')}</h3><p>计划 ${esc(row.id)} · ${esc(row.platform||'平台未返回')} · 优化师 ${esc(row.optimizer||'未返回')}</p><p>${esc(row.account||'账户未返回')} · ${esc(row.accountId)}</p></div>
      ${estimated?'<p class="detail-callout detail-warning">该任务为估算关联，不是日报直接确认的归属；相关收益指标也属于估算，请结合业务核对。</p>':''}
      ${issues.length?`<div class="detail-callout detail-warning"><strong>数据待核对</strong><ul>${issues.map(reason=>`<li>${esc(reason)}</li>`).join('')}</ul></div>`:''}
      <div class="detail-kpis">${[['总消耗',fmt(row.cost)],['现金消耗',fmt(row.cashCost)],['预估佣金',fmt(row.commission)],['预估 ROI',fmtRoi(row.estimatedRoi)]].map(([label,value])=>`<div><span>${label}</span><strong>${value}</strong></div>`).join('')}</div>
      <h3>任务与单价来源</h3><dl class="detail-fields">
      ${field('关联任务',row.task||'未匹配任务')}${field('关联依据',sourceLabels[row.taskSource]||'未识别')}
      ${field('结算单价',fmt(row.basePrice))}${field('单价来源',priceSource)}
      ${field('gap',fmtRoi(row.gap))}${field('gap 来源',gapTitle(row.accountId,row))}
      ${field('实际单价 = 结算单价 × gap',fmt(row.price))}${field('日报任务日期',detail.taskDate||'--')}</dl>
      ${row.taskSource==='bid-return'?`<div class="detail-callout"><strong>估算匹配过程</strong><p>当前出价 ${fmt(row.bid)} × 回传比例 ${fmtPercent(row.ratio)} = 每注册估算结算金额 ${fmt(detail.estimatedSettlementPrice)}</p><p>最近且唯一的任务实际单价 ${fmt(detail.matchedActualPrice)}；绝对差值 ${fmtRoi(detail.difference)}。金额接近不代表任务一定正确。</p></div>`:''}
      <h3>投放与收益计算</h3><dl class="detail-fields">
      ${field('转化数 / 注册数',`${fmt(row.conversions)} / ${fmt(row.registrations)}`)}${field('回传比例 = 转化数 ÷ 注册数',fmtPercent(row.ratio))}
      ${field('当前出价',fmt(row.bid))}${field('预估赔付',fmt(row.estimatedCompensation))}
      ${field('赠款',fmt(row.grant))}${field('现金利润 = 佣金 − 现金消耗',fmt(cashProfit))}
      ${field('盈亏线出价 = 实际单价 ÷ 回传比例',fmt(row.breakEvenBid))}${field('出价利润率',fmtPercent(row.bidProfitRate))}</dl>
      <p class="detail-footnote">佣金 = 注册数 × 实际单价；预估 ROI =（佣金 + 预估赔付）÷ 总消耗。缺失值保持 --，不会当作 0。全部计划的消耗均保留，汇总收益只覆盖具备有效单价和 gap 的计划。</p>`;
    dialog.showModal();
  });
})();
