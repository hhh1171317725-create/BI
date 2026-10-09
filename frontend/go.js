(() => {
  'use strict';
  const $=id=>document.getElementById(id),G=window.BIGo,letters='ABCDEFGHJKLMNOPQRST';
  let state=G.create(),dead=new Set(),focused=40,cells=[],finalScore=null;
  const board=$('goBoard');
  function build(){
    board.replaceChildren();cells=[];board.style.setProperty('--go-size',state.size);board.setAttribute('aria-rowcount',state.size);board.setAttribute('aria-colcount',state.size);
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),path=document.createElementNS(ns,'path'),max=state.size*2;
    svg.setAttribute('viewBox',`0 0 ${max} ${max}`);svg.setAttribute('class','gomoku-lines');svg.setAttribute('aria-hidden','true');let d='';
    for(let n=1;n<max;n+=2)d+=`M1 ${n}H${max-1}M${n} 1V${max-1}`;
    path.setAttribute('d',d);path.setAttribute('stroke','#9d855e');path.setAttribute('stroke-width','.05');svg.append(path);
    const points=state.size===9?[2,4,6]:state.size===13?[3,6,9]:[3,9,15];
    for(const row of points)for(const col of points){if(state.size===9&&((row===4)!==(col===4)))continue;const dot=document.createElementNS(ns,'circle');dot.setAttribute('cx',1+col*2);dot.setAttribute('cy',1+row*2);dot.setAttribute('r','.13');dot.setAttribute('fill','#80653e');svg.append(dot);}board.append(svg);
    for(let r=0;r<state.size;r++){const row=document.createElement('div');row.className='gomoku-row';row.setAttribute('role','row');
      for(let c=0;c<state.size;c++){const i=r*state.size+c,cell=document.createElement('button');cell.type='button';cell.className='gomoku-cell';cell.setAttribute('role','gridcell');cell.setAttribute('aria-rowindex',r+1);cell.setAttribute('aria-colindex',c+1);cell.dataset.index=i;cell.onclick=()=>place(i);cell.onfocus=()=>{cells[focused].tabIndex=-1;focused=i;cell.tabIndex=0;};cells.push(cell);row.append(cell);}board.append(row);
    }
  }
  function render(){
    const scoring=state.phase==='scoring',finished=state.phase==='finished',color=state.turn===1?'黑':'白';
    $('goStatus').textContent=finished?(finalScore?finalScore:'本局结束 · '+(state.winner===1?'黑':'白')+'方获胜'):scoring?'请双方确认死子':`轮到${color}方`;
    $('goHint').textContent=scoring?'点击棋块标记 / 取消死子':finished?'可以悔棋或重新开局':'点击空交点落子';
    $('goTurnStone').classList.toggle('white',state.turn===2);$('goMove').textContent=`已操作 ${state.history.length} 手`;
    $('goCaptures').textContent=`黑方提子 ${state.captures[1]} · 白方提子 ${state.captures[2]}`;
    cells.forEach((cell,i)=>{const player=state.board[i];cell.tabIndex=i===focused?0:-1;cell.classList.toggle('last',i===state.last);cell.classList.toggle('dead',dead.has(i));cell.setAttribute('aria-disabled',String(finished||(!scoring&&!!player)||(scoring&&!player)));cell.setAttribute('aria-label',`${letters[i%state.size]}${Math.floor(i/state.size)+1}，${player===1?'黑子':player===2?'白子':'空位'}${dead.has(i)?'，已标死子':''}`);if(cell.dataset.player!==String(player)){cell.dataset.player=player;cell.replaceChildren();if(player){const stone=document.createElement('span');stone.className='gomoku-stone'+(player===2?' white':'');cell.append(stone);}}});
    $('goUndo').disabled=!state.history.length;$('goPass').disabled=$('goResign').disabled=state.phase!=='playing';$('goScoring').hidden=!scoring;
    if(scoring){const s=G.score(state,dead);$('goScore').textContent=`黑：${s.stones[1]} 子 + ${s.territory[1]} 空 = ${s.black}；白：${s.stones[2]} 子 + ${s.territory[2]} 空 + 7.5 贴目 = ${s.white}；中立空点 ${s.neutral}。` ;}
  }
  function place(i){
    $('goNotice').textContent='';
    if(state.phase==='scoring'&&state.board[i]){const chain=G.group(state.board,i,state.size).stones,remove=dead.has(i);chain.forEach(n=>remove?dead.delete(n):dead.add(n));render();return;}
    const result=G.play(state,i);if(result.error){$('goNotice').textContent=result.error;return;}state=result.state;render();
  }
  function reset(){if(state.history.length&&!confirm('重新开局会清除当前对局，确定继续？')){$('goSize').value=state.size;return;}state=G.create(Number($('goSize').value));focused=Math.floor(state.size*state.size/2);dead.clear();finalScore=null;$('goNotice').textContent='';build();render();}
  $('goNew').onclick=reset;$('goSize').onchange=reset;
  $('goPass').onclick=()=>{state=G.pass(state);$('goNotice').textContent=state.phase==='scoring'?'双方已连续停一手。':'上一方已停一手。';render();};
  $('goUndo').onclick=()=>{state=G.undo(state);dead.clear();finalScore=null;$('goNotice').textContent='已撤回一步';render();};
  $('goResume').onclick=()=>{state=G.resume(state);dead.clear();$('goNotice').textContent='已恢复对局';render();};
  $('goResign').onclick=()=>{if(confirm(`确定${state.turn===1?'黑':'白'}方认输？`)){state=G.resign(state);$('goNotice').textContent='';render();}};
  $('goFinish').onclick=()=>{if(!confirm('双方已确认死子标记及计分结果？'))return;const s=G.score(state,dead),difference=s.black-s.white;finalScore=difference===0?'双方平局':`${difference>0?'黑':'白'}方胜 ${Math.abs(difference)} 目`;const {history,...snapshot}=state;state={...state,phase:'finished',history:[...history,snapshot]};$('goNotice').textContent='双方确认的数子结果';render();};
  board.onkeydown=e=>{const r=Math.floor(focused/state.size),c=focused%state.size,moves={ArrowUp:[Math.max(0,r-1),c],ArrowDown:[Math.min(state.size-1,r+1),c],ArrowLeft:[r,Math.max(0,c-1)],ArrowRight:[r,Math.min(state.size-1,c+1)]};if(moves[e.key]){e.preventDefault();const [row,col]=moves[e.key];cells[row*state.size+col].focus({preventScroll:true});}};
  build();render();
})();
