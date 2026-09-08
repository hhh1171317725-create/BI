// Visual hierarchy only; report loading, authorization and calculations stay unchanged.
(() => {
  const body=document.body;
  body.classList.add('site-polished');
  const create=(tag,className,text)=>{const element=document.createElement(tag);element.className=className;element.textContent=text;return element;};
  if(body.dataset.page==='tools'){
    const section=document.querySelector('main .section');
    const head=section.querySelector('.section-head');
    head.querySelector('h2').before(create('p','section-eyebrow','WORKSPACE / 工作台'));
    const category=document.getElementById('toolCategory');
    category.add(new Option('自定义','custom'));
    const results=create('div','tool-results','');
    const count=create('span','','');count.id='toolResultCount';count.setAttribute('role','status');
    const reset=create('button','tool-reset','清除筛选');reset.type='button';reset.id='resetToolFilters';
    reset.onclick=()=>{document.getElementById('toolSearch').value='';category.value='';document.dispatchEvent(new Event('tools:reset-filters'));document.getElementById('toolSearch').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('toolSearch').focus();};
    results.append(count,reset);section.querySelector('.tool-filters').after(results);
    function update(){
      const all=[...section.querySelectorAll('.tool')].filter(card=>!card.dataset.tool||card.dataset.allowed==='true');
      const visible=all.filter(card=>!card.hidden).length;
      count.textContent=`显示 ${visible} / ${all.length} 项工具`;
      reset.hidden=!document.getElementById('toolSearch').value&&!category.value&&!window.toolFavorites?.only;
    }
    new MutationObserver(update).observe(section.querySelector('.grid'),{subtree:true,childList:true,attributes:true,attributeFilter:['hidden','data-allowed']});
    document.getElementById('toolSearch').addEventListener('input',update);category.addEventListener('change',update);update();
    return;
  }
  body.dataset.reportStyle=location.pathname.startsWith('/jd')?'jd':'dhh';
  function labelPanel(panel,title,note){
    if(!panel)return;
    const heading=create('div','panel-heading','');
    heading.append(create('h2','',title),create('span','',note));panel.prepend(heading);
  }
  labelPanel(document.querySelector('body > .panel.admin-only'),'数据同步','管理员操作 · 更新服务器数据');
  labelPanel(document.getElementById('filterPanel'),'筛选与查询','选择范围后，点击应用筛选');
})();
