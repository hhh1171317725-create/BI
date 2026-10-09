/* Local Go: area scoring, positional superko, no suicide. No network or storage. */
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.BIGo=api;})(globalThis,()=>{
  'use strict';
  const key=board=>board.join('');
  function create(size=9){if(![9,13,19].includes(size))throw Error('棋盘尺寸无效');const board=Array(size*size).fill(0);return {size,board,turn:1,captures:[0,0,0],passes:0,phase:'playing',history:[],positions:[key(board)],last:null,winner:null};}
  function neighbors(i,size){const row=Math.floor(i/size),col=i%size;return [row>0?i-size:-1,row<size-1?i+size:-1,col>0?i-1:-1,col<size-1?i+1:-1].filter(n=>n>=0);}
  function group(board,index,size){const color=board[index],stones=new Set([index]),liberties=new Set(),stack=[index];while(stack.length){for(const n of neighbors(stack.pop(),size)){if(!board[n])liberties.add(n);else if(board[n]===color&&!stones.has(n)){stones.add(n);stack.push(n);}}}return {stones:[...stones],liberties};}
  function save(state,changes){const {history,...snapshot}=state;return {...state,...changes,history:[...history,snapshot]};}
  function play(state,index){
    if(state.phase!=='playing')return {error:'本局已停止落子，请继续对局或重新开局。'};
    if(!Number.isInteger(index)||index<0||index>=state.board.length||state.board[index])return {error:'请选择空交点。'};
    const board=[...state.board],opponent=3-state.turn;board[index]=state.turn;let removed=0;
    for(const n of neighbors(index,state.size)){if(board[n]!==opponent)continue;const chain=group(board,n,state.size);if(!chain.liberties.size){removed+=chain.stones.length;chain.stones.forEach(i=>board[i]=0);}}
    if(!group(board,index,state.size).liberties.size)return {error:'此处落子无气，不能自杀。'};
    const position=key(board);if(state.positions.includes(position))return {error:'禁止重复已有局面，请先在别处落子。'};
    const captures=[...state.captures];captures[state.turn]+=removed;
    return {state:save(state,{board,turn:opponent,captures,passes:0,last:index,positions:[...state.positions,position]})};
  }
  function pass(state){return state.phase!=='playing'?state:save(state,{turn:3-state.turn,passes:state.passes+1,last:null,phase:state.passes===1?'scoring':'playing'});}
  function undo(state){if(!state.history.length)return state;return {...state.history.at(-1),history:state.history.slice(0,-1)};}
  function resume(state){return state.phase==='scoring'?save(state,{phase:'playing',passes:0}):state;}
  function resign(state){return state.phase==='playing'?save(state,{phase:'finished',winner:3-state.turn}):state;}
  function score(state,dead=[],komi=7.5){
    const board=[...state.board];for(const i of dead)board[i]=0;
    const stones=[0,0,0],territory=[0,0,0],visited=new Set();let neutral=0;
    board.forEach((color,i)=>{if(color){stones[color]++;return;}if(visited.has(i))return;const region=[],border=new Set(),stack=[i];visited.add(i);
      while(stack.length){const point=stack.pop();region.push(point);for(const n of neighbors(point,state.size)){if(board[n])border.add(board[n]);else if(!visited.has(n)){visited.add(n);stack.push(n);}}}
      if(border.size===1)territory[[...border][0]]+=region.length;else neutral+=region.length;
    });
    return {stones,territory,neutral,black:stones[1]+territory[1],white:stones[2]+territory[2]+komi,komi};
  }
  return {create,play,pass,undo,resume,resign,score,group};
});
