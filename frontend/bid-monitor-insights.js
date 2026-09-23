// Read-only report insights. Uses the same filtered rows and formulas as the table/export.
(() => {
  const alert=document.createElement('div');alert.id='compensationAlert';alert.className='compensation-alert';alert.hidden=true;
  const alertText=document.createElement('span'),alertFilter=document.createElement('button');alertFilter.type='button';alertFilter.id='compensationAlertFilter';
  alert.append(alertText,alertFilter);document.querySelector('#report .table-wrap').before(alert);
  alertFilter.onclick=()=>{compensationOnly=!compensationOnly;page=1;render();};
  function drawCompensationAlert(){
    if(!compensationWarningsReady()){
      alert.hidden=false;alertFilter.hidden=true;
      alertText.textContent=priorConversionStatus==='error'?'历史累计消耗与转化读取失败，暂不判断转化缺口，请刷新全部数据重试。':'正在读取历史累计消耗与转化，完成后显示转化门槛预警…';return;
    }
    alertFilter.hidden=false;
    const count=new Set(compensationCandidates.map(B.planIdentity)).size;
    alert.hidden=!count&&!compensationOnly;
    alert.classList.toggle('is-filtered',compensationOnly);
    alertText.textContent=count
      ?compensationOnly
        ?`正在展示 ${count} 个转化门槛预警计划：三天前创建，累计消耗高于出价 × 7.2，累计转化不足 6 个。`
        :`发现 ${count} 个转化门槛预警计划；当前表格展示全部计划。`
      :'当前筛选下没有转化门槛预警。';
    alert.title='只判断创建日期恰好为今天往前第3天的计划：累计消耗 > 当前出价 × 7.2，且累计转化 < 6；其他创建日期不预警。';
    alertFilter.textContent=compensationOnly?'返回全部计划':`查看 ${count} 个预警计划`;alertFilter.setAttribute('aria-pressed',String(compensationOnly));
  }
  document.addEventListener('bid:rendered',drawCompensationAlert);drawCompensationAlert();
  const cards=$('#summaryCards');
  let displayedRows=null;
  function drawCards(){
    if(displayedRows===filteredRows)return;
    displayedRows=filteredRows;
    const rows=filteredRows,priced=rows.filter(row=>Number.isFinite(row.price)&&Number.isFinite(row.commission)&&Number.isFinite(row.cashCost));
    const cost=rows.reduce((sum,row)=>sum+(Number.isFinite(row.cost)?row.cost:0),0);
    const commission=priced.reduce((sum,row)=>sum+row.commission,0);
    const grantRows=rows.filter(row=>Number.isFinite(row.grant));
    const grant=grantRows.reduce((sum,row)=>sum+row.grant,0);
    const roi=B.summarizeCash(priced).estimatedRoi,coverage=`${priced.length.toLocaleString('zh-CN')} / ${rows.length.toLocaleString('zh-CN')} 条计划可计算`;
    const coveredCost=priced.reduce((sum,row)=>sum+(Number.isFinite(row.cost)?row.cost:0),0);
    const items=[
      ['cost','总消耗',fmt(cost),`${rows.length.toLocaleString('zh-CN')} 条计划`],
      ['commission','预估佣金',priced.length?fmt(commission):'--',coverage],
      ['grant','预估赠款',grantRows.length||!rows.length?fmt(grant):'--',`${grantRows.length.toLocaleString('zh-CN')} / ${rows.length.toLocaleString('zh-CN')} 条计划可计算`],
      ['roi','预估 ROI',fmtRoi(roi),coverage]
    ];
    cards.innerHTML=items.map(([key,label,value,note])=>`<div class="quality-card summary-card" data-summary="${key}" title="${key==='cost'?'当前筛选全部计划的消耗':key==='grant'?'逐计划计算：同一计划在当前查询范围内累计转化数大于或等于6，且该行转化成本大于出价的1.2倍时，赠款=消耗−出价×转化数；当天数据同样参与':`仅统计可计算计划，覆盖消耗 ${fmt(coveredCost)} 元；未匹配计划不计为零收益`}"><span>${label}</span><strong>${value}</strong><small>${note}</small></div>`).join('');
    let note=$('#summaryCoverage');
    if(!note){note=document.createElement('p');note.id='summaryCoverage';note.className='muted';cards.after(note);}
    note.textContent=priced.length<rows.length?`收益覆盖消耗 ${fmt(coveredCost)} / ${fmt(cost)} 元；${rows.length-priced.length} 条计划收益待补齐。ROI 仅按可计算计划加权汇总，同计划在查询范围累计转化数少于 6 不计赔付。`:'收益覆盖当前筛选全部计划；ROI 按消耗加权汇总，同计划在查询范围累计转化数少于 6 不计赔付。';
  }
  document.addEventListener('bid:rendered',drawCards);drawCards();

  const dialog=document.createElement('dialog');dialog.id='bidPlanDetail';dialog.className='bid-dialog bid-plan-detail';dialog.setAttribute('aria-labelledby','bidPlanDetailTitle');
  dialog.innerHTML=`<header class="bid-dialog-head"><h2 id="bidPlanDetailTitle">计划数据与计算依据</h2><button type="button" class="dialog-close" aria-label="关闭计划详情">×</button></header>
    <div class="plan-detail-range" aria-label="查询计划指定时间数据"><label>开始日期<input id="planDetailStart" type="date" aria-label="计划详情开始日期"></label><span aria-hidden="true">至</span><label>结束日期<input id="planDetailEnd" type="date" aria-label="计划详情结束日期"></label><button id="planDetailLoad" class="primary" type="button">查询</button></div>
    <p class="bid-dialog-note" role="status" aria-live="polite"></p><div class="bid-dialog-body"></div><footer class="bid-dialog-footer"><button type="button">关闭</button></footer>`;
  document.body.append(dialog);
  dialog.querySelector('.dialog-close').onclick=dialog.querySelector('.bid-dialog-footer button').onclick=()=>dialog.close();
  const startInput=dialog.querySelector('#planDetailStart'),endInput=dialog.querySelector('#planDetailEnd'),loadButton=dialog.querySelector('#planDetailLoad'),note=dialog.querySelector('.bid-dialog-note'),body=dialog.querySelector('.bid-dialog-body');
  const field=(label,value)=>`<div><dt>${esc(label)}</dt><dd>${esc(value??'--')}</dd></div>`;
  const sourceLabels={'account-name':'账户名称关键词','daily-report':'大航海日报任务','plan-name':'计划 / 账户名称识别','bid-return':'出价 × 回传比例估算','inferred':'历史价格推测'};
  let selectedIdentity='',queryGeneration=0;

  function renderDetail(row,detailRange,recordCount=null){
    const detail=row.inference||{},estimated=['bid-return','inferred'].includes(row.taskSource);
    const cashProfit=Number.isFinite(row.commission)&&Number.isFinite(row.cashCost)?row.commission-row.cashCost:null;
    const issues=[];
    if(!row.task)issues.push(names[row.pricingStatus]||'尚未识别任务，请核对日报任务名或账户关键词。');
    if(row.missingReason)issues.push(row.missingReason);
    if(!Number.isFinite(row.bidProfitRate)&&!row.missingReason)issues.push(names[row.status]||'出价利润率暂无有效结果，请检查出价、注册数、转化数与实际单价。');
    const priceSource={manual:'手动设置（优先）','daily-account':`${row.priceDate} 账户日报`,'daily-task':`${row.priceDate} 同任务日报参考`,'range-total':`${row.rangeStart} 至 ${row.rangeEnd} 每日结果合并`}[row.priceSource]||'暂无有效单价';
    const countText=recordCount===null?'当前报表数据':`${recordCount} 条计划日数据已合并为 1 条`;
    note.textContent=`统计区间 ${detailRange.start} 至 ${detailRange.end} · ${countText}`;
    const detailGap=row.gapSource==='range-total'?`${row.rangeStart} 至 ${row.rangeEnd} 按各日任务、单价与 gap 计算后合并`:gapTitle(row.accountId,row);
    const destination=B.platformAdLink(row),platformLink=destination?` · <a class="plan-source-link" href="${esc(destination.url)}" target="_blank" rel="noopener noreferrer" title="${destination.exact?'直达创量广点通广告':'打开创量字节广告列表，需按广告 ID 搜索'}">${destination.exact?'直达创量广告↗':'打开创量列表↗'}</a>`:'';
    body.innerHTML=`
      <div class="plan-detail-identity"><h3>${esc(row.name||'未命名计划')}</h3><p>计划 ${esc(row.id)} · ${esc(row.platform||'平台未返回')} · 优化师 ${esc(row.optimizer||'未返回')}${platformLink}</p><p>${esc(row.account||'账户未返回')} · ${esc(row.accountId)}</p></div>
      ${row.compensationShortfall>0&&(recordCount!==null||compensationWarningsReady())?`<div class="detail-callout detail-warning"><strong>转化门槛未达到：还差 ${row.compensationShortfall} 个转化</strong><p>该计划创建于 ${esc(String(row.createdAt||'').slice(0,10))}，符合三天前创建的预警范围。累计消耗 ${fmt(row.overallCost)} 元，高于最低消耗 ${fmt(row.compensationWarningThreshold)} 元（当前出价 × 7.2）；累计转化 ${fmt(row.overallConversions)} 个。</p></div>`:''}
      ${estimated?'<p class="detail-callout detail-warning">该任务为估算关联，不是日报直接确认的归属；相关收益指标也属于估算，请结合业务核对。</p>':''}
      ${issues.length?`<div class="detail-callout detail-warning"><strong>数据待核对</strong><ul>${issues.map(reason=>`<li>${esc(reason)}</li>`).join('')}</ul></div>`:''}
      <div class="detail-kpis">${[['总消耗',fmt(row.cost)],['现金消耗',fmt(row.cashCost)],['预估佣金',fmt(row.commission)],['预估 ROI',fmtRoi(row.estimatedRoi)]].map(([label,value])=>`<div><span>${label}</span><strong>${value}</strong></div>`).join('')}</div>
      <h3>任务与单价来源</h3><dl class="detail-fields">
      ${field('关联任务',row.task||'未匹配任务')}${field('关联依据',sourceLabels[row.taskSource]||'未识别')}
      ${field('结算单价',fmt(row.basePrice))}${field('单价来源',priceSource)}
      ${field('gap',fmtRoi(row.gap))}${field('gap 来源',detailGap)}
      ${field('实际单价 = 结算单价 × gap',fmt(row.price))}${field('日报任务日期',detail.taskDate||row.priceDate||'--')}</dl>
      ${row.taskSource==='bid-return'?`<div class="detail-callout"><strong>估算匹配过程</strong><p>当前出价 ${fmt(row.bid)} × 回传比例 ${fmtPercent(row.ratio)} = 每注册估算结算金额 ${fmt(detail.estimatedSettlementPrice)}</p><p>最近且唯一的任务实际单价 ${fmt(detail.matchedActualPrice)}；绝对差值 ${fmtRoi(detail.difference)}。金额接近不代表任务一定正确。</p></div>`:''}
      <h3>投放与收益计算</h3><dl class="detail-fields">
      ${field(row.impressionsEstimated?'曝光数（按消耗和媒体 CPM 反算）':'曝光数',fmt(row.impressions))}${field('转化数 / 注册数',`${fmt(row.conversions)} / ${fmt(row.registrations)}`)}${field('注册成本 = 总消耗 ÷ 注册数',fmt(row.cpa))}${field('计划累计转化数',fmt(row.overallConversions))}${field('计划累计消耗',fmt(row.overallCost))}${field('预警最低消耗 = 当前出价 × 7.2',fmt(row.compensationWarningThreshold))}${field('预估 eCPM = 当前出价 × 转化数 ÷ 曝光数 × 1000',fmt(row.ecpm))}${field('回传比例 = 转化数 ÷ 注册数',fmtPercent(row.ratio))}
      ${field('当前出价',fmt(row.bid))}${field('预估赔付',fmt(row.estimatedCompensation))}
      ${field('赠款',fmt(row.grant))}${field('现金利润 = 佣金 − 现金消耗',fmt(cashProfit))}
      ${field('盈亏线出价 = 实际单价 ÷ 回传比例',fmt(row.breakEvenBid))}${field('出价利润率',fmtPercent(row.bidProfitRate))}</dl>
      <p class="detail-footnote">查询多日时，系统先按每天对应的任务、单价和 gap 计算，再将同一计划的消耗、佣金和赔付合并为一条。预估 ROI =（佣金 + 预估赔付）÷ 总消耗；缺失值保持 --，不会当作 0。</p>`;
  }

  async function loadDetailRange(){
    const start=startInput.value,end=endInput.value,generation=++queryGeneration;
    if(!start||!end||start>end||end>today()){note.textContent='请选择不晚于今天的有效开始和结束日期。';return;}
    loadButton.disabled=true;loadButton.textContent='查询中…';note.textContent=`正在查询 ${start} 至 ${end} 的计划数据与每日计算依据…`;
    try{
      const data=await window.loadBidHistoryRange(start,end),normalized=(Array.isArray(data.rows)?data.rows:[]).map(B.normalize),matched=normalized.filter(row=>B.planIdentity(row)===selectedIdentity);
      if(generation!==queryGeneration)return;
      if(!matched.length){body.innerHTML='<div class="plan-detail-empty"><strong>所选时间没有该计划的数据</strong><p>可以扩大日期范围，或确认该计划当时已经创建并产生快照。</p></div>';note.textContent=`${start} 至 ${end} · 未找到该计划`;return;}
      const references=await window.loadBidHistoricalReferences(matched);
      if(generation!==queryGeneration)return;
      const calculated=window.analyzeBidStrategyRows(matched,references),merged=B.mergePlanRows(calculated)[0];
      renderDetail(merged,{start,end},matched.length);
      if(!references.complete)note.textContent+=` · ${references.size} / ${references.totalDates} 个日期已关联收益口径`;
    }catch(error){if(generation===queryGeneration)note.textContent='查询失败：'+error.message;}
    finally{if(generation===queryGeneration){loadButton.disabled=false;loadButton.textContent='查询';}}
  }
  loadButton.onclick=()=>void loadDetailRange();
  for(const input of [startInput,endInput])input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();void loadDetailRange();}};

  $('#rows').addEventListener('click',event=>{
    const button=event.target.closest('[data-plan-detail]');if(!button||$('#viewMode').value!=='plans')return;
    const row=visible[Number(button.dataset.planDetail)];if(!row)return;
    selectedIdentity=B.planIdentity(row);queryGeneration++;
    const detailRange={start:range?.start||today(),end:range?.end||today()};
    startInput.max=endInput.max=today();startInput.value=detailRange.start;endInput.value=detailRange.end;
    loadButton.disabled=false;loadButton.textContent='查询';renderDetail(row,detailRange);
    dialog.showModal();
  });
})();
