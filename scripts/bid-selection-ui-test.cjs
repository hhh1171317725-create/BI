const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const frontend=path.join(__dirname,'../frontend');
const workspace=fs.readFileSync(path.join(frontend,'bid-monitor-workspace.js'),'utf8');
const report=fs.readFileSync(path.join(frontend,'bid-monitor.js'),'utf8');

(async()=>{
 let browser;
 try{
  browser=await chromium.launch({headless:true,channel:'chrome'});
  const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.setContent('<select id="viewMode"><option value="plans">plans</option></select><button id="batchExport">export</button><div id="batchCount"></div><table><thead id="tableHead"><tr></tr></thead><tbody id="rows"></tbody></table>');
  await page.addScriptTag({content:fs.readFileSync(path.join(frontend,'bid-monitor-core.js'),'utf8')});
  await page.addScriptTag({content:`
   const make=(tag,className)=>{const element=document.createElement(tag);if(className)element.className=className;return element;};
   const batchToggle=make('button'),batchBar=make('div'),batchCount=document.getElementById('batchCount'),batchExport=document.getElementById('batchExport'),batchClear=make('button');
   const esc=value=>String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
   const fmt=value=>value==null?'--':Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}),fmtPercent=value=>fmt(value*100)+'%',fmtRoi=fmt;
   const textSortKeys=new Set(['name','account','platform']),names={};const compensationWarningsReady=()=>false;const gapTitle=()=>'';
   let analyzed=[],visible=[],columns=[['计划','name'],['账户','account'],['平台','platform'],['总消耗','cost']];const activePlanColumns=()=>columns;
   ${report.slice(report.indexOf('function planCell('),report.indexOf('const aggregateColumns='))}
   ${workspace.slice(workspace.indexOf('let selectionMode='),workspace.indexOf('function drawTableSummary('))}
   ${workspace.slice(workspace.indexOf('batchExport.onclick='),workspace.indexOf("const density=make("))}
   selectionMode=true;window.csvExports=[];URL.createObjectURL=blob=>{window.csvExports.push(blob.text());return 'blob:selection-test';};URL.revokeObjectURL=()=>{};
   const create=document.createElement.bind(document);document.createElement=tag=>{const element=create(tag);if(tag==='a')element.click=()=>{};return element;};
   window.setData=rows=>{analyzed=rows;};window.setColumns=next=>{columns=next;document.getElementById('tableHead').innerHTML='<tr>'+columns.map(([label])=>'<th>'+esc(label)+'</th>').join('')+'</tr>';};
   window.setPage=rows=>{visible=rows;document.getElementById('rows').innerHTML=rows.map((row,index)=>'<tr>'+columns.map(([,key])=>planCell(row,key,index)).join('')+'</tr>').join('');enhanceSelection();};
  `});
  const data=[{id:'123',name:'Byte A',account:'Account A',accountId:'a',platform:'字节',cost:100},{id:'123',name:'Byte B',account:'Account B',accountId:'b',platform:'字节',cost:200},{id:'123',name:'GDT A',account:'Account A',accountId:'a',platform:'广点通',cost:300}];
  await page.evaluate(data=>{window.setData(data);window.setPage(data.slice(0,2));},data);
  await page.locator('#tableHead input').check();assert.equal(await page.locator('#batchCount').textContent(),'已选择 2 条');
  await page.evaluate(data=>window.setPage(data.slice(2)),data);await page.locator('#rows input').check();
  assert.equal(await page.locator('#batchCount').textContent(),'已选择 3 条');
  await page.evaluate(data=>{window.setColumns([['总消耗','cost'],['计划','name']]);window.setPage(data.slice(2));},data);
  assert.equal(await page.locator('#rows input').isChecked(),true);await page.locator('#batchExport').click();
  const csv=await page.evaluate(()=>window.csvExports.at(-1));
  assert.deepEqual(csv.replace(/^\ufeff/,'').split('\r\n'),['"总消耗","计划"','"100.00","Byte A / 123"','"200.00","Byte B / 123"','"300.00","GDT A / 123"']);
  await page.evaluate(data=>{const updated={...data[0],name:'Byte A latest',cost:999};window.setData([updated]);window.setPage([updated]);},data);
  assert.equal(await page.locator('#batchCount').textContent(),'已选择 1 条');await page.locator('#batchExport').click();
  const refreshed=await page.evaluate(()=>window.csvExports.at(-1));assert.match(refreshed,/999.00/);assert.match(refreshed,/Byte A latest/);assert.doesNotMatch(refreshed,/Byte B|GDT A/);
  assert.deepEqual(errors,[]);console.log('Bid selection DOM regression passed: identities, paging, columns, current data, export.');
 }finally{if(browser)await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
