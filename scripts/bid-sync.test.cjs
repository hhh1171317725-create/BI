const {test}=require('node:test');
const assert=require('node:assert/strict');
const core=require('../browser-extension/ad-tools-helper/bid-sync-core.js');
const row={promotion_id:'1874489175001681',media_account_id:'7676449794404745237',promotion_name:'test',stat_cost:10,convert_cnt:2,active_register:4,cpa_bid:5};
const response=(rows,total=rows.length)=>({code:0,data:{list:rows,total_count:total}});
test('uses current date and serialized conditions without copied cookies',()=>{
 const body=core.body('2026-09-03',2);assert.equal(body.page,2);assert.equal(body.start_date,body.end_date);
 assert.deepEqual(JSON.parse(body.conditions).media_account_id,[]);assert.ok(!JSON.stringify(body).includes('cookie'));
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
