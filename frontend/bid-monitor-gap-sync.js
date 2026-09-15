// Detect committed daily-report updates without downloading plan snapshots again.
(() => {
  let appliedRevision='',checking=false;
  window.checkBidGapRevision=async()=>{
    if(checking||document.hidden||!document.body.classList.contains('ready'))return;
    checking=true;
    try{
      const result=await api('/api/bid-monitor/gap/revision',{cache:'no-store',signal:AbortSignal.timeout(10000)});
      const revision=result.sourceRevision;
      if(typeof revision!=='string'||!revision||revision===appliedRevision)return;
      if(await window.refreshBidReferences(revision))appliedRevision=revision;
    }catch{/* Keep the previous version unacknowledged so the next poll retries. */}
    finally{checking=false;}
  };
  window.addEventListener('storage',event=>{if(event.key==='dhh-report-updated')void window.checkBidGapRevision();});
})();
