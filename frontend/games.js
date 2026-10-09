(() => {
  'use strict';
  const $ = id => document.getElementById(id), core = window.BIGomoku;
  const columns = 'ABCDEFGHJKLMNOP';
  // Each new game adds a catalog entry and an opener without changing the lobby layout.
  const games = [{id:'gomoku',title:'五子棋',category:'休闲棋类',description:'黑白交替，五子连线。与电脑切磋，或和身边的朋友来一局。',modes:['人机对战','本地双人']}];
  let state = core.createGame(), mode = 'ai', human = 1, active = false, started = false;
  let aiTimer = null, revision = 0, focused = 7 * core.SIZE + 7;
  const board = $('gomokuBoard'), cells = [];
  const position = move => `${columns[move.col]}${move.row + 1}`;
  function make(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function svgElement(tag, attributes) {
    const element = document.createElementNS('http://www.w3.org/2000/svg',tag);
    for (const [key,value] of Object.entries(attributes)) element.setAttribute(key,String(value));
    return element;
  }
  function catalogArt() {
    const art = svgElement('svg',{viewBox:'0 0 160 160','aria-hidden':'true'});
    art.append(svgElement('rect',{x:4,y:4,width:152,height:152,rx:12,fill:'#eddbbb',stroke:'#dcc6a1'}));
    let path = '';
    for(let i=0;i<7;i++){const p=20+i*20;path+=`M20 ${p}H140M${p} 20V140`;}
    art.append(svgElement('path',{d:path,stroke:'#ab946f','stroke-width':.8,fill:'none'}));
    [[60,60,1],[80,80,2],[80,60,1],[100,80,2],[100,60,1]].forEach(([cx,cy,player])=>art.append(svgElement('circle',{cx,cy,r:8,fill:player===1?'#243044':'#fff',stroke:player===1?'#162233':'#d5dce5','stroke-width':1})));
    return art;
  }
  $('gameCount').textContent = `${games.length} 款游戏`;
  const openers = {gomoku:openGomoku};
  for(const game of games){
    const card=make('article','game-card'), art=make('div','game-card-art'), body=make('div','game-card-body');
    art.append(catalogArt());
    const heading=make('div','game-card-heading');heading.append(make('h3','',game.title),make('span','game-tag',game.category));
    const modes=make('div','game-card-modes');game.modes.forEach(label=>modes.append(make('span','',label)));
    const button=make('button','game-button game-primary','开始游戏');button.type='button';button.dataset.game=game.id;button.addEventListener('click',()=>openers[game.id]());
    body.append(heading,make('p','',game.description),modes,button);card.append(art,body);$('gameCatalog').append(card);
  }
  const lines=svgElement('svg',{viewBox:'0 0 30 30',class:'gomoku-lines','aria-hidden':'true'});
  let gridPath='';for(let i=0;i<core.SIZE;i++){const n=1+2*i;gridPath+=`M1 ${n}H29M${n} 1V29`;}
  lines.append(svgElement('path',{d:gridPath,stroke:'#9d855e','stroke-width':.045,fill:'none'}));
  [[3,3],[3,11],[7,7],[11,3],[11,11]].forEach(([row,col])=>lines.append(svgElement('circle',{cx:1+2*col,cy:1+2*row,r:.12,fill:'#8f7853'})));
  board.append(lines);
  for(let row=0;row<core.SIZE;row++){
    const line=make('div','gomoku-row');line.setAttribute('role','row');
    for(let col=0;col<core.SIZE;col++){
      const cell=make('button','gomoku-cell');cell.type='button';cell.dataset.row=row;cell.dataset.col=col;
      cell.setAttribute('role','gridcell');cell.setAttribute('aria-rowindex',row+1);cell.setAttribute('aria-colindex',col+1);
      const index=cells.length;cell.tabIndex=index===focused?0:-1;
      cell.addEventListener('focus',()=>setFocus(index,false));
      cell.addEventListener('click',()=>place(row,col));cells.push(cell);line.append(cell);
    }
    board.append(line);
  }
  function setFocus(index, moveFocus) {
    cells[focused].tabIndex=-1;focused=index;cells[focused].tabIndex=0;
    if(moveFocus)cells[focused].focus({preventScroll:true});
  }
  board.addEventListener('keydown',event=>{
    const row=Math.floor(focused/core.SIZE),col=focused%core.SIZE;
    const positions={ArrowUp:[Math.max(0,row-1),col],ArrowDown:[Math.min(14,row+1),col],ArrowLeft:[row,Math.max(0,col-1)],ArrowRight:[row,Math.min(14,col+1)],Home:[row,0],End:[row,14]};
    if(positions[event.key]){event.preventDefault();const [r,c]=positions[event.key];setFocus(r*core.SIZE+c,true);}
  });
  function cancelComputer() { revision++;if(aiTimer!==null)clearTimeout(aiTimer);aiTimer=null; }
  function canPlay() { return active&&state.status==='playing'&&(mode==='local'||state.currentPlayer===human); }
  function lastHumanIndex() { return state.moves.findLastIndex(move=>move.player===human); }
  function render() {
    const last=state.moves.at(-1), winners=new Set(state.winningLine.map(move=>move.row*core.SIZE+move.col)), playable=canPlay();
    board.dataset.turn=playable?String(state.currentPlayer):'';
    cells.forEach((cell,index)=>{
      const row=Math.floor(index/core.SIZE),col=index%core.SIZE,player=state.board[row][col];
      cell.classList.toggle('last',Boolean(last&&last.row===row&&last.col===col));cell.classList.toggle('winning',winners.has(index));
      cell.setAttribute('aria-label',`${columns[col]}${row+1}，第 ${row+1} 行第 ${col+1} 列，${player===1?'黑子':player===2?'白子':'空位'}`);
      cell.setAttribute('aria-disabled',String(!playable||Boolean(player)));
      if(cell.dataset.player!==String(player)){cell.dataset.player=player;cell.replaceChildren();if(player)cell.append(make('span',`gomoku-stone${player===2?' white':''}`));}
    });
    let title,hint;
    if(state.status==='won'){
      title=mode==='ai'?(state.winner===human?'你赢了！':'电脑获胜'):`${state.winner===1?'黑':'白'}方获胜`;
      hint='五子连线成功，可以开始新局或悔棋继续';
    }else if(state.status==='draw'){title='本局和棋';hint='棋盘已填满，开始新局再来一场';}
    else if(mode==='local'){title=`轮到${state.currentPlayer===1?'黑':'白'}方`;hint='两位玩家在这台设备上交替落子';}
    else if(state.currentPlayer===human){title=`轮到你 · 执${human===1?'黑':'白'}`;hint='点击棋盘交点落子';}
    else{title='电脑思考中…';hint='正在选择下一步落点';}
    $('gameStatus').textContent=title;$('gameStatusHint').textContent=hint;
    $('turnStone').className=`turn-stone${(state.winner||state.currentPlayer)===2?' white':''}${state.status==='draw'?' draw':''}`;
    $('moveTotal').textContent=state.status==='playing'?`第 ${state.moves.length+1} 手`:`共 ${state.moves.length} 手`;
    $('lastMove').textContent=last?`上一手：${last.player===1?'黑':'白'} · ${position(last)}`:'尚未落子';
    $('undoMove').disabled=mode==='local'?!state.moves.length:lastHumanIndex()<0;
    $('humanSideControl').hidden=mode!=='ai';
    const history=$('moveHistory');history.replaceChildren();
    state.moves.slice(-8).forEach((move,index)=>{
      const item=make('li');item.append(make('span','history-number',String(Math.max(0,state.moves.length-8)+index+1)),make('span',`history-stone${move.player===2?' white':''}`),make('span','',move.player===1?'黑子':'白子'),make('span','history-position',position(move)));history.append(item);
    });
    $('emptyHistory').hidden=Boolean(state.moves.length);
    document.querySelector('[data-game="gomoku"]').textContent=started?'继续对局':'开始游戏';
  }
  function scheduleComputer() {
    if(!active||mode!=='ai'||state.status!=='playing'||state.currentPlayer===human||aiTimer!==null)return;
    const token=revision;
    aiTimer=setTimeout(()=>{
      aiTimer=null;if(token!==revision||!active||mode!=='ai'||state.status!=='playing'||state.currentPlayer===human)return;
      const move=core.chooseMove(state);if(move)state=core.play(state,move.row,move.col);render();
    },220);
  }
  function place(row,col) {
    if(!canPlay())return;
    const next=core.play(state,row,col);if(next===state)return;
    state=next;setFocus(row*core.SIZE+col,false);render();scheduleComputer();
  }
  function restart() {cancelComputer();state=core.createGame();mode=$('gameMode').value;human=Number($('humanSide').value);setFocus(7*core.SIZE+7,false);render();scheduleComputer();}
  function openGomoku() {
    active=true;started=true;$('gameLobby').hidden=true;$('gomokuArena').hidden=false;
    if(location.hash!=='#gomoku')history.replaceState(null,'','#gomoku');render();scheduleComputer();$('backToLobby').focus({preventScroll:true});
  }
  function openLobby() {cancelComputer();active=false;$('gameLobby').hidden=false;$('gomokuArena').hidden=true;history.replaceState(null,'',location.pathname+location.search);render();document.querySelector('[data-game="gomoku"]').focus({preventScroll:true});}
  $('newGame').addEventListener('click',restart);
  $('gameMode').addEventListener('change',restart);$('humanSide').addEventListener('change',restart);
  $('undoMove').addEventListener('click',()=>{if($('undoMove').disabled)return;cancelComputer();state=core.undo(state,mode==='local'?1:state.moves.length-lastHumanIndex());render();scheduleComputer();});
  $('backToLobby').addEventListener('click',openLobby);
  window.addEventListener('hashchange',()=>{if(location.hash==='#gomoku')openGomoku();else if(active)openLobby();});
  window.addEventListener('pagehide',cancelComputer);
  window.addEventListener('pageshow',()=>{render();scheduleComputer();});
  render();if(location.hash==='#gomoku')openGomoku();
})();
