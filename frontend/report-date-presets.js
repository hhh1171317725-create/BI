(function(root){
  function ranges(now=new Date()){
    const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(now);
    const [year,month,day]=today.split('-').map(Number);
    const iso=(y,m,d)=>new Date(Date.UTC(y,m,d)).toISOString().slice(0,10);
    const end=iso(year,month-1,day-1);
    return {yesterday:[end,end],week:[iso(year,month-1,day-7),end],month:[iso(year,month-1,day-30),end],previousMonth:[iso(year,month-2,1),iso(year,month-1,0)]};
  }
  if(typeof module==='object'&&module.exports){module.exports={ranges};return;}
  root.BIReportDates={ranges};
  const panel=document.getElementById('filterPanel');if(!panel)return;
  const start=document.getElementById('start'),end=document.getElementById('end');
  const group=document.createElement('div');group.className='date-shortcuts';group.setAttribute('role','group');group.setAttribute('aria-label','快捷查询日期');
  const caption=document.createElement('span');caption.textContent='快捷日期';group.append(caption);
  const buttons=[];
  for(const [key,label] of [['yesterday','昨天'],['week','近 7 天'],['month','近 30 天'],['previousMonth','上月']]){
    const button=document.createElement('button');button.type='button';button.textContent=label;button.dataset.rangePreset=key;button.setAttribute('aria-pressed','false');
    button.title=key==='previousMonth'?'北京时间上一个完整自然月':'按北京时间计算，截止昨日';
    button.onclick=()=>{[start.value,end.value]=ranges()[key];start.dispatchEvent(new Event('input',{bubbles:true}));end.dispatchEvent(new Event('input',{bubbles:true}));update();};
    buttons.push(button);group.append(button);
  }
  const note=document.createElement('small');note.textContent='截止昨日 · 选择后点击应用筛选，不会同步数据';group.append(note);
  panel.querySelector('.filters').before(group);
  function update(){const values=ranges();for(const button of buttons){const [a,b]=values[button.dataset.rangePreset];button.setAttribute('aria-pressed',String(start.value===a&&end.value===b));}}
  start.addEventListener('input',update);end.addEventListener('input',update);
  panel.addEventListener('click',()=>queueMicrotask(update));update();
})(typeof window==='undefined'?globalThis:window);
