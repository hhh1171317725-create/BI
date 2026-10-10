// Keep failed pulls visible independently of the current tab or loaded snapshot.
(() => {
  const failures=new Map(),active=new Set(),retryHandlers=new Map(),retrying=new Set();
  const names={sync:'计划同步失败',status:'同步状态读取失败',snapshot:'计划快照读取失败',shared:'共享报表读取失败',query:'计划查询失败',history:'历史数据读取失败',gap:'任务、单价与 gap 更新失败',references:'历史收益关联失败',prior:'累计转化数据读取失败',archive:'每日归档失败'};
  const priority=['sync','query','snapshot','shared','status','history','gap','references','prior','archive'];
  let lastSuccess='',snapshotAt='',syncOwner='',syncState={},notificationCount=0,toastTimer,renderKey='',publishedKey='';
  const originalTitle=document.title;
  const make=(tag,cls,text)=>{const el=document.createElement(tag);if(cls)el.className=cls;if(text)el.textContent=text;return el;};
  const banner=make('aside','bid-data-alert');banner.id='bidDataAlert';banner.hidden=true;banner.setAttribute('aria-label','数据更新异常');
  const icon=make('span','bid-data-alert-icon','!');icon.setAttribute('aria-hidden','true');
  const content=make('div','bid-data-alert-content'),title=make('strong'),reason=make('p'),meta=make('p','bid-data-alert-meta');
  title.id='bidDataAlertTitle';reason.id='bidDataAlertReason';meta.id='bidDataAlertMeta';
  const details=make('details','bid-data-alert-more'),summary=make('summary'),list=make('ul');details.append(summary,list);
  content.append(title,reason,meta,details);
  const retry=make('button','bid-data-alert-retry');retry.type='button';retry.id='bidDataAlertRetry';
  banner.append(icon,content,retry);document.querySelector('.section-nav').after(banner);
  const toast=make('div','bid-data-toast');toast.id='bidDataFailureToast';toast.hidden=true;
  const live=make('span');live.setAttribute('role','alert');live.setAttribute('aria-atomic','true');
  const dismiss=make('button','','×');dismiss.type='button';dismiss.setAttribute('aria-label','关闭本次通知');dismiss.onclick=()=>{toast.hidden=true;};
  toast.append(live,dismiss);document.body.append(toast);
  const nav=document.querySelector('.section-nav');
  new ResizeObserver(()=>document.body.style.setProperty('--bid-nav-height',nav.offsetHeight+'px')).observe(nav);
  const displayDate=value=>{const date=new Date(value);return value&&Number.isFinite(date.getTime())?date.toLocaleString('zh-CN'):'';};
  const safeReason=value=>String(value?.message||value||'请求失败，请稍后重试').slice(0,350);
  const newerSuccess=(stamp,failure)=>{
    if(!stamp||stamp===failure.lastSuccess)return false;
    const next=Date.parse(stamp),previous=Date.parse(failure.lastSuccess),failed=Date.parse(failure.lastFailureAt||failure.at);
    return Number.isFinite(next)&&(!Number.isFinite(previous)||next>previous)&&(!Number.isFinite(failed)||next>=failed);
  };
  function notify(text){
    notificationCount++;toast.dataset.notificationCount=String(notificationCount);live.textContent=text;toast.hidden=false;
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>{toast.hidden=true;},8000);
  }
  function ordered(){return [...failures].sort(([a],[b])=>priority.indexOf(a)-priority.indexOf(b));}
  function state(){return{failed:failures.size>0,count:failures.size,loading:active.size>0||retrying.size>0||['running','waiting'].includes(syncState.state),lastSuccess,snapshotAt};}
  function publish(){
    const detail=state(),key=JSON.stringify(detail);if(key===publishedKey)return;
    publishedKey=key;document.dispatchEvent(new CustomEvent('bid:data-status',{detail}));
  }
  function render(){
    const entries=ordered(),first=entries[0],channel=first?.[0],failure=first?.[1],handler=retryHandlers.get(channel);
    const retained=Boolean(window.getBidStrategyData?.().rows?.length);
    const label=(typeof handler?.label==='function'?handler.label():handler?.label)||'重试';
    const disabled=retrying.has(channel)||Boolean(handler?.disabled?.());
    // Status polls commonly repeat the same result; preserve the current DOM and focus.
    const key=JSON.stringify([entries,lastSuccess,snapshotAt,syncState.state,syncState.enabled,retained,Boolean(handler),label,disabled]);
    if(key===renderKey){publish();return;}renderKey=key;
    banner.hidden=!first;
    const pageTitle=first?'⚠ 数据更新失败 · '+originalTitle:originalTitle;
    if(document.title!==pageTitle)document.title=pageTitle;
    if(first){
      title.textContent=names[channel]||'数据读取失败';reason.textContent=failure.error;
      const last=displayDate(lastSuccess),snapshot=displayDate(snapshotAt),time=displayDate(failure.at);
      const latest=displayDate(failure.lastFailureAt)||time;
      const failureTime=channel==='sync'?(latest?'最近失败：'+latest:''):(time?'失败时间：'+time:'');
      const failureCount=channel==='sync'&&failure.failureCount>0?'连续失败：'+failure.failureCount+'次':'';
      const retainedNote=retained?'已保留当前数据，请勿当作更新成功。':'暂无已加载数据。';
      const syncNote=channel==='sync'?(['running','waiting'].includes(syncState.state)?'正在重试。':syncState.state==='paused'?'自动同步已暂停。':syncState.enabled?'后台将自动重试。':'自动同步未开启。'):'';
      meta.textContent=[syncNote,retainedNote,last?'最近成功：'+last:'尚无成功同步记录',snapshot&&snapshot!==last?'当前快照：'+snapshot:'',failureTime,failureCount].filter(Boolean).join(' · ');
      summary.textContent='另有 '+(entries.length-1)+' 项读取异常';details.hidden=entries.length<2;
      list.replaceChildren(...entries.slice(1).map(([key,item])=>make('li','',(names[key]||key)+'：'+item.error)));
      retry.hidden=!handler;retry.textContent=label;retry.disabled=disabled;retry.dataset.channel=channel;
    }else toast.hidden=true;
    publish();
  }
  function fail(channel,error,options={}){
    const old=failures.get(channel),incident=options.incident||old?.incident||String(Date.now());
    const sameIncident=old?.incident===incident;
    failures.set(channel,{error:safeReason(error),incident,at:options.at||old?.at||new Date().toISOString(),lastSuccess:options.lastSuccess??old?.lastSuccess??lastSuccess,
      lastFailureAt:options.lastFailureAt||(sameIncident?old?.lastFailureAt:'')||options.at||'',
      failureCount:Number.isSafeInteger(options.failureCount)&&options.failureCount>0?options.failureCount:sameIncident?old?.failureCount||0:0});
    active.delete(channel);render();
    if(!old||old.incident!==incident)notify((names[channel]||'数据读取失败')+'，请查看页面顶部提示。');
  }
  function success(channel){active.delete(channel);failures.delete(channel);render();}
  function syncStatus(result,owner=result.userId){
    success('status');
    if(syncOwner&&syncOwner!==String(owner)){failures.delete('sync');failures.delete('archive');lastSuccess='';snapshotAt='';}
    syncOwner=String(owner||'');syncState=result;
    if(result.lastSuccess)lastSuccess=result.lastSuccess;
    if(result.snapshotUpdatedAt)snapshotAt=result.snapshotUpdatedAt;
    if(result.error){
      const diagnostic=typeof result.failureReason==='string'?result.failureReason.trim():'';
      const error=String(result.error);
      fail('sync',diagnostic&&!error.includes(diagnostic)?error+'；原因：'+diagnostic:error,{incident:syncOwner+':'+(result.failureAt||'unresolved'),at:result.failureAt,lastFailureAt:result.lastFailureAt,failureCount:result.failureCount,lastSuccess:result.lastSuccess||''});
    }else{
      const failure=failures.get('sync');
      // Reading a saved snapshot or saving credentials does not prove a new pull succeeded.
      const committed=result.snapshotUpdatedAt===undefined||result.snapshotUpdatedAt===result.lastSuccess;
      if(failure&&committed&&newerSuccess(result.lastSuccess,failure)&&!result.failureAt)success('sync');
    }
    if(result.historyError)fail('archive',result.historyError,{incident:syncOwner+':'+(result.historyFailureAt||'unresolved'),at:result.historyFailureAt,lastSuccess:result.historyLastSuccess||result.historyLastDate||''});
    else{
      const failure=failures.get('archive'),committed=result.historyLastSuccess||result.historyLastDate;
      if(failure&&committed&&newerSuccess(committed,failure)&&!result.historyFailureAt)success('archive');
    }
    render();
  }
  retry.onclick=async()=>{
    const channel=retry.dataset.channel,handler=retryHandlers.get(channel);if(!handler||retry.disabled||retrying.has(channel))return;
    retrying.add(channel);render();
    try{await handler.run();}catch(error){fail(channel,error);}finally{retrying.delete(channel);render();}
  };
  window.BidDataAlerts={
    fail,success,syncStatus,refresh:render,
    begin(channel){active.add(channel);render();},
    cancel(channel){active.delete(channel);render();},
    snapshotLoaded(at){snapshotAt=at||snapshotAt;success('snapshot');render();},
    setRetry(channel,handler){retryHandlers.set(channel,handler);render();},
    get state(){return state();}
  };
  document.addEventListener('bid:rendered',()=>{if(failures.size)render();});
})();
