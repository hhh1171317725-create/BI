const {test} = require('node:test');
const assert = require('node:assert/strict');
const {createRowsCache} = require('../frontend/daily-report-runtime.js');

test('sorting matches previous numeric/Chinese ordering without changing source', () => {
  const cache = createRowsCache();
  const rows = Object.freeze([{名称:'账户10',值:3},{名称:'账户2',值:'1,000'},{名称:'账户甲',值:null},{名称:'账户乙',值:'12'},{名称:'账户丙',值:-8}]);
  for (const column of ['名称','值']) for (const order of ['asc','desc']) {
    const direction = order === 'desc' ? -1 : 1;
    const expected = [...rows].sort((a,b) => {
      const x=Number(a[column]),y=Number(b[column]);
      return (Number.isFinite(x)&&Number.isFinite(y) ? x-y : String(a[column]??'').localeCompare(String(b[column]??''),'zh-CN',{numeric:true,sensitivity:'base'}))*direction;
    });
    assert.deepEqual(cache.sort(rows,{sortColumn:column,sortDirection:order}),expected);
  }
  assert.equal(cache.sort(rows,{}), rows);
});

test('10,000 rows reused across paging without reading sort values again', () => {
  let reads = 0;
  const cache = createRowsCache(), rows = Array.from({length:10000},(_,i)=>({get 消耗(){reads++;return 10000-i;}}));
  const settings = {sortColumn:'消耗',sortDirection:'asc'};
  const first = cache.sort(rows,settings);
  assert.ok(reads>0);reads=0;
  for(let i=0;i<20;i++) assert.equal(cache.sort(rows,settings),first);
  assert.equal(reads,0);
  assert.notEqual(cache.sort([...rows],settings),first);
  assert.ok(reads>0);
  assert.notEqual(cache.sort(rows,{...settings,sortDirection:'desc'}),first);
});

test('filtered date rows reuse and invalidate with source, condition and coercion changes', () => {
  const cache=createRowsCache();
  const rows=Object.freeze([{优化师:'甲',账户:12,日期:'2026-09-01'},{优化师:'甲',账户:'12',日期:'2026-09-03'},{优化师:'乙',账户:'13',日期:'2026-09-02'}]);
  const first=cache.select(rows,{优化师:'甲'});
  assert.equal(cache.select(rows,{优化师:'甲'}),first);
  assert.deepEqual(cache.select(rows,{账户:'12'},true),rows.slice(0,2));
  assert.deepEqual(cache.select(rows,{账户:'12'}),[rows[1]]);
  const sorted=cache.sort(first,{sortColumn:'日期',sortDirection:'desc'});
  assert.deepEqual(sorted,[rows[1],rows[0]]);
  assert.equal(cache.sort(cache.select(rows,{优化师:'甲'}),{sortColumn:'日期',sortDirection:'desc'}),sorted);
  assert.deepEqual(cache.select(rows,{优化师:'乙'}),[rows[2]]);
  assert.notEqual(cache.select([...rows],{优化师:'甲'}),first);
  assert.deepEqual(cache.select(null,{优化师:'甲'}),[]);
});

test('many drilldown choices have a bounded per-source cache', () => {
  const cache=createRowsCache(),rows=[{账户:'0'}];
  const first=cache.select(rows,{账户:'0'});
  for(let i=1;i<=12;i++) cache.select(rows,{账户:String(i)});
  assert.notEqual(cache.select(rows,{账户:'0'}),first);
});
