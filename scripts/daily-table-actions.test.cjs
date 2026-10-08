const {test}=require('node:test');
const assert=require('node:assert/strict');
const {csv,createSearch}=require('../frontend/daily-table-actions.js');

test('CSV preserves quotes, comma, newline, Chinese text and spreadsheet-safe strings',()=>{
  const data=csv([{名称:'中文,"引号"\n换行',账户ID:'7686336798147510315',备注:' =SUM(1,2)',消耗:-12.5,现金ROI:1.2345}],['名称','账户ID','备注','消耗','现金ROI']);
  assert.equal(data,'\uFEFF"名称","账户ID","备注","消耗","现金ROI"\r\n"中文,""引号""\n换行","\'7686336798147510315","\' =SUM(1,2)","-12.5","123.45%"');
  assert.equal(csv([{名称:'@test'},{名称:'\t=x'}],['名称']),'\uFEFF"名称"\r\n"\'@test"\r\n"\'\t=x"');
});

test('search supports multiple words, full-width text and IDs without matching metrics',()=>{
  const search=createSearch(),rows=[{名称:'优化师Ａ 投放',账户ID:'123',消耗:555},{名称:'优化师 B 投放',账户ID:'456',消耗:123}];
  assert.deepEqual(search(rows,['名称','账户ID'],'a 123'),[rows[0]]);
  assert.deepEqual(search(rows,['名称','账户ID'],'555'),[]);
  assert.equal(search(rows,['名称','账户ID'],'   '),rows);
});

test('repeated searches reuse indexes; refreshed arrays or search fields invalidate them',()=>{
  const search=createSearch();let reads=0;
  const rows=Array.from({length:10000},(_,i)=>({get 名称(){reads++;return '优化师 '+i;},账户ID:String(i)}));
  const first=search(rows,['名称'],'优化师 1');
  assert.equal(reads,10000);reads=0;
  assert.equal(search(rows,['名称'],'优化师 1'),first);
  search(rows,['名称'],'优化师 2');assert.equal(reads,0);
  assert.notEqual(search([...rows],['名称'],'优化师 1'),first);assert.equal(reads,10000);
  assert.deepEqual(search(rows,['账户ID'],'9999'),[rows[9999]]);
});
