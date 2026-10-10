// Keep failed pulls visible independently of the current tab or loaded snapshot.
(() => {
  const failures=new Map(),active=new Set(),retryHandlers=new Map();
  const names={sync:'计划同步失败',status:'同步状态读取失败',snapshot:'计划快照读取失败',shared:'共享报表读取失败',query:'计划查询失败',history:'历史数据读取失败',gap:'任务、单价与 gap 更新失败',references:'历史收益关联失败',prior:'累计转化数据读取失败',archive:'每日归档失败'};
  const priority=['sync','query','snapshot','shared','status','history','gap','references','prior','archive'];
  let lastSuccess='',snapshotAt='',syncOwner='',syncState={},notificationCount=0,toastTimer;
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
    const next=Date.parse(stamp),previous=Date.parse(failure.lastSuccess),failed=Date.parse(failure.at);
    return Number.isFinite(next)&&(!Number.isFinite(previous)||next>previous)&&(!Number.isFinite(failed)||next>=failed);
  };
  function notify(text){
    notificationCount++;toast.dataset.notificationCount=String(notificationCount);live.textContent=text;toast.hidden=false;
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>{toast.hidden=true;},8000);
  }
  function ordered(){return [...failures].sort(([a],[b])=>priority.indexOf(a)-priority.indexOf(b));}
  function state(){return{failed:failures.size>0,count:failures.size,loading:active.size>0||['running','waiting'].includes(syncState.state),lastSuccess,snapshotAt};}
  function render(){
    const entries=ordered(),first=entries[0];banner.hidden=!first;
    document.title=first?'⚠ 数据更新失败 · '+originalTitle:originalTitle;
    if(first){
      const [channel,failure]=first;
      title.textContent=names[channel]||'数据读取失败';reason.textContent=failure.error;
      const last=displayDate(lastSuccess),snapshot=displayDate(snapshotAt),time=displayDate(failure.at);
      const retained=window.getBidStrategyData?.().rows?.length?'已保留当前数据，请勿当作更新成功。':'暂无已加载数据。';
      const syncNote=channel==='sync'?(['running','waiting'].includes(syncState.state)?'正在重试。':syncState.state==='paused'?'自动同步已暂停。':syncState.enabled?'后台将自动重试。':'自动同步未开启。'):'';
      meta.textContent=[syncNote,retained,last?'最近成功：'+last:'尚无成功同步记录',snapshot&&snapshot!==last?'当前快照：'+snapshot:'',time?'失败时间：'+time:''].filter(Boolean).join(' · ');
      summary.textContent='另有 '+(entries.length-1)+' 项读取异常';details.hidden=entries.length<2;
      list.replaceChildren(...entries.slice(1).map(([key,item])=>make('li','',(names[key]||key)+'：'+item.error)));
      const handler=retryHandlers.get(channel);retry.hidden=!handler;retry.textContent=(typeof handler?.label==='function'?handler.label():handler?.label)||'重试';retry.disabled=Boolean(handler?.disabled?.());retry.dataset.channel=channel;
    }else toast.hidden=true;
    document.dispatchEvent(new CustomEvent('bid:data-status',{detail:state()}));
  }
  function fail(channel,error,options={}){
    const old=failures.get(channel),incident=options.incident||old?.incident||String(Date.now());
    failures.set(channel,{error:safeReason(error),incident,at:options.at||old?.at||new Date().toISOString(),lastSuccess:options.lastSuccess??old?.lastSuccess??lastSuccess});
    active.delete(channel);render();
    if(!old||old.incident!==incident)notify((names[channel]||'数据读取失败')+'，请查看页面顶部提示。');
  }
  function success(channel){active.delete(channel);if(failures.delete(channel))render();else document.dispatchEvent(new CustomEvent('bid:data-status',{detail:state()}));}
  function syncStatus(result,owner=result.userId){
    success('status');
    if(syncOwner&&syncOwner!==String(owner)){failures.delete('sync');failures.delete('archive');lastSuccess='';snapshotAt='';}
    syncOwner=String(owner||'');syncState=result;
    if(result.lastSuccess)lastSuccess=result.lastSuccess;
    if(result.snapshotUpdatedAt)snapshotAt=result.snapshotUpdatedAt;
    if(result.error){
      fail('sync',result.error,{incident:syncOwner+':'+(result.failureAt||'unresolved'),at:result.failureAt,lastSuccess:result.lastSuccess||''});
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
    const channel=retry.dataset.channel,handler=retryHandlers.get(channel);if(!handler||retry.disabled)return;
    retry.disabled=true;
    try{await handler.run();}catch(error){fail(channel,error);}finally{render();}
  };
  window.BidDataAlerts={
    fail,success,syncStatus,
    begin(channel){active.add(channel);render();},
    cancel(channel){active.delete(channel);render();},
    snapshotLoaded(at){snapshotAt=at||snapshotAt;success('snapshot');render();},
    setRetry(channel,handler){retryHandlers.set(channel,handler);render();},
    get state(){return state();}
  };
  document.addEventListener('bid:rendered',()=>{if(failures.size)render();});
})();
