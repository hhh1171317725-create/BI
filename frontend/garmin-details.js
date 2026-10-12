(() => {
  'use strict';
  const $ = id => document.getElementById(id), dialog = $('dayDialog');
  const groups = {
    overview:['健康概览',['daily','steps','floors','intensity','move_iq']],
    recovery:['心率与恢复',['heart','hrv','stress','battery','battery_events','respiration','spo2']],
    sleep:['睡眠',['sleep']],
    training:['训练表现',['training_readiness','training_status','training_load','daily_training','vo2max','fitness_age','endurance','hill','running_tolerance','race_predictions','ftp','lactate_threshold']],
    body:['身体与生活',['weight','composition','blood_pressure','hydration','lifestyle','nutrition','meals','menstrual']],
    activities:['运动详情',[]],
  };
  const labels = {
    daily:'每日统计',steps:'步数时段',floors:'爬楼',intensity:'强度分钟',move_iq:'自动识别活动',heart:'心率',hrv:'心率变异性 HRV',stress:'压力',battery:'身体电量',battery_events:'身体电量事件',respiration:'呼吸',spo2:'血氧',sleep:'睡眠',
    training_readiness:'训练准备度',training_status:'训练状态',training_load:'四周训练负荷',daily_training:'每日训练状态',vo2max:'最大摄氧量 VO₂ Max',fitness_age:'体能年龄',endurance:'耐力得分',hill:'爬坡得分',running_tolerance:'跑步耐受性',race_predictions:'比赛预测',ftp:'功能性阈值功率 FTP',lactate_threshold:'乳酸阈值',
    weight:'体重记录',composition:'体成分',blood_pressure:'血压',hydration:'饮水',lifestyle:'生活习惯',nutrition:'营养记录',meals:'餐食',menstrual:'生理周期',
    overview:'运动概览',splits:'分段',typed_splits:'分段类型',split_summaries:'分段汇总',weather:'天气',heart_zones:'心率区间',power_zones:'功率区间',exercise_sets:'训练组',gear:'装备',samples:'运动采样与轨迹',
    totalSteps:'总步数',dailyStepGoal:'步数目标',totalDistanceMeters:'距离（米）',activeKilocalories:'活动热量（千卡）',totalKilocalories:'总热量（千卡）',bmrKilocalories:'基础热量（千卡）',restingHeartRate:'静息心率（次/分）',minHeartRate:'最低心率（次/分）',maxHeartRate:'最高心率（次/分）',averageStressLevel:'平均压力',maxStressLevel:'最高压力',stressDuration:'压力时长（秒）',restStressDuration:'休息时长（秒）',lowStressDuration:'低压力时长（秒）',mediumStressDuration:'中压力时长（秒）',highStressDuration:'高压力时长（秒）',
    dailySleepDTO:'睡眠统计',sleepTimeSeconds:'睡眠时长（秒）',deepSleepSeconds:'深睡（秒）',lightSleepSeconds:'浅睡（秒）',remSleepSeconds:'REM 睡眠（秒）',awakeSleepSeconds:'清醒（秒）',sleepScores:'睡眠评分',overall:'总评分',value:'数值',qualifierKey:'评价',avgSleepHRV:'睡眠 HRV（毫秒）',averageSpO2Value:'平均血氧（%）',lowestSpO2Value:'最低血氧（%）',averageRespirationValue:'平均呼吸（次/分）',lowestRespirationValue:'最低呼吸（次/分）',highestRespirationValue:'最高呼吸（次/分）',sleepStartTimestampGMT:'入睡时间（UTC 毫秒）',sleepEndTimestampGMT:'起床时间（UTC 毫秒）',
    lastNightAvg:'夜间平均 HRV（毫秒）',lastNight5MinHigh:'夜间最高 5 分钟 HRV（毫秒）',weeklyAvg:'七日平均 HRV（毫秒）',hrvSummary:'HRV 汇总',baseline:'基线',status:'状态',score:'得分',recoveryTime:'恢复时间',acuteLoad:'急性负荷',chronicLoad:'慢性负荷',trainingEffect:'训练效果',aerobicTrainingEffect:'有氧训练效果',anaerobicTrainingEffect:'无氧训练效果',
    weight:'体重记录',dateWeightList:'称重列表',bodyFat:'体脂率（%）',bodyWater:'体水分（%）',muscleMass:'肌肉量（克）',boneMass:'骨量（克）',bmi:'BMI',systolic:'收缩压',diastolic:'舒张压',pulse:'脉搏',calendarDate:'日期',date:'日期 / 原始时间',startTimeGMT:'开始时间（UTC）',startTimeLocal:'开始时间（设备当地）',endTimeGMT:'结束时间（UTC）',activityName:'运动名称',activityType:'运动类型',typeKey:'类型',duration:'时长（秒）',movingDuration:'移动时长（秒）',elapsedDuration:'经过时长（秒）',distance:'距离（米）',averageHR:'平均心率（次/分）',maxHR:'最高心率（次/分）',averageSpeed:'平均速度（米/秒）',maxSpeed:'最高速度（米/秒）',averagePower:'平均功率（瓦）',maxPower:'最高功率（瓦）',elevationGain:'累计爬升（米）',elevationLoss:'累计下降（米）',calories:'热量（千卡）',stepsGoal:'步数目标',floorsAscended:'上楼层数',floorsDescended:'下楼层数',moderateIntensityMinutes:'中等强度分钟',vigorousIntensityMinutes:'高强度分钟',bodyBatteryChargedValue:'身体电量充电',bodyBatteryDrainedValue:'身体电量消耗',
  };
  let days=[], date='', group='overview', selectedActivity='', data=null, sequence=0, controller, canSync=false;
  const el=(tag,text,className)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;};
  const fmt=value=>value===null||value===undefined?'—':typeof value==='boolean'?(value?'是':'否'):typeof value==='number'?value.toLocaleString('zh-CN',{maximumFractionDigits:3}):String(value);
  const stamp=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'}):'尚无记录';
  const minutes=value=>value===null||value===undefined?'—':`${Math.floor(value/3600)} 小时 ${Math.round(value%3600/60)} 分`;
  const bridge=()=>window.garminDetailBridge;
  function controls(allowed){canSync=allowed;$('syncDetail').disabled=!allowed||!data;for(const button of dialog.querySelectorAll('[data-sync-activity]'))button.disabled=!allowed;}
  function clear(){sequence++;controller?.abort();data=null;date='';days=[];$('detailSearch').value='';$('detailContent').replaceChildren();$('detailTabs').replaceChildren();$('detailDate').replaceChildren();$('exportDetail').disabled=true;$('detailStatus').textContent='';if(dialog.open)dialog.close();}
  function setDays(next){if(date&&!next.some(day=>day.date===date)&&dialog.open)clear();days=next;$('detailDate').replaceChildren(...days.map(day=>{const option=el('option',day.date);option.value=day.date;return option;}));if(date&&days.some(day=>day.date===date))$('detailDate').value=date;}
  async function load(){
    if(!date||!dialog.open)return;controller?.abort();controller=new AbortController();const current=++sequence,abort=controller,requested=date;
    const timeout=setTimeout(()=>abort.abort(),15000);data=null;$('detailContent').replaceChildren();$('detailStatus').textContent='正在读取每日详情…';$('exportDetail').disabled=true;controls(canSync);
    try{const result=await bridge().request('/day?'+new URLSearchParams({date:requested}),{signal:abort.signal});
      if(current!==sequence||!dialog.open)return;if(result.day?.date!==requested)throw Error('详情日期不一致，请重试。');data=result.day;render();
    }catch(error){if(current===sequence&&dialog.open)$('detailStatus').textContent=error.name==='AbortError'?'详情读取超时，请重新选择日期重试。':error.message;}
    finally{clearTimeout(timeout);}
  }
  function open(day,section='overview',activity=''){date=day;group=groups[section]?section:'overview';selectedActivity=activity;$('detailSearch').value='';$('detailDate').value=day;if(!dialog.open)dialog.showModal();$('detailTitle').textContent=day+' 的健康记录';load();}
  function tabs(){const nodes=[];for(const [key,[label]] of Object.entries(groups)){const button=el('button',label);button.type='button';button.setAttribute('aria-pressed',String(key===group&&!$('detailSearch').value.trim()));button.addEventListener('click',()=>{group=key;$('detailSearch').value='';render();});nodes.push(button);}$('detailTabs').replaceChildren(...nodes);}
  function search(query,parent){
    let count=0,visited=0;const grid=el('div',undefined,'field-grid');const needle=query.toLocaleLowerCase();
    function walk(value,path){if(++visited>120000)return;if(value!==null&&typeof value==='object'){
      for(const [key,item] of Object.entries(value))walk(item,`${path} / ${labels[key]||key}`);
    }else if((path+' '+fmt(value)).toLocaleLowerCase().includes(needle)){count++;if(count<=200){const row=el('div',undefined,'detail-field');row.append(el('span',path),el('strong',fmt(value)));grid.append(row);}}}
    for(const [key,record] of Object.entries(data.details||{}))if(record.state==='available')walk(record.value,labels[key]||key);
    for(const workout of data.activities||[]){if(workout.overview?.state==='available')walk(workout.overview.value,`运动 / ${workout.name}`);for(const [key,record] of Object.entries(workout.details||{}))if(record.state==='available')walk(record.value,`运动 / ${workout.name} / ${labels[key]||key}`);}
    parent.append(el('p',count?`匹配 ${count} 个字段${count>200?'，先展示前 200 个，请缩小搜索范围':''}${visited>120000?'；部分采样未搜索，可在完整字段中查看':''}`:'已采集数据中没有匹配字段。尚未采集的指标可补采后再搜索。','muted'),grid);
  }
  function fields(value,parent,key='',depth=0){
    if(value!==null&&typeof value==='object'){
      const entries=Array.isArray(value)?value.map((v,i)=>[String(i+1),v]):Object.entries(value);
      const box=el('details',undefined,'field-section'),heading=el('summary',`${labels[key]||key||'字段'} · ${entries.length} ${Array.isArray(value)?'项':'个字段'}`);box.append(heading);parent.append(box);
      if(depth===0&&!Array.isArray(value))box.open=true;
      let built=false;const build=()=>{if(built)return;built=true;const body=el('div',undefined,'field-grid');box.append(body);let offset=0;
        const more=el('button','展示更多','secondary');more.type='button';const append=()=>{more.remove();for(const [name,item] of entries.slice(offset,offset+60))fields(item,body,name,depth+1);offset+=60;if(offset<entries.length){more.textContent=`展示更多（剩余 ${entries.length-offset} 项）`;body.append(more);}};more.addEventListener('click',append);append();};
      box.addEventListener('toggle',()=>{if(box.open)build();});if(box.open)build();
    }else{const row=el('div',undefined,'detail-field');row.append(el('span',labels[key]||key),el('strong',fmt(value)));parent.append(row);}
  }
  function chart(points,label,maximum){
    if(!Array.isArray(points))return null;
    const valid=points.filter(p=>Array.isArray(p)&&typeof p[0]==='number'&&typeof p[1]==='number'&&p[1]>=0&&p[1]<=maximum);
    if(valid.length<2)return null;const svgNS='http://www.w3.org/2000/svg',svg=document.createElementNS(svgNS,'svg');svg.setAttribute('viewBox','0 0 700 150');svg.setAttribute('role','img');svg.setAttribute('aria-label',label+'随时间变化的曲线');
    const title=document.createElementNS(svgNS,'title');title.textContent=label;svg.append(title);const min=Math.min(...valid.map(p=>p[0])),max=Math.max(...valid.map(p=>p[0]));if(max===min)return null;
    let path='',previousValid=false;
    for(const point of points){const ok=Array.isArray(point)&&typeof point[0]==='number'&&typeof point[1]==='number'&&point[1]>=0&&point[1]<=maximum;
      if(!ok){previousValid=false;continue;}const x=12+(point[0]-min)/(max-min)*676,y=135-point[1]/maximum*120;path+=`${previousValid?'L':'M'}${x.toFixed(1)},${y.toFixed(1)} `;previousValid=true;}
    const line=document.createElementNS(svgNS,'path');line.setAttribute('d',path);line.setAttribute('fill','none');line.setAttribute('stroke','currentColor');line.setAttribute('stroke-width','2');svg.append(line);
    const box=el('div',undefined,'health-chart');box.append(el('p',`${label} · 原始有效采样 ${valid.length} 个`),svg,el('small',`${stamp(min)} — ${stamp(max)} · 纵轴 0–${maximum}；缺测与负值不计入曲线`));return box;
  }
  function record(key,item,parent){
    const section=el('section',undefined,'detail-category');section.append(el('h3',labels[key]||key));parent.append(section);
    if(!item){section.append(el('p','尚未采集，可补采这一天的完整指标。','muted'));return;}
    section.append(el('p',`采集于 ${stamp(item.collectedAt)}${item.truncated?' · 已限制数据量，未完整保存；可对照佳明原始记录':''}`,'collection-note'));
    if(item.state!=='available'){section.append(el('p',item.truncated?'数据量超出保存上限，暂无完整字段。':'佳明未提供记录，可能与设备支持、账户地区或当日记录有关。','muted'));return;}
    const raw=item.value;
    const plot=key==='heart'?chart(raw?.heartRateValues,'心率（次/分）',250):key==='stress'?chart(raw?.stressValuesArray,'压力',100):key==='battery'?chart(Array.isArray(raw)?raw[0]?.bodyBatteryValuesArray:null,'身体电量',100):null;if(plot)section.append(plot);
    // Arrays are expanded lazily; detailed samples never block the daily table.
    fields(raw,section,labels[key]||key);
    const all=el('details',undefined,'raw-fields');all.append(el('summary','查看完整返回字段（JSON）'));let built=false;all.addEventListener('toggle',()=>{if(all.open&&!built){built=true;all.append(el('pre',JSON.stringify(raw,null,2)));}});section.append(all);
  }
  function render(){
    if(!data)return;tabs();$('detailTitle').textContent=date+' 的健康记录';$('exportDetail').disabled=false;
    const collected=Object.keys(data.details||{}).length;$('detailStatus').textContent=`已采集 ${collected} 类数据。未采集与佳明未提供分别显示；历史详情保留各自采集时间。`;
    const content=$('detailContent');content.replaceChildren();
    if($('detailSearch').value.trim()){search($('detailSearch').value.trim(),content);controls(bridge()?.canSync()===true);return;}
    if(group==='overview'){
      const grid=el('div',undefined,'detail-summary-grid');const s=data.summary||{};
      for(const [key,label,unit] of [['steps','步数','步'],['distanceMeters','距离','米'],['totalCalories','总热量','千卡'],['activeCalories','活动热量','千卡'],['heartRateAverage','平均心率','次/分'],['heartRateMin','最低心率','次/分'],['heartRateMax','最高心率','次/分'],['restingHeartRate','静息心率','次/分'],['sleepScore','睡眠得分','分'],['hrvMs','夜间 HRV','毫秒'],['stress','平均压力',''],['bodyBattery','最近身体电量',''],['weightKg','体重','千克']]){
        const card=el('div',undefined,'detail-field');card.append(el('span',label),el('strong',s[key]===null||s[key]===undefined?'—':`${fmt(s[key])} ${unit}`));grid.append(card);}
      content.append(grid);
    }
    if(group==='sleep'){const grid=el('div',undefined,'detail-summary-grid');for(const [key,label] of [['sleepSeconds','睡眠总时长'],['deepSleepSeconds','深睡'],['lightSleepSeconds','浅睡'],['remSleepSeconds','REM 睡眠'],['awakeSleepSeconds','清醒']]){const card=el('div',undefined,'detail-field');card.append(el('span',label),el('strong',minutes(data.summary?.[key])));grid.append(card);}content.append(grid);}
    if(group==='activities'){
      const workouts=Array.isArray(data.activities)?data.activities:[];if(!workouts.length)content.append(el('p','这一天没有已保存的运动记录。','muted'));
      if(workouts.length){const select=el('select');select.setAttribute('aria-label','选择运动记录');for(const item of workouts){const option=el('option',item.name||item.id);option.value=item.id;select.append(option);}if(!workouts.some(item=>item.id===selectedActivity))selectedActivity=workouts[0].id;select.value=selectedActivity;select.addEventListener('change',()=>{selectedActivity=select.value;render();});content.append(select);
        const item=workouts.find(workout=>workout.id===selectedActivity),button=el('button','补采本次运动详情','primary');button.type='button';button.dataset.syncActivity=item.id;button.addEventListener('click',()=>bridge().sync({startDate:date,endDate:date,activityId:item.id,includeDetails:false}));content.append(button);
        record('overview',item.details?.overview||item.overview,content);
        for(const key of ['splits','typed_splits','split_summaries','heart_zones','power_zones','exercise_sets','weather','gear','samples'])record(key,item.details?.[key],content);
        content.append(el('p','轨迹与图表由接口最多返回 1,000 个采样点，未取得原始 FIT 文件。','collection-note'));
      }
    }else for(const key of groups[group][1])record(key,data.details?.[key],content);
    controls(bridge()?.canSync()===true);
  }
  $('detailDate').addEventListener('change',()=>{date=$('detailDate').value;selectedActivity='';$('detailSearch').value='';$('detailTitle').textContent=date+' 的健康记录';load();});
  let searchTimer;$('detailSearch').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(render,180);});
  $('closeDetail').addEventListener('click',()=>dialog.close());dialog.addEventListener('close',()=>{sequence++;controller?.abort();data=null;$('detailContent').replaceChildren();});
  $('syncDetail').addEventListener('click',()=>bridge().sync({startDate:date,endDate:date,includeDetails:true}));
  $('exportDetail').addEventListener('click',()=>{if(!data)return;const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const link=el('a');link.href=url;link.download=`garmin-${date}.json`;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  window.garminDetails={open,clear,setDays,controls,refresh:()=>{if(dialog.open)load();}};
})();
