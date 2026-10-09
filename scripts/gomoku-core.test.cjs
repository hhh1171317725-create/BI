const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { SIZE, createGame, play, undo, chooseMove } = require('../frontend/gomoku-core.js');

function sequence(moves) {
  return moves.reduce((game, [row, col]) => play(game, row, col), createGame());
}

function blackLine(points) {
  const moves = [];
  points.forEach((point, index) => {
    moves.push(point);
    if (index < points.length - 1) moves.push([14, index * 2]);
  });
  return sequence(moves);
}

function drawGame() {
  const positions = [[], []];
  for (let row = 0; row < SIZE; row++) {
    for (let col = 0; col < SIZE; col++) {
      positions[(row + 2 * col) % 4 < 2 ? 0 : 1].push([row, col]);
    }
  }
  const moves = [];
  positions[0].forEach((point, index) => {
    moves.push(point);
    if (positions[1][index]) moves.push(positions[1][index]);
  });
  return sequence(moves);
}

test('new games have independent 15 × 15 boards and black starts', () => {
  const first = createGame(), second = createGame();
  assert.equal(SIZE, 15);
  assert.equal(first.board.length, SIZE);
  assert.ok(first.board.every(row => row.length === SIZE && row.every(cell => cell === 0)));
  assert.deepEqual({ ...first, board: undefined }, {
    board: undefined, moves: [], currentPlayer: 1, status: 'playing', winner: null, winningLine: []
  });
  first.board[0][0] = 1;
  assert.equal(first.board[1][0], 0);
  assert.equal(second.board[0][0], 0);
});

test('valid moves alternate players without changing prior states', () => {
  const original = createGame(), first = play(original, 0, 14), second = play(first, 14, 0);
  assert.equal(original.board[0][14], 0);
  assert.deepEqual(original.moves, []);
  assert.equal(first.board[14][0], 0);
  assert.equal(first.currentPlayer, 2);
  assert.equal(second.currentPlayer, 1);
  assert.equal(second.board[0][14], 1);
  assert.equal(second.board[14][0], 2);
  assert.deepEqual(second.moves, [{ row: 0, col: 14, player: 1 }, { row: 14, col: 0, player: 2 }]);
  assert.notEqual(first.board, second.board);
  assert.notEqual(first.board[0], second.board[0]);
});

test('illegal coordinates and occupied cells return the original state', () => {
  const game = play(createGame(), 7, 7);
  for (const [row, col] of [
    [-1, 0], [0, -1], [15, 0], [0, 15], [1.5, 2], [2, 1.5],
    [NaN, 2], [2, Infinity], ['7', 7], [7, '7'], [undefined, 0], [null, 0], [7, 7]
  ]) assert.equal(play(game, row, col), game);
  assert.equal(game.moves.length, 1);
  assert.equal(game.currentPlayer, 2);
});

for (const [name, points] of [
  ['horizontal', [[7, 3], [7, 4], [7, 5], [7, 6], [7, 7]]],
  ['vertical', [[3, 4], [4, 4], [5, 4], [6, 4], [7, 4]]],
  ['descending diagonal', [[3, 3], [4, 4], [5, 5], [6, 6], [7, 7]]],
  ['ascending diagonal', [[3, 11], [4, 10], [5, 9], [6, 8], [7, 7]]]
]) {
  test(`${name} five wins and records the winning cells`, () => {
    const game = blackLine(points);
    assert.equal(game.status, 'won');
    assert.equal(game.winner, 1);
    assert.deepEqual(game.winningLine, points.map(([row, col]) => ({ row, col })));
    assert.equal(play(game, 0, 0), game);
    assert.equal(chooseMove(game), null);
  });
}

test('joining two shorter runs into six wins under free rules', () => {
  const points = [2, 3, 4, 6, 7, 5].map(col => [7, col]);
  const game = blackLine(points);
  assert.equal(game.status, 'won');
  assert.equal(game.winner, 1);
  assert.deepEqual(game.winningLine, [2, 3, 4, 5, 6, 7].map(col => ({ row: 7, col })));
});

test('white can win at the edge and four stones are not a win', () => {
  let game = createGame();
  for (let col = 0; col < 4; col++) {
    game = play(game, 14, col * 2);
    game = play(game, 0, col);
  }
  assert.equal(game.status, 'playing');
  game = play(game, 12, 12);
  game = play(game, 0, 4);
  assert.equal(game.status, 'won');
  assert.equal(game.winner, 2);
  assert.deepEqual(game.winningLine, [0, 1, 2, 3, 4].map(col => ({ row: 0, col })));
});

test('undo rebuilds board, move history, and turn without mutating the input', () => {
  const game = sequence([[7, 7], [6, 6], [8, 8]]);
  const snapshot = JSON.stringify(game);
  const oneBack = undo(game), twoBack = undo(game, 2), reset = undo(game, 99);
  assert.deepEqual(oneBack, sequence([[7, 7], [6, 6]]));
  assert.deepEqual(twoBack, sequence([[7, 7]]));
  assert.deepEqual(reset, createGame());
  assert.equal(JSON.stringify(game), snapshot);
  for (const steps of [0, -1, 1.5, NaN, Infinity, '1']) assert.equal(undo(game, steps), game);
  const empty = createGame();
  assert.equal(undo(empty), empty);
});

test('undo reopens a won game and permits a different next move', () => {
  const won = blackLine([0, 1, 2, 3, 4].map(col => [0, col]));
  const game = undo(won);
  assert.equal(game.status, 'playing');
  assert.equal(game.winner, null);
  assert.deepEqual(game.winningLine, []);
  assert.equal(game.currentPlayer, 1);
  assert.equal(game.board[0][4], 0);
  assert.equal(play(game, 8, 8).status, 'playing');
  assert.equal(play(game, 0, 4).status, 'won');
});

test('a complete board with no five is a draw; undo reopens it', () => {
  const game = drawGame();
  assert.equal(game.moves.length, SIZE * SIZE);
  assert.ok(game.board.every(row => row.every(cell => cell !== 0)));
  assert.equal(game.status, 'draw');
  assert.equal(game.winner, null);
  assert.deepEqual(game.winningLine, []);
  assert.equal(play(game, 0, 0), game);
  assert.equal(chooseMove(game), null);
  const reopened = undo(game);
  assert.equal(reopened.status, 'playing');
  assert.equal(reopened.currentPlayer, 1);
  const last = game.moves.at(-1);
  assert.equal(reopened.board[last.row][last.col], 0);
  assert.equal(play(reopened, last.row, last.col).status, 'draw');
});

test('AI opens in the center and returns no move on a full board', () => {
  assert.deepEqual(chooseMove(createGame()), { row: 7, col: 7 });
  const full = drawGame();
  assert.equal(chooseMove({ ...full, status: 'playing' }), null);
  assert.equal(chooseMove(createGame(), 0), null);
});

test('AI takes its immediate win before blocking the opponent', () => {
  const moves = [];
  for (let col = 0; col < 4; col++) moves.push([0, col], [14, col]);
  const game = sequence(moves), snapshot = JSON.stringify(game);
  assert.deepEqual(chooseMove(game), { row: 0, col: 4 });
  assert.equal(play(game, 0, 4).winner, 1);
  assert.equal(JSON.stringify(game), snapshot);
});

test('AI blocks an opponent immediate win and supports a player override', () => {
  const moves = [];
  for (let col = 0; col < 4; col++) moves.push([14, col * 2], [0, col]);
  const game = sequence(moves);
  assert.deepEqual(chooseMove(game), { row: 0, col: 4 });
  assert.deepEqual(chooseMove(game, 2), { row: 0, col: 4 });
});

test('AI recognizes a winning gap instead of only extending line ends', () => {
  const game = blackLine([2, 3, 5, 6].map(col => [7, col]));
  // blackLine leaves the turn on white; explicitly ask for black's best move.
  assert.deepEqual(chooseMove(game, 1), { row: 7, col: 4 });
});

test('AI develops near existing stones deterministically without altering state', () => {
  const game = sequence([[7, 7], [0, 0]]), snapshot = JSON.stringify(game);
  const move = chooseMove(game);
  assert.deepEqual(chooseMove(game), move);
  assert.equal(game.board[move.row][move.col], 0);
  assert.ok(Math.abs(move.row - 7) <= 2 && Math.abs(move.col - 7) <= 2);
  assert.equal(JSON.stringify(game), snapshot);
});

test('browser script exposes the same API on window.BIGomoku', () => {
  const context = { window: {} };
  vm.runInNewContext(fs.readFileSync(require.resolve('../frontend/gomoku-core.js'), 'utf8'), context);
  const browser = context.window.BIGomoku;
  assert.equal(browser.SIZE, 15);
  assert.equal(browser.createGame().board[7][7], 0);
  assert.equal(browser.play(browser.createGame(), 7, 7).board[7][7], 1);
  assert.equal(browser.chooseMove(browser.createGame()).row, 7);
});
