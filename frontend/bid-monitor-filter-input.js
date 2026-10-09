(function(root,factory){
  const api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.BIBidFilterInput=api;
})(typeof globalThis==='undefined'?this:globalThis,function(){
  'use strict';
  function create({apply,onPending=()=>{},delay=140}){
    let timer=null,generation=0;
    function cancel(){
      if(timer===null)return;
      clearTimeout(timer);timer=null;generation++;onPending(false);
    }
    function schedule(){
      const wasPending=timer!==null;
      if(wasPending)clearTimeout(timer);
      const expected=++generation;
      timer=setTimeout(()=>{
        if(timer===null||generation!==expected)return;
        timer=null;onPending(false);apply();
      },delay);
      if(!wasPending)onPending(true);
    }
    function flush(){
      if(timer===null)return;
      cancel();apply();
    }
    return{schedule,flush,cancel,get pending(){return timer!==null;}};
  }
  return{create};
});
