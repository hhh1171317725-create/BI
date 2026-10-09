const assert=require('node:assert/strict'),G=require('../frontend/go-core.js');
const play=(state,index)=>{const result=G.play(state,index);assert.ok(!result.error,result.error);return result.state;};
let s=G.create();const original=s;s=play(s,1);assert.equal(original.board[1],0);s=play(s,0);s=play(s,9);assert.equal(s.board[0],0);assert.equal(s.captures[1],1);assert.ok(G.play(s,0).error);assert.ok(G.play(s,1).error);s=G.undo(s);assert.equal(s.board[0],2);assert.equal(s.captures[1],0);
// A white stone at B2 is captured at B3; immediate recapture repeats the board.
s=G.create();[[1,1],[9,1],[11,1],[10,2],[18,2],[20,2],[28,2]].forEach(([i,c])=>s.board[i]=c);s.positions=[s.board.join('')];s=play(s,19);assert.equal(s.board[10],0);assert.ok(G.play(s,10).error);
const before=s;s=G.pass(s);s=G.pass(s);assert.equal(s.phase,'scoring');assert.equal(G.undo(s).phase,'playing');s=G.resume(s);assert.equal(s.passes,0);assert.equal(s.turn,before.turn);
assert.equal(G.resign(s).winner,3-s.turn);
for(const size of [9,13,19]){s=G.create(size);assert.equal(s.board.length,size*size);const score=G.score(s);assert.equal(score.neutral,size*size);assert.equal(score.black,0);assert.equal(score.white,7.5);}
s=G.create();s.board.fill(1);s.board[40]=0;let result=G.score(s);assert.equal(result.black,81);assert.equal(result.territory[1],1);
s.board[40]=2;result=G.score(s,[40]);assert.equal(result.black,81);assert.equal(s.board[40],2);assert.equal(result.white,7.5);
console.log('PASS Go rules: capture, suicide, occupied point, superko, undo, pass, resume, resign, area score, komi and board sizes');
