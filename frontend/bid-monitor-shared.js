'use strict';
let bidCanManage=false,bidSharedVersion='',bidSharedUser='',bidSharedLoading=false,bidSharedPricing='';
function bidSharedAccess(data){
  if(!data.userId||typeof data.canManage!=='boolean')throw Error('共享报表接口未就绪，请确认后端已更新');
  if(bidSharedUser&&bidSharedUser!==data.userId){location.replace('/login');throw Error('登录账户已变化，请重新登录');}
  bidSharedUser=data.userId;bidCanManage=data.canManage;
  document.body.dataset.bidReadonly=String(!bidCanManage);
  if(!bidCanManage&&!['#report','#strategy-lab'].includes(location.hash))location.hash='#report';
  const label=document.getElementById('sharedReportNotice');
  label.textContent=`共享报表 · 由 ${data.sharedOwnerName||'管理员'} 维护。${bidCanManage?'你可以管理同步、任务价格和推送。':'你可以查看、筛选和导出，无需配置。'}`;
}
async function bidApplyShared(data){
  bidSharedAccess(data);
  window.bidStrategyBundle={userId:data.userId,canManage:data.canManage,strategies:Array.isArray(data.strategies)?data.strategies:[],revision:data.strategyRevision||''};
  document.dispatchEvent(new CustomEvent('bid:strategies-shared',{detail:window.bidStrategyBundle}));
  if(bidCanManage)return;
  if(bidSharedPricing!==data.pricingRevision)for(const option of document.getElementById('taskFilter').options)option.selected=false;
  bidSharedPricing=data.pricingRevision||'';
  taskRules=Array.isArray(data.rules)?data.rules:[];
  if(data.snapshot){
    const snapshot=data.snapshot;
    if(Array.isArray(snapshot.rows)&&snapshot.rows.length){
      await receive(snapshot.rows,`管理员共享快照 ${new Date(snapshot.updatedAt).toLocaleString('zh-CN')}`,
        {start:snapshot.date,end:snapshot.date},true);
    }else{
      ++gapGeneration;gapData=null;raw=[];range=null;source='';render();
      message('管理员尚未同步共享数据，请联系管理员开启同步。');
    }
  }else render();
  bidSharedVersion=data.version||'';
}
async function bidRefreshShared(force=false){
  if(bidSharedLoading||document.hidden)return;
  bidSharedLoading=true;
  try{
    const data=await api('/api/bid-monitor/shared-report?after='+encodeURIComponent(force?'':bidSharedVersion),{signal:AbortSignal.timeout(20000)});
    await bidApplyShared(data);
    return true;
  }catch(error){message(error.message,true);return false;}finally{bidSharedLoading=false;}
}
