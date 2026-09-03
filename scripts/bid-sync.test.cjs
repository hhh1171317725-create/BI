const {test}=require('node:test');
const assert=require('node:assert/strict');
const core=require('../browser-extension/ad-tools-helper/bid-sync-core.js');
const row={promotion_id:'1874489175001681',media_account_id:'7676449794404745237',promotion_name:'test',stat_cost:10,convert_cnt:2,active_register:4,cpa_bid:5};
const response=(rows,total=rows.length)=>({code:0,data:{list:rows,total_count:total}});
test('uses current date and serialized conditions without copied cookies',()=>{
 const body=core.body('2026-09-03',2);assert.equal(body.page,2);assert.equal(body.start_date,body.end_date);
 assert.deepEqual(JSON.parse(body.conditions).media_account_id,[]);assert.ok(!JSON.stringify(body).includes('cookie'));
 assert.deepEqual(JSON.parse(body.conditions).deep_bid_type,[]);
 assert.equal(JSON.parse(body.conditions).cdt_start_date,'2026-08-28 00:00:00');
 assert.equal(body.sort_field,'stat_cost');assert.equal(body.sort_direction,'desc');
 const next=core.body('2026-09-03',2,7,4656);assert.equal(next.total_count,4656);assert.equal(next.total_page,47);
});
test('large upstream total still stops at exactly 200 rows',()=>{
 const state={rows:[],ids:new Set(),total:null};
 const chunk=(start)=>core.parse(response(Array.from({length:100},(_,i)=>({...row,promotion_id:String(start+i)})),335367));
 assert.equal(core.append(state,chunk(1)),false);assert.equal(core.append(state,chunk(101)),true);
 assert.equal(state.rows.length,200);assert.throws(()=>core.body('2026-09-03',3));
});
test('fewer than 200 reads actual count and premature end remains an error',()=>{
 const state={rows:[],ids:new Set(),total:null};
 assert.equal(core.append(state,core.parse(response([row],1))),true);
 const partial={rows:[],ids:new Set(),total:null};
 assert.equal(core.append(partial,core.parse(response([row],150))),false);
 assert.throws(()=>core.append(partial,core.parse(response([],150))));
});
test('request ID matches verified upstream format including ff suffix',()=>{
 const id=core.requestId(new Date('2026-09-03T04:54:23Z'));
 assert.match(id,/^20260903125423[0-9a-f]{32}ff$/);assert.equal(id.length,48);
});
test('real nested page_info and advertiser nickname are recognized',()=>{
 const result=core.parse({code:0,data:{list:[{...row,advertiser_nick:'account'}],page_info:{page:1,page_size:100,total_count:4656,total_page:47}}});
 assert.equal(result.total,4656);assert.equal(result.rows[0].media_account_name,'account');
});
test('rolling creation window crosses months and rejects unsupported windows',()=>{
 assert.deepEqual(core.createdRange('2026-09-03',14),{createdStart:'2026-08-21',createdEnd:'2026-09-03'});
 assert.throws(()=>core.createdRange('2026-09-03',999));
});
test('allowlists metrics and preserves string IDs',()=>{
 const parsed=core.parse(response([{...row,cookie:'secret',account_info:{}}]));
 assert.equal(parsed.rows[0].media_account_id,row.media_account_id);assert.equal(parsed.rows[0].cookie,undefined);
});
test('rejects upstream failures, absent totals, unsafe numeric IDs, missing metrics',()=>{
 for(const result of [{code:-1}, {code:0,data:{list:[]}},response([{...row,promotion_id:7676449794404745237}]),response([{...row,cpa_bid:null}])])assert.throws(()=>core.parse(result));
});
test('complete pagination only, rejects duplicates and changed totals',()=>{
 const state={rows:[],ids:new Set(),total:null};assert.equal(core.append(state,core.parse(response([row],2))),false);
 assert.throws(()=>core.append(state,core.parse(response([row],2))));
 assert.throws(()=>core.append(state,core.parse(response([],3))));
 assert.equal(core.append(state,core.parse(response([{...row,promotion_id:'2'}],2))),true);
});
