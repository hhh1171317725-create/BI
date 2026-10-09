(() => {
  'use strict';
  const $ = id => document.getElementById(id), core = window.BIGomoku;
  const columns = 'ABCDEFGHJKLMNOP';
  // Each new game adds a catalog entry and an opener without changing the lobby layout.
  const games = [{id:'gomoku',title:'五子棋',category:'休闲棋类',description:'黑白交替，五子连线。挑战电脑，或邀请朋友远程来一局。',modes:['人机对战','本地双人','远程对战']},{id:'go',title:'围棋',category:'策略棋类',description:'落子围地，攻守之间。支持提子、停一手与终局死子确认。',modes:['本地双人','9 / 13 / 19 路']}];
  let state = core.createGame(), mode = 'ai', human = 1, active = false, started = false;
  let aiTimer = null, revision = 0, focused = 7 * core.SIZE + 7;
  const board = $('gomokuBoard'), cells = [];
  let remote=null, remoteConnection='paused', remoteNotice='';
  const online=new window.BIGomokuOnline({
    onUpdate:room=>{remote=room;if(mode==='online')state=room?.game||core.createGame();render();},
    onConnection:value=>{remoteConnection=value;render();},
    onBusy:()=>render(),
    onError:message=>{remoteNotice=message;render();}
  });
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
  const openers = {gomoku:openGomoku,go:()=>location.assign('/go.html')};
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
  function canPlay() {
    if(mode==='online')return active&&remote?.phase==='playing'&&!remote.request&&remoteConnection==='connected'&&!online.busy&&state.currentPlayer===remote.seat;
    return active&&state.status==='playing'&&(mode==='local'||state.currentPlayer===human);
  }
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
    if(mode==='online'){
      if(!remote){title='创建或加入远程房间';hint='邀请好友，一人执黑，一人执白';}
      else if(remote.phase==='closed'){title='房间已结束';hint=remote.closedReason||'可以创建房间开始新的对局';}
      else if(remoteConnection!=='connected'){title=remoteConnection==='connecting'?'正在连接房间…':'连接中断，正在重连…';hint='同步完成后可以继续落子';}
      else if(remote.phase==='waiting'){title='等待好友加入';hint=`你执黑 · 发送房间号 ${remote.code} 或邀请链接给好友`;}
      else if(remote.request){title='等待确认';hint=remote.request.by===remote.seat?'请求已发送，等待对方同意':'对方发起了请求，请在房间面板确认';}
      else if(state.status==='won'){title=state.winner===remote.seat?'你赢了！':'对方获胜';hint='可以邀请对方再来一局';}
      else if(state.status==='draw'){title='本局和棋';hint='可以邀请对方再来一局';}
      else{title=state.currentPlayer===remote.seat?'轮到你落子':'等待对方落子';hint=`你执${remote.seat===1?'黑':'白'} · ${remote.seat===1?'房主先手':'加入者后手'}`;}
    }else if(state.status==='won'){
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
    const liveRoom=mode==='online'&&remote&&['playing','won','draw'].includes(remote.phase)&&remoteConnection==='connected'&&!online.busy&&!remote.request;
    $('undoMove').disabled=mode==='online'?!(liveRoom&&state.moves.some(move=>move.player===remote.seat)):mode==='local'?!state.moves.length:lastHumanIndex()<0;
    $('undoMove').textContent=mode==='online'?'申请悔棋':'悔棋';
    $('newGame').textContent=mode==='online'?'再来一局':'开始新局';$('newGame').disabled=mode==='online'&&!liveRoom;
    $('gameMode').disabled=online.busy;
    $('humanSideControl').hidden=mode!=='ai';
    $('settingNote').textContent=mode==='online'?'切换模式或返回大厅会保留房间。':'更改模式或执子将开始新局。';
    renderRoom();
    const history=$('moveHistory');history.replaceChildren();
    state.moves.slice(-8).forEach((move,index)=>{
      const item=make('li');item.append(make('span','history-number',String(Math.max(0,state.moves.length-8)+index+1)),make('span',`history-stone${move.player===2?' white':''}`),make('span','',move.player===1?'黑子':'白子'),make('span','history-position',position(move)));history.append(item);
    });
    $('emptyHistory').hidden=Boolean(state.moves.length);
    document.querySelector('[data-game="gomoku"]').textContent=mode==='online'&&remote&&remote.phase!=='closed'?'继续远程对局':started?'继续对局':'开始游戏';
  }
  function renderRoom() {
    $('onlinePanel').hidden=mode!=='online';
    const labels={paused:'未连接',connecting:'连接中',connected:'已连接',reconnecting:'重连中'};
    $('onlineConnection').textContent=remote?.phase==='closed'?'已结束':labels[remoteConnection];
    const inRoom=remote&&remote.phase!=='closed';$('roomSetup').hidden=Boolean(inRoom);$('roomInfo').hidden=!remote;
    $('currentRoomCode').textContent=remote?.code||'';
    const players=$('roomPlayers');players.replaceChildren();
    if(remote)for(let seat=1;seat<=2;seat++){
      const player=remote.players.find(player=>player.seat===seat),row=make('div','room-player');
      row.append(make('span',`history-stone${seat===2?' white':''}`),make('span','room-player-name',player?`${player.name}${seat===remote.seat?'（你）':''}`:'等待加入'),make('span',`room-player-presence${player?.online?' is-online':''}`,player?(player.online?'在线':'暂离'):'空位'));players.append(row);
    }
    $('createRoom').disabled=online.busy;$('joinRoom').disabled=online.busy;
    $('copyRoomLink').disabled=!inRoom;$('leaveRoom').disabled=online.busy||!remote;
    $('roomRequest').hidden=!inRoom||!remote.request;
    if(remote?.request){const mine=remote.request.by===remote.seat,description=remote.request.type==='undo'?'悔棋':'重新开局';$('roomRequestText').textContent=mine?`已申请${description}，等待对方同意。`:`对方申请${description}，是否同意？`;$('requestResponses').hidden=mine;}
    $('acceptRequest').disabled=$('rejectRequest').disabled=online.busy||remoteConnection!=='connected';
    $('onlineNotice').textContent=remoteNotice;$('onlineNotice').hidden=!remoteNotice;
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
    if(mode==='online'){setFocus(row*core.SIZE+col,false);online.action('moves',{row,col});return;}
    const next=core.play(state,row,col);if(next===state)return;
    state=next;setFocus(row*core.SIZE+col,false);render();scheduleComputer();
  }
  function restart() {
    if(mode==='online'){online.action('requests',{type:'restart'});return;}
    resetLocal();
  }
  function resetLocal() {cancelComputer();state=core.createGame();human=Number($('humanSide').value);setFocus(7*core.SIZE+7,false);render();scheduleComputer();}
  function changeMode() {
    cancelComputer();online.pause();mode=$('gameMode').value;
    if(mode==='online'){state=remote?.game||core.createGame();render();if(active&&remote&&remote.phase!=='closed')online.resume();}
    else resetLocal();
  }
  function openGomoku() {
    active=true;started=true;$('gameLobby').hidden=true;$('gomokuArena').hidden=false;
    if(location.hash!=='#gomoku')history.replaceState(null,'','#gomoku');render();scheduleComputer();if(mode==='online'&&remote&&remote.phase!=='closed')online.resume();$('backToLobby').focus({preventScroll:true});
  }
  function openLobby() {cancelComputer();online.pause();active=false;$('gameLobby').hidden=false;$('gomokuArena').hidden=true;history.replaceState(null,'',location.pathname);render();document.querySelector('[data-game="gomoku"]').focus({preventScroll:true});}
  $('newGame').addEventListener('click',restart);
  $('gameMode').addEventListener('change',changeMode);$('humanSide').addEventListener('change',resetLocal);
  $('undoMove').addEventListener('click',()=>{if($('undoMove').disabled)return;if(mode==='online'){online.action('requests',{type:'undo'});return;}cancelComputer();state=core.undo(state,mode==='local'?1:state.moves.length-lastHumanIndex());render();scheduleComputer();});
  $('createRoom').addEventListener('click',()=>online.connect('create'));
  $('roomCode').addEventListener('input',()=>{$('roomCode').value=$('roomCode').value.replace(/\s/g,'').toUpperCase();});
  $('joinRoomForm').addEventListener('submit',event=>{event.preventDefault();online.connect('join',$('roomCode').value.trim().toUpperCase());});
  $('acceptRequest').addEventListener('click',()=>online.action('requests/respond',{accept:true}));$('rejectRequest').addEventListener('click',()=>online.action('requests/respond',{accept:false}));
  $('leaveRoom').addEventListener('click',()=>online.leave());
  $('copyRoomLink').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(online.inviteLink());remoteNotice='邀请链接已复制，发送给好友即可加入。';}catch{remoteNotice=`请复制房间号 ${remote.code}，让好友登录后加入。`;}render();});
  $('backToLobby').addEventListener('click',openLobby);
  window.addEventListener('hashchange',()=>{if(location.hash==='#gomoku')openGomoku();else if(active)openLobby();});
  window.addEventListener('pagehide',()=>{cancelComputer();online.pause();});
  window.addEventListener('pageshow',()=>{render();scheduleComputer();if(active&&mode==='online'&&remote)online.resume();});
  document.addEventListener('visibilitychange',()=>{if(mode!=='online'||!active)return;if(document.hidden)online.pause();else if(remote)online.resume();});
  render();if(location.hash==='#gomoku')openGomoku();
  online.ready.then(savedCode=>{
    const invite=new URL(location.href).searchParams.get('room')?.trim().toUpperCase();
    if(invite&&/^[A-Z0-9]{6}$/.test(invite)){$('gameMode').value='online';changeMode();openGomoku();$('roomCode').value=invite;online.connect('join',invite);}
    else if(savedCode){$('gameMode').value='online';changeMode();openGomoku();online.connect('resume',savedCode);}
  }).catch(()=>{});
})();
