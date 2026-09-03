window.addEventListener('message', async event => {
  if (event.source!==window || event.origin!==location.origin || event.data?.source!=='bi-bid-page') return;
  if (!['/bid-monitor','/bid-monitor.html'].includes(location.pathname)) return;
  const {requestId,command,minutes,clientUser,mainUserId}=event.data;
  if (typeof requestId!=='string' || !['status','start','stop','detect'].includes(command)) return;
  let result;
  try { result=await chrome.runtime.sendMessage({type:'bid-sync',command,minutes,clientUser,mainUserId}); }
  catch { result={error:'插件已更新，请刷新此页面'}; }
  window.postMessage({source:'bi-bid-extension',requestId,...result},location.origin);
});
