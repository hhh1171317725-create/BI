(() => {
  function init(){
    const user=window.currentSessionUser,identity=user?.id??user?.username;
    if(identity===undefined)return; // Never mix preferences for unidentified sessions.
    const storageKey='marketing-tool-favorites-v1:'+encodeURIComponent(identity);
    let saved;try{saved=JSON.parse(localStorage.getItem(storageKey)||'[]');}catch{saved=[];}
    const favorites=new Set(Array.isArray(saved)?saved.filter(value=>typeof value==='string').slice(0,1000):[]);
    const key=card=>card.dataset.tool?'builtin:'+card.dataset.tool:'custom:'+JSON.stringify([card.querySelector('h3')?.textContent,card.querySelector('.actions a')?.href]);
    const state={only:false,matches:card=>!state.only||favorites.has(key(card))};window.toolFavorites=state;
    const toggle=document.createElement('button');toggle.type='button';toggle.id='favoriteToolsOnly';toggle.className='tool-reset';
    const results=document.querySelector('.tool-results');results.insertBefore(toggle,document.getElementById('resetToolFilters'));
    const hint=document.createElement('p');hint.className='favorite-hint';hint.setAttribute('role','status');hint.textContent='点击 ☆ 收藏并置顶；仅保存在当前浏览器的当前登录账户下。';results.after(hint);
    function refresh(){
      let available=0;
      for(const card of document.querySelectorAll('.tool')){
        const selected=favorites.has(key(card));
        if(selected&&(!card.dataset.tool||card.dataset.allowed==='true'))available++;
        let button=card.querySelector('.tool-favorite');
        if(!button){button=document.createElement('button');button.type='button';button.className='tool-favorite';card.append(button);}
        const name=card.querySelector('h3')?.textContent||'工具';
        button.textContent=selected?'★':'☆';button.setAttribute('aria-pressed',String(selected));button.setAttribute('aria-label',(selected?'取消收藏':'收藏')+'：'+name);
        card.classList.toggle('is-favorite',selected);
      }
      toggle.textContent=`只看收藏（${available}）`;toggle.setAttribute('aria-pressed',String(state.only));
      const empty=document.getElementById('emptyTools');empty.textContent=state.only?'没有符合条件的收藏工具。可清除筛选，点击工具卡片上的 ☆ 添加收藏。':'没有符合条件的工具。';
    }
    document.querySelector('.grid').addEventListener('click',event=>{
      const button=event.target.closest('.tool-favorite');if(!button)return;
      const id=key(button.closest('.tool'));if(favorites.has(id))favorites.delete(id);else favorites.add(id);
      try{localStorage.setItem(storageKey,JSON.stringify([...favorites]));}catch{hint.textContent='浏览器无法保存收藏，本次页面内仍可使用；刷新后可能丢失。';}
      applyFilters();
    });
    toggle.onclick=()=>{state.only=!state.only;applyFilters();};
    document.addEventListener('tools:reset-filters',()=>state.only=false);
    document.addEventListener('tools:updated',refresh);refresh();
  }
  if(document.documentElement.classList.contains('authenticated'))init();
  else{const observer=new MutationObserver(()=>{if(document.documentElement.classList.contains('authenticated')){observer.disconnect();init();}});observer.observe(document.documentElement,{attributes:true,attributeFilter:['class']});}
})();
