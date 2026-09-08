const test=require('node:test'),assert=require('node:assert/strict');
const {ranges}=require('../frontend/report-date-presets.js');
test('ranges use Beijing date, not the browser local date',()=>{
 const value=ranges(new Date('2026-09-07T18:00:00Z'));
 assert.deepEqual(value.yesterday,['2026-09-07','2026-09-07']);
 assert.deepEqual(value.week,['2026-09-01','2026-09-07']);
 assert.deepEqual(value.month,['2026-08-09','2026-09-07']);
 assert.deepEqual(value.previousMonth,['2026-08-01','2026-08-31']);
});
test('new year and month boundaries retain complete days',()=>{
 const value=ranges(new Date('2026-01-01T00:00:00+08:00'));
 assert.deepEqual(value.week,['2025-12-25','2025-12-31']);
 assert.deepEqual(value.previousMonth,['2025-12-01','2025-12-31']);
});
test('leap years include February 29',()=>{
 const value=ranges(new Date('2024-03-01T00:00:00+08:00'));
 assert.deepEqual(value.yesterday,['2024-02-29','2024-02-29']);
 assert.deepEqual(value.previousMonth,['2024-02-01','2024-02-29']);
 assert.deepEqual(value.month,['2024-01-31','2024-02-29']);
});
