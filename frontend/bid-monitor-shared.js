'use strict';
let bidCanManage=false,bidSharedVersion='',bidSharedUser='',bidSharedLoading=false,bidSharedPending=null,bidSharedPricing=null,bidSharedStrategies=null;
function bidSharedAccess(data){
  if(!data.userId||typeof data.canManage!=='boolean')throw Error('共享报表接口未就绪，请确认后端已更新');
  if(bidSharedUser&&bidSharedUser!==data.userId){location.replace('/login');throw Error('登录账户已变化，请重新登录');}
  bidSharedUser=data.userId;bidCanManage=data.canManage;
  document.body.dataset.bidReadonly=String(!bidCanManage);
  if(!bidCanManage&&!['#report','#strategy-lab'].includes(location.hash))location.hash='#report';
  const label=document.getElementById('sharedReportNotice');
  label.textContent=`共享报表 · 由 ${data.sharedOwnerName||'管理员'} 维护。${bidCanManage?'你可以管理同步、任务价格和推送。':'你可以查看、筛选和导出，无需配置。'}`;
}
async function bidApplyShared(data,force=false,applySnapshot=true){
  bidSharedAccess(data);
  let snapshotCurrent=applySnapshot,snapshotApplied=false;
  const strategies=Array.isArray(data.strategies)?data.strategies:[];
  const strategyKey=JSON.stringify([data.userId,data.canManage,data.strategyRevision||strategies]);
  if(bidSharedStrategies!==strategyKey){
    bidSharedStrategies=strategyKey;
    window.bidStrategyBundle={userId:data.userId,canManage:data.canManage,strategies,revision:data.strategyRevision||''};
    document.dispatchEvent(new CustomEvent('bid:strategies-shared',{detail:window.bidStrategyBundle}));
  }
  if(bidCanManage)return snapshotCurrent;
  window.BidDataAlerts?.syncStatus(data.status||{},data.sharedOwnerId);
  const rules=Array.isArray(data.rules)?data.rules:[],pricingKey=data.pricingRevision||JSON.stringify(rules),pricingChanged=bidSharedPricing!==pricingKey;
  if(pricingChanged){
    for(const option of document.getElementById('taskFilter').options)option.selected=false;
    bidSharedPricing=pricingKey;taskRules=rules;
  }
  if(applySnapshot&&data.snapshot&&(force||followSync)){
    const snapshot=data.snapshot;
    if(Array.isArray(snapshot.rows)&&snapshot.rows.length){
      const generation=reportLoadGeneration,loading=receive(snapshot.rows,`管理员共享快照 ${new Date(snapshot.updatedAt).toLocaleString('zh-CN')}`,
        {start:snapshot.date,end:snapshot.date},true),appliedRows=raw;
      await loading;snapshotCurrent=raw===appliedRows&&reportLoadGeneration===generation&&!busy;snapshotApplied=snapshotCurrent;
    }else{
      ++gapGeneration;gapData=null;raw=[];range=null;source='';render();
      message('管理员尚未同步共享数据，请联系管理员开启同步。');
    }
  }else if(pricingChanged)render();
  bidSharedVersion=data.version||'';
  if(snapshotApplied&&data.snapshot?.updatedAt)window.BidDataAlerts?.snapshotLoaded(data.snapshot.updatedAt);
  return snapshotCurrent;
}
async function bidRefreshShared(force=false){
  if(document.hidden)return false;
  if(bidSharedLoading){if(!force)return bidSharedPending;const generation=reportLoadGeneration;await bidSharedPending;if(generation!==reportLoadGeneration)return false;return bidRefreshShared(true);}
  const expectedRows=raw,expectedGeneration=reportLoadGeneration;
  bidSharedLoading=true;
  bidSharedPending=(async()=>{
    try{
      const data=await api('/api/bid-monitor/shared-report?after='+encodeURIComponent(force?'':bidSharedVersion),{signal:AbortSignal.timeout(20000)});
      // Metadata stays current, while a newer report query owns the displayed rows.
      const applySnapshot=raw===expectedRows&&reportLoadGeneration===expectedGeneration&&!busy;
      const current=await bidApplyShared(data,force,applySnapshot);
      window.BidDataAlerts?.success('shared');
      return current&&reportLoadGeneration===expectedGeneration&&!busy;
    }catch(error){message(error.message,true);window.BidDataAlerts?.fail('shared',error);return false;}
  })();
  try{return await bidSharedPending;}finally{bidSharedLoading=false;bidSharedPending=null;}
}
