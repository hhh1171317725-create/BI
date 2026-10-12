(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const dayMillis = 86400000;
  const activeStates = new Set(['connecting', 'syncing']);
  const stateLabels = {idle:'未连接',connecting:'连接中',mfa_required:'需要验证码',syncing:'同步中',ready:'已连接',error:'需要处理'};
  const warningLabels = {steps:'步数暂未提供',heart_rate:'心率暂未提供',sleep:'睡眠暂未提供',stress:'压力暂未提供',body_battery:'身体电量暂未提供',hrv:'HRV 暂未提供',weight:'体重暂未提供',activities:'运动记录暂未提供',workout:'运动时长暂未提供'};
  let userId = null, status = null, ready = false, busy = false, locked = false, settingsDirty = false;
  let statusSequence = 0, dataSequence = 0, statusController, dataController, pollTimer, shownDays = [];
  const text = (id, value) => { const element = typeof id === 'string' ? $(id) : id; if (element.textContent !== value) element.textContent = value; };
  const hide = (id, value) => { if ($(id).hidden !== value) $(id).hidden = value; };
  const number = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) ? Number(value) : null;
  const format = (value, decimals = 0) => number(value) === null ? '—' : Number(value).toLocaleString('zh-CN', {maximumFractionDigits:decimals});
  const duration = value => {
    if (number(value) === null) return '—';
    const minutes = Math.round(Number(value) / 60);
    return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分` : `${minutes} 分`;
  };
  const timestamp = value => typeof value === 'number' ? (value > 0 ? value : NaN) : Date.parse(value);
  const time = value => value && Number.isFinite(timestamp(value)) ? new Date(timestamp(value)).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai',hour12:false}) : '尚无记录';
  const today = () => new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  const active = () => !document.hidden && document.hasFocus();
  function message(value = '', error = false) { text('pageMessage', value); hide('pageMessage', !value); $('pageMessage').dataset.error = String(error); }
  function clearData() {
    shownDays = []; $('dailyRows').replaceChildren(); $('summaryCards').replaceChildren(); $('activitiesList').replaceChildren(); $('warningsList').replaceChildren();
    for (const id of ['tableWrap','summaryCards','activitiesPanel','warningsPanel','dataEmpty']) hide(id, true);
    text('dataRange', ''); text('dataStatus', '');
  }
  function suspendAccess() {
    locked = true; ready = false; status = null; userId = null; clearTimeout(pollTimer);
    statusSequence++; dataSequence++; statusController?.abort(); dataController?.abort(); clearData();
    $('email').value = $('password').value = $('mfaCode').value = '';
    text('jobStatus', '已停止本页面的请求'); text('lastSuccess', '最近成功：尚无记录'); hide('syncError', true); hide('jobProgress', true); hide('mfaForm', true);
    hide('setupNotice', true); hide('disconnect', true); settingsDirty = false; $('autoSync').checked = false; $('intervalHours').value = '1'; $('syncDays').value = '7'; text('autoSyncState', '关闭');
    controls();
  }
  function lockAccount() {
    suspendAccess(); text('connectionState', '请刷新页面'); text('connectionDescription', '网站登录账户已更换');
    message('网站登录账户已更换，请刷新页面后继续。', true); controls();
  }
  function lockAccess() {
    suspendAccess(); text('connectionState', '未获授权'); text('connectionDescription', '佳明工具访问已暂停。');
    message('佳明工具未获授权或权限已关闭，请联系网站管理员。', true);
  }
  function checkOwner(result) {
    if (String(result?.userId ?? '') !== String(userId)) { lockAccount(); throw Error('网站登录账户已更换，请刷新页面后继续。'); }
  }
  async function request(path, options = {}) {
    const response = await fetch('/api/garmin' + path, {cache:'no-store',credentials:'same-origin',...options,headers:{'Content-Type':'application/json'}});
    const result = await response.json().catch(() => ({}));
    if (locked) throw Error('佳明工具访问已暂停。');
    if (response.status === 401) { lockAccount(); location.replace('/login'); throw Error('登录已失效，请重新登录。'); }
    if (response.status === 403) { lockAccess(); throw Error('佳明工具未获授权或权限已关闭，请联系网站管理员。'); }
    if (result.code === 'ACCOUNT_CHANGED') { lockAccount(); throw Error('网站登录账户已更换，请刷新页面后继续。'); }
    if (!response.ok) throw Error(typeof result.error === 'string' ? result.error.slice(0, 800) : `请求失败（${response.status}），请稍后重试。`);
    checkOwner(result); return result;
  }
  function controls() {
    const canUse = ready && !locked, available = !!status && status.available !== false;
    const running = activeStates.has(status?.state), mfaRequired = status?.mfa?.required === true || status?.state === 'mfa_required';
    const connected = status?.connected === true, canSync = canUse && available && connected && !busy && !running && !mfaRequired;
    for (const id of ['email','password','region','connectButton']) $(id).disabled = !canUse || !available || busy || running || mfaRequired;
    $('refreshStatus').disabled = !canUse || busy;
    for (const id of ['startDate','endDate','queryButton']) $(id).disabled = !canUse || busy;
    $('syncButton').disabled = !canSync;
    const expired = status?.mfa?.expiresAt && timestamp(status.mfa.expiresAt) <= Date.now();
    $('mfaCode').disabled = $('mfaButton').disabled = !canUse || busy || !mfaRequired || !status?.jobId || !!expired;
    $('disconnect').disabled = !canUse || busy || (!connected && !running && !mfaRequired);
    for (const id of ['autoSync','intervalHours','syncDays']) $(id).disabled = !canSync;
    $('saveSettings').disabled = !canSync || !settingsDirty;
    text('connectButton', busy ? '处理中…' : '连接账户');
  }
  function schedulePoll() {
    clearTimeout(pollTimer);
    if (!ready || locked || busy || !active()) return;
    const waiting = activeStates.has(status?.state) || status?.state === 'mfa_required';
    if (!waiting && !(status?.connected && status?.settings?.autoSync)) return;
    pollTimer = setTimeout(() => refreshStatus(), waiting ? 3000 : 30000);
  }
  function renderStatus(next) {
    const previous = status; status = next;
    const changedAccount = next.accountChanged && !previous?.accountChanged;
    if (changedAccount) settingsDirty = false;
    const connected = next.connected === true, state = next.state || (connected ? 'ready' : 'idle');
    text('connectionState', state === 'idle' && connected ? '已连接' : stateLabels[state] || '状态待更新'); $('connectionState').dataset.state = state;
    text('connectionDescription', connected ? `${next.region === 'cn' ? '中国区' : '国际区'}账户已连接，采集结果保存在本站。` : '连接你的佳明账户，将设备记录同步到本站。');
    const progress = next.progress || {}, done = number(progress.done), total = number(progress.total);
    const progressText = total > 0 && done !== null ? ` · ${format(done)} / ${format(total)} 天` : '';
    const jobs = {connecting:'正在连接佳明账户，请稍候。',mfa_required:'连接需要身份验证，请填写下方验证码。',syncing:`正在后台采集健康记录${progressText}`,error:'上次任务未完成，可处理原因后重试。'};
    text('jobStatus', jobs[state] || (connected ? '当前没有进行中的采集任务。' : '尚未连接佳明账户。'));
    text('lastSuccess', `最近成功：${time(next.lastSuccess)}`);
    text('syncError', next.error ? `失败原因：${next.error}` : ''); hide('syncError', !next.error);
    hide('jobProgress', state !== 'syncing' || !(total > 0));
    if (total > 0) { $('jobProgress').max = total; $('jobProgress').value = Math.max(0, Math.min(done || 0, total)); }
    text('setupNotice', next.setupError || (next.available === false ? '采集服务暂未就绪，请联系网站管理员完成配置。已有历史记录仍可查询。' : '')); hide('setupNotice', next.available !== false && !next.setupError);
    const mfaRequired = next.mfa?.required === true || state === 'mfa_required'; hide('mfaForm', !mfaRequired);
    if (mfaRequired) {
      const expired = next.mfa?.expiresAt && timestamp(next.mfa.expiresAt) <= Date.now();
      text('mfaDescription', expired ? '验证码已过期，请断开连接后重新连接。' : `${next.mfa?.invalidCode ? '验证码无效，请重新填写。' : '请填写佳明发送的验证码。'}${next.mfa?.expiresAt ? `有效至 ${time(next.mfa.expiresAt)}。` : ''}`);
    }
    if (!previous || previous.connected !== connected || mfaRequired !== (previous.mfa?.required === true || previous.state === 'mfa_required')) $('connectionPanel').open = !connected && !mfaRequired;
    hide('disconnect', !connected && !activeStates.has(state) && !mfaRequired);
    text('autoSyncState', next.settings?.autoSync ? `已开启 · 每 ${next.settings.intervalHours || 1} 小时` : '关闭');
    if (!settingsDirty) {
      $('autoSync').checked = next.settings?.autoSync === true;
      $('intervalHours').value = String(next.settings?.intervalHours || 1);
      $('syncDays').value = String(next.settings?.days || 7);
    }
    controls();
    if (changedAccount) { dataSequence++; dataController?.abort(); clearData(); message('已切换佳明账户，当前显示新账户的数据。'); }
    if (previous && (changedAccount || (next.lastSuccess && next.lastSuccess !== previous.lastSuccess))) queryData();
  }
  async function refreshStatus() {
    if (!ready || locked || busy) return;
    clearTimeout(pollTimer); statusController?.abort(); const controller = new AbortController(); statusController = controller;
    const sequence = ++statusSequence, timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const result = await request('/status', {signal:controller.signal});
      if (sequence !== statusSequence || locked) return;
      renderStatus(result);
    } catch (error) {
      if (sequence !== statusSequence || locked) return;
      message(error.name === 'AbortError' ? '连接状态读取超时，请刷新状态重试。' : error.message || '连接状态暂时无法读取。', true);
      if (!status) { text('connectionState', '暂未加载'); text('jobStatus', '连接状态暂时无法读取，请刷新状态重试。'); }
    } finally { clearTimeout(timeout); if (sequence === statusSequence) { controls(); schedulePoll(); } }
  }
  function range() {
    if (!$('rangeForm').reportValidity()) throw Error('请填写有效的开始和结束日期。');
    const startDate = $('startDate').value, endDate = $('endDate').value;
    const count = Math.round((Date.parse(endDate + 'T00:00:00Z') - Date.parse(startDate + 'T00:00:00Z')) / dayMillis) + 1;
    if (!(count >= 1)) throw Error('结束日期不能早于开始日期。');
    if (count > 90) throw Error('每次最多查询或同步 90 天，请缩小日期范围。');
    if (endDate > today()) throw Error('不能查询或同步未来日期。');
    return {startDate,endDate,count};
  }
  const element = (tag, value, className) => { const node = document.createElement(tag); if (value !== undefined) node.textContent = value; if (className) node.className = className; return node; };
  function renderData(result) {
    const days = Array.isArray(result.days) ? result.days.filter(day => /^\d{4}-\d{2}-\d{2}$/.test(day.date)).sort((a,b) => b.date.localeCompare(a.date)) : [];
    shownDays = days;
    text('dataRange', `${result.startDate} — ${result.endDate}`);
    const expected = Math.round((Date.parse(result.endDate) - Date.parse(result.startDate)) / dayMillis) + 1;
    const missing = Array.isArray(result.missingDates) ? result.missingDates.length : Math.max(0, expected - days.length);
    text('dataStatus', days.length ? `已保存 ${days.length} 天记录${missing ? ` · ${missing} 天尚无记录` : ''}。单项缺失不会记为零。` : '所选日期暂无已保存的记录。');
    hide('dataEmpty', !!days.length); hide('tableWrap', !days.length); hide('summaryCards', !days.length);
    text('emptyDescription', status?.connected ? '点击「同步所选日期」采集这段时间的记录。' : '连接佳明账户后，点击「同步所选日期」开始采集。断开后保留的历史记录也可按日期查询。');
    const cards = document.createDocumentFragment();
    function card(label, value, note) { const node = element('div', undefined, 'summary-metric'); node.append(element('p', label), element('strong', value), element('small', note)); cards.append(node); }
    for (const [key,label,formatter] of [['steps','步数合计',v=>format(v)],['activeCalories','活动热量合计',v=>`${format(v)} 千卡`],['workoutSeconds','运动时长合计',duration]]) {
      const values = days.map(day => number(day.summary?.[key])).filter(value => value !== null);
      card(label, values.length ? formatter(values.reduce((sum,value) => sum + value, 0)) : '—', `有数据 ${values.length} / ${expected} 天`);
    }
    for (const [key,label,formatter] of [['sleepSeconds','最近睡眠',duration],['restingHeartRate','最近静息心率',v=>`${format(v)} 次/分`],['weightKg','最近体重',v=>`${format(v,1)} 千克`]]) {
      const day = days.find(item => number(item.summary?.[key]) !== null);
      card(label, day ? formatter(day.summary[key]) : '—', day ? day.date : '所选日期暂无记录');
    }
    $('summaryCards').replaceChildren(cards);
    const rows = document.createDocumentFragment(), activities = document.createDocumentFragment(), warnings = document.createDocumentFragment(); let activityCount = 0, warningCount = 0;
    for (const day of days) {
      const summary = day.summary || {}, row = element('tr'); row.dataset.date = day.date;
      const workouts = Array.isArray(day.activities) ? day.activities : [];
      const values = [day.date,format(summary.steps),number(summary.heartRateAverage) === null ? '—' : `${format(summary.heartRateAverage,1)} 次/分`,number(summary.restingHeartRate) === null ? '—' : `${format(summary.restingHeartRate)} 次/分`,number(summary.activeCalories) === null ? '—' : `${format(summary.activeCalories)} 千卡`,number(summary.weightKg) === null ? '—' : `${format(summary.weightKg,1)} 千克`,duration(summary.sleepSeconds),number(summary.workoutSeconds) === null ? (workouts.length ? `${workouts.length} 次` : '—') : duration(summary.workoutSeconds),number(summary.hrvMs) === null ? '—' : `${format(summary.hrvMs,1)} 毫秒`,format(summary.stress),format(summary.bodyBattery)];
      values.forEach((value,index) => {
        const cell = element(index === 0 ? 'th' : 'td', value); if (index === 0) cell.scope = 'row';
        if (index === 2 && (number(summary.heartRateMin) !== null || number(summary.heartRateMax) !== null)) cell.title = `最低 ${format(summary.heartRateMin)} · 最高 ${format(summary.heartRateMax)} 次/分`;
        if (index === 6) cell.title = `深睡 ${duration(summary.deepSleepSeconds)} · 浅睡 ${duration(summary.lightSleepSeconds)} · REM ${duration(summary.remSleepSeconds)} · 睡眠评分 ${format(summary.sleepScore)}${summary.sleepStartTime ? ` · 入睡 ${time(summary.sleepStartTime)}` : ''}${summary.sleepEndTime ? ` · 起床 ${time(summary.sleepEndTime)}` : ''}`;
        row.append(cell);
      }); rows.append(row);
      for (const workout of workouts) {
        activityCount++; const node = element('article', undefined, 'activity');
        const timestamp = element('time', workout.startTime ? time(workout.startTime) : day.date); if (workout.startTime) timestamp.dateTime = workout.startTime;
        node.append(timestamp, element('h3', workout.name || workout.type || '运动记录'));
        const details = [workout.type || '',number(workout.durationSeconds) === null ? '' : duration(workout.durationSeconds),number(workout.distanceMeters) === null ? '' : `${format(workout.distanceMeters / 1000,2)} 公里`,number(workout.calories) === null ? '' : `${format(workout.calories)} 千卡`,number(workout.averageHeartRate) === null ? '' : `平均心率 ${format(workout.averageHeartRate)} 次/分`].filter(Boolean);
        node.append(element('p', details.join(' · ') || '暂无详细指标')); activities.append(node);
      }
      const codes = [...new Set(Array.isArray(day.warnings) ? day.warnings : [])];
      if (codes.length) { warningCount++; warnings.append(element('li', `${day.date}：${[...new Set(codes.map(code => warningLabels[code] || '部分指标暂未提供'))].join('；')}`)); }
    }
    $('dailyRows').replaceChildren(rows); $('activitiesList').replaceChildren(activities); $('warningsList').replaceChildren(warnings);
    hide('activitiesPanel', !activityCount); hide('warningsPanel', !warningCount);
    text('activitiesHeading', `运动记录（${activityCount} 次）`); text('warningsHeading', `数据说明（${warningCount} 天）`);
  }
  async function queryData() {
    if (!ready || locked) return;
    let dates; try { dates = range(); } catch (error) { message(error.message, true); return; }
    dataController?.abort(); const controller = new AbortController(); dataController = controller;
    const sequence = ++dataSequence, timeout = setTimeout(() => controller.abort(), 20000);
    text('dataStatus', '正在读取所选日期的健康记录…'); $('tableWrap').setAttribute('aria-busy', 'true');
    try {
      const result = await request('/data?' + new URLSearchParams({startDate:dates.startDate,endDate:dates.endDate}), {signal:controller.signal});
      if (sequence !== dataSequence || locked) return;
      renderData(result);
    } catch (error) {
      if (sequence !== dataSequence || locked) return;
      text('dataStatus', (error.name === 'AbortError' ? '健康记录读取超时，请重试。' : error.message || '健康记录暂时无法读取。') + (shownDays.length ? ' 下方仍显示此前读取的记录。' : ''));
      if (!shownDays.length) { hide('dataEmpty', false); text('emptyDescription', '请点击「查询记录」重试。'); }
    } finally { clearTimeout(timeout); if (sequence === dataSequence) $('tableWrap').setAttribute('aria-busy', 'false'); }
  }
  async function mutate(path, payload, successMessage) {
    if (!ready || locked || busy) return;
    busy = true; statusSequence++; statusController?.abort(); clearTimeout(pollTimer); controls(); message();
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 25000);
    let focusMfa = false;
    try {
      const result = await request(path, {method:'POST',body:JSON.stringify({...payload,expectedUserId:userId}),signal:controller.signal});
      if (locked) return;
      if (path === '/settings' || path === '/disconnect') settingsDirty = false;
      const switchedAccount = result.accountChanged && !status?.accountChanged;
      renderStatus(result); if (!switchedAccount) message(successMessage);
      focusMfa = result.mfa?.required || result.state === 'mfa_required';
      return result;
    } catch (error) {
      if (!locked) message(error.name === 'AbortError' ? '请求超时，任务可能仍在后台运行，请刷新状态确认。' : error.message || '请求未完成，请稍后重试。', true);
    } finally { clearTimeout(timeout); busy = false; controls(); if (focusMfa && active()) $('mfaCode').focus(); schedulePoll(); }
  }
  $('refreshStatus').addEventListener('click', () => { message(); refreshStatus(); });
  $('rangeForm').addEventListener('submit', event => { event.preventDefault(); message(); queryData(); });
  $('connectForm').addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !$('connectForm').reportValidity()) return;
    const payload = {email:$('email').value.trim(),password:$('password').value,region:$('region').value};
    $('email').value = $('password').value = ''; $('mfaCode').value = '';
    await mutate('/connect', payload, '连接任务已提交，状态将在此处更新。');
  });
  $('mfaForm').addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !$('mfaForm').reportValidity()) return;
    const code = $('mfaCode').value.trim(); $('mfaCode').value = '';
    await mutate('/mfa', {code,jobId:status?.jobId}, '验证码已提交，正在继续连接。');
  });
  $('syncButton').addEventListener('click', () => {
    let dates; try { dates = range(); } catch (error) { message(error.message, true); return; }
    mutate('/sync', {startDate:dates.startDate,endDate:dates.endDate}, '同步任务已提交，可在此处查看采集进度。');
  });
  $('settingsForm').addEventListener('change', () => { settingsDirty = true; controls(); });
  $('settingsForm').addEventListener('input', () => { settingsDirty = true; controls(); });
  $('settingsForm').addEventListener('submit', event => {
    event.preventDefault(); if (!$('settingsForm').reportValidity()) return;
    mutate('/settings', {autoSync:$('autoSync').checked,intervalHours:Number($('intervalHours').value),days:Number($('syncDays').value)}, '自动同步设置已保存。');
  });
  $('disconnect').addEventListener('click', () => {
    if (!confirm('确定断开佳明连接？自动同步将停止，当前佳明账户的历史数据会保留。')) return;
    $('mfaCode').value = ''; mutate('/disconnect', {}, '已断开连接并停止自动同步，历史记录仍可按日期查询。');
  });
  $('logout').addEventListener('click', async () => {
    lockAccount(); try { await fetch('/api/logout', {method:'POST',credentials:'same-origin'}); } finally { location.replace('/login'); }
  });
  function pausePolling() { clearTimeout(pollTimer); if (statusController) { statusSequence++; statusController.abort(); } }
  document.addEventListener('visibilitychange', () => { if (active()) refreshStatus(); else pausePolling(); });
  window.addEventListener('blur', pausePolling); window.addEventListener('focus', () => { if (active()) refreshStatus(); });
  window.addEventListener('pagehide', () => { pausePolling(); dataSequence++; dataController?.abort(); $('email').value = $('password').value = $('mfaCode').value = ''; });
  window.addEventListener('pageshow', event => { if (event.persisted && active()) refreshStatus(); });
  (async () => {
    try {
      const response = await fetch('/api/session', {cache:'no-store',credentials:'same-origin'});
      if (response.status === 401) { location.replace('/login'); return; }
      if (!response.ok) throw Error('登录信息读取失败。');
      const session = await response.json();
      if (!session.authenticated || session.user?.id === undefined || session.user?.id === null) { location.replace('/login'); return; }
      userId = session.user.id; window.currentSessionUser = session.user; ready = true;
      document.documentElement.classList.add('authenticated');
      const end = today(), start = new Date(Date.parse(end + 'T00:00:00Z') - 6 * dayMillis).toISOString().slice(0,10);
      $('startDate').value = start; $('endDate').value = end; $('startDate').max = $('endDate').max = end;
      controls(); await refreshStatus(); if (!locked) await queryData();
    } catch { document.documentElement.classList.add('authenticated'); message('网站登录信息暂时无法读取，请刷新页面重试。', true); }
  })();
})();
