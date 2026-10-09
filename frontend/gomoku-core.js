(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.BIGomoku = factory();
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';

  var SIZE = 15;
  var DIRECTIONS = [[0, 1], [1, 0], [1, 1], [1, -1]];

  function createGame() {
    return {
      board: Array.from({ length: SIZE }, function () { return Array(SIZE).fill(0); }),
      moves: [],
      currentPlayer: 1,
      status: 'playing',
      winner: null,
      winningLine: []
    };
  }

  function inside(row, col) {
    return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
  }

  // The center is treated as a newly placed stone, so AI checks never edit the board.
  function lineThrough(board, row, col, player, dr, dc) {
    var before = [], after = [];
    var r = row - dr, c = col - dc;
    while (inside(r, c) && board[r][c] === player) {
      before.push({ row: r, col: c });
      r -= dr;
      c -= dc;
    }
    r = row + dr;
    c = col + dc;
    while (inside(r, c) && board[r][c] === player) {
      after.push({ row: r, col: c });
      r += dr;
      c += dc;
    }
    return before.reverse().concat([{ row: row, col: col }], after);
  }

  function winningLineFor(board, row, col, player) {
    for (var i = 0; i < DIRECTIONS.length; i++) {
      var direction = DIRECTIONS[i];
      var line = lineThrough(board, row, col, player, direction[0], direction[1]);
      if (line.length >= 5) return line;
    }
    return [];
  }

  function play(state, row, col) {
    if (!state || state.status !== 'playing' ||
        !Number.isInteger(row) || !Number.isInteger(col) || !inside(row, col) ||
        state.board[row][col] !== 0) return state;

    var player = state.currentPlayer;
    var board = state.board.map(function (cells) { return cells.slice(); });
    board[row][col] = player;
    var moves = state.moves.concat([{ row: row, col: col, player: player }]);
    var winningLine = winningLineFor(board, row, col, player);
    var status = winningLine.length ? 'won' :
      (moves.length === SIZE * SIZE ? 'draw' : 'playing');
    return {
      board: board,
      moves: moves,
      currentPlayer: 3 - player,
      status: status,
      winner: winningLine.length ? player : null,
      winningLine: winningLine
    };
  }

  function undo(state, steps) {
    if (steps === undefined) steps = 1;
    if (!state || !Number.isInteger(steps) || steps < 1 || !state.moves.length) return state;
    var remaining = state.moves.slice(0, Math.max(0, state.moves.length - steps));
    return remaining.reduce(function (game, move) {
      return play(game, move.row, move.col);
    }, createGame());
  }

  function directionScore(board, row, col, player, dr, dc) {
    var count = 1, open = 0;
    for (var sign = -1; sign <= 1; sign += 2) {
      var r = row + dr * sign, c = col + dc * sign;
      while (inside(r, c) && board[r][c] === player) {
        count++;
        r += dr * sign;
        c += dc * sign;
      }
      if (inside(r, c) && board[r][c] === 0) open++;
    }

    var score = 0;
    if (count >= 5) score = 10000000;
    else if (open) {
      var values = open === 2 ? [0, 20, 500, 15000, 500000] : [0, 5, 80, 1500, 70000];
      score = values[count];
    }

    // Five-cell windows also reward broken lines, e.g. XX.X and X.XX.
    var windowValues = [0, 1, 12, 120, 4000, 1000000];
    for (var offset = -4; offset <= 0; offset++) {
      var stones = 0, blocked = false;
      for (var step = 0; step < 5; step++) {
        var distance = offset + step;
        var wr = row + dr * distance, wc = col + dc * distance;
        if (!inside(wr, wc)) { blocked = true; break; }
        var cell = distance === 0 ? player : board[wr][wc];
        if (cell !== 0 && cell !== player) { blocked = true; break; }
        if (cell === player) stones++;
      }
      if (!blocked) score += windowValues[stones];
    }
    return score;
  }

  function positionScore(board, row, col, player) {
    return DIRECTIONS.reduce(function (score, direction) {
      return score + directionScore(board, row, col, player, direction[0], direction[1]);
    }, 0);
  }

  function nearbyScore(board, row, col) {
    var score = 0;
    for (var dr = -2; dr <= 2; dr++) {
      for (var dc = -2; dc <= 2; dc++) {
        if ((!dr && !dc) || !inside(row + dr, col + dc)) continue;
        if (board[row + dr][col + dc]) score += 3 - Math.max(Math.abs(dr), Math.abs(dc));
      }
    }
    return score;
  }

  function chooseMove(state, player) {
    if (!state || state.status !== 'playing') return null;
    if (player === undefined) player = state.currentPlayer;
    if (player !== 1 && player !== 2) return null;

    var board = state.board, empty = [], hasStone = false;
    var center = (SIZE - 1) / 2;
    for (var row = 0; row < SIZE; row++) {
      for (var col = 0; col < SIZE; col++) {
        if (board[row][col] === 0) empty.push({ row: row, col: col });
        else hasStone = true;
      }
    }
    if (!empty.length) return null;
    if (!hasStone) return { row: center, col: center };

    // Sorting makes equal choices stable and prefers central cells.
    empty.sort(function (a, b) {
      var distanceA = Math.abs(a.row - center) + Math.abs(a.col - center);
      var distanceB = Math.abs(b.row - center) + Math.abs(b.col - center);
      return distanceA - distanceB || a.row - b.row || a.col - b.col;
    });
    var opponent = 3 - player;
    var winning = empty.filter(function (move) {
      return winningLineFor(board, move.row, move.col, player).length;
    });
    if (winning.length) return winning[0];

    var threats = empty.filter(function (move) {
      return winningLineFor(board, move.row, move.col, opponent).length;
    });
    var candidates = threats.length ? threats : empty.filter(function (move) {
      return nearbyScore(board, move.row, move.col) > 0;
    });
    var best = null, bestScore = -Infinity;
    candidates.forEach(function (move) {
      var score = positionScore(board, move.row, move.col, player) +
        positionScore(board, move.row, move.col, opponent) * 1.1 +
        nearbyScore(board, move.row, move.col) * 4 +
        (SIZE - 1 - Math.abs(move.row - center) - Math.abs(move.col - center));
      if (score > bestScore) {
        best = move;
        bestScore = score;
      }
    });
    return best;
  }

  return { SIZE: SIZE, createGame: createGame, play: play, undo: undo, chooseMove: chooseMove };
});
