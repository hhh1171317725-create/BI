const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const B=require('../frontend/bid-monitor-core.js');
const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-workspace.js'),'utf8');
const selection=source.slice(source.indexOf('let selectionMode='),source.indexOf('function drawTableSummary('));
const exportHandler=source.slice(source.indexOf('batchExport.onclick='),source.indexOf("const density=make("));

function setup(data){
 const boxes=[],exports=[],state={rows:[],columns:[['计划','name'],['账户','account'],['总消耗','cost']]};
 const make=tag=>({tag,children:[],setAttribute(){},append(child){this.children.push(child);},set innerHTML(value){this.firstElementChild={childNodes:[{textContent:value.replace(/<[^>]+>/g,'')}]};}});
 const element=()=>({setAttribute(){}}),batchExport=element();
 const document={getElementById:()=>({value:'plans'}),querySelector:()=>null,querySelectorAll:selector=>selector==='#rows tr'?state.rows:[],createElement:()=>({click(){}})};
 const context=vm.createContext({document,window:{BidMonitor:B},analyzed:data,visible:data,make:tag=>{const item=make(tag);if(tag==='input')boxes.push(item);return item;},
  batchToggle:element(),batchBar:element(),batchCount:element(),batchExport,batchClear:element(),activePlanColumns:()=>state.columns,
  planCell:(row,key)=>`<td>${row[key]??'--'}</td>`,Blob:class{constructor(parts){this.parts=parts;}},URL:{createObjectURL:blob=>{exports.push(blob.parts.join(''));return 'blob:test';},revokeObjectURL(){}},setTimeout(){}});
 vm.runInContext(selection+';selectionMode=true;this.selectedRows=selectedRows;',context);vm.runInContext(exportHandler,context);
 const page=rows=>{
  context.visible=rows;state.rows=rows.map((item,index)=>({querySelector(selector){if(selector==='.plan-detail-link')return {dataset:{planDetail:String(index)},textContent:item.name};if(selector==='.ocean-select-cell')return this.cell||null;return null;},prepend(cell){this.cell=cell;}}));
  boxes.length=0;context.enhanceSelection();return boxes;
 };
 const select=box=>{box.checked=true;box.onchange();};
 return {context,state,page,select,exports,export:()=>batchExport.onclick()};
}

test('batch selection keeps equal plan IDs separate across platforms and accounts',()=>{
 const data=[{id:'123',name:'Byte A',account:'A',accountId:'a',platform:'字节',cost:100},{id:'123',name:'Byte B',account:'B',accountId:'b',platform:'字节',cost:200},{id:'123',name:'GDT A',account:'A',accountId:'a',platform:'广点通',cost:300}];
 const {context,page,select,exports,export:exportRows}=setup(data);page(data).forEach(select);
 assert.equal(context.selectedRows.size,3);exportRows();
 assert.equal(exports[0].split('\r\n').length,4);assert.match(exports[0],/Byte A/);assert.match(exports[0],/Byte B/);assert.match(exports[0],/GDT A/);
});

test('selections survive paging and filters; current export columns are applied to every selected object',()=>{
 const data=[{id:'1',name:'First',accountId:'a',account:'A',platform:'字节',cost:100},{id:'2',name:'Second',accountId:'b',account:'B',platform:'字节',cost:200}];
 const {context,state,page,select,exports,export:exportRows}=setup(data);
 select(page([data[0]])[0]);select(page([data[1]])[0]);
 state.columns=[['总消耗','cost'],['计划','name']];page([data[1]]);exportRows();
 assert.equal(context.selectedRows.size,2);
 assert.deepEqual(exports[0].replace(/^\ufeff/,'').split('\r\n'),['"总消耗","计划"','"100","First"','"200","Second"']);
 assert.equal(page([data[0]])[0].checked,true);
});

test('new report data refreshes selected values and removes plans that disappeared from the loaded report',()=>{
 const data=[{id:'1',name:'First',accountId:'a',account:'A',platform:'字节',cost:100},{id:'2',name:'Second',accountId:'b',account:'B',platform:'字节',cost:200}];
 const {context,page,select,exports,export:exportRows}=setup(data);page(data).forEach(select);
 const latest={...data[0],name:'First updated',cost:999};context.analyzed=[latest];page([latest]);exportRows();
 assert.equal(context.selectedRows.size,1);assert.match(exports[0],/First updated/);assert.match(exports[0],/999/);assert.doesNotMatch(exports[0],/Second/);
});

test('batch export still neutralizes spreadsheet formulas in the currently rendered columns',()=>{
 const row={id:'1',name:'=1+1',accountId:'a',account:'A',platform:'字节',cost:100};
 const {page,select,exports,export:exportRows}=setup([row]);select(page([row])[0]);exportRows();assert.match(exports[0],/"'=1\+1"/);
});
