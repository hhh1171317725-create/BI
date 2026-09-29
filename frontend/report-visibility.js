(() => {
  const reportPaths = {
    dhh: '/',
    jd: '/jd',
    jdLowActivity: '/jd-low-activity',
    adpflux: '/adpflux',
    bidMonitor: '/bid-monitor.html'
  };
  const pagePath = location.pathname.replace(/\.html$/, '').replace(/\/index$/, '/') || '/';
  const currentReport = Object.entries(reportPaths)
      .find(([, path]) => pagePath === path.replace(/\.html$/, ''))?.[0];
  const cacheKey = 'report-visibility-v2';
  const style = document.createElement('style');
  style.textContent = '.report-visibility-hidden{display:none!important}'
      + 'html.report-visibility-checking body{visibility:hidden}';
  document.head.appendChild(style);
  if (currentReport) document.documentElement.classList.add('report-visibility-checking');

  function renderVisibility(visibility, canRedirect = true) {
    window.biReportVisibility = {...visibility};
    const navigation = document.querySelector('.header-actions, nav.nav, nav[aria-label="页面导航"]');
    const supplementalLinks = [
      {path: '/adpflux', label: 'TikTok账户'},
      {path: '/bid-monitor.html', label: '出价监测'}
    ];
    for (const item of supplementalLinks) {
      if (!navigation || location.pathname === item.path
          || navigation.querySelector(`a[href="${item.path}"]`)) continue;
      const link = document.createElement('a');
      link.href = item.path;
      link.textContent = item.label;
      if (navigation.classList.contains('header-actions')) link.className = 'report-link';
      const toolsLink = navigation.querySelector('a[href="/tools"]');
      const settingsLink = navigation.querySelector('a[href="/account"]');
      navigation.insertBefore(link, toolsLink || settingsLink || navigation.querySelector('button'));
    }
    for (const [key, path] of Object.entries(reportPaths)) {
      document.querySelectorAll(`a[href="${path}"]`).forEach(link => {
        link.classList.toggle('report-visibility-hidden', visibility[key] === false);
      });
    }
    if (canRedirect && currentReport && visibility[currentReport] === false) {
      const destination = Object.entries(reportPaths)
          .find(([key]) => visibility[key] === true)?.[1] || '/tools';
      location.replace(destination);return false;
    }
    document.dispatchEvent(new CustomEvent('bi:report-visibility', {detail: {...visibility}}));
    document.documentElement.classList.remove('report-visibility-checking');
    document.documentElement.classList.add('report-visibility-ready');return true;
  }

  function forgetCache() { try { sessionStorage.removeItem(cacheKey); } catch {} }
  async function readPermissions(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(url, {cache:'no-store', signal:controller.signal});
      if (response.status === 401) { forgetCache(); location.replace('/login'); throw Error('session'); }
      if (!response.ok) throw Error('permissions');
      const data = await response.json();
      if (!data || typeof data !== 'object' || Array.isArray(data)) throw Error('permissions');
      return data;
    } finally { clearTimeout(timer); }
  }
  let loading = false;
  async function applyVisibility() {
    if (loading) return;
    loading = true;
    let visibility = window.biReportVisibility || {dhh:false,jd:false,jdLowActivity:false,adpflux:false,bidMonitor:false};
    try {
      const saved=JSON.parse(sessionStorage.getItem(cacheKey)||'null');
      if(saved&&Date.now()-saved.savedAt<30_000){visibility={...visibility,...saved.visibility};renderVisibility(visibility,false);}
    } catch { forgetCache(); }
    const retry = document.querySelector('#permissionRetry');
    if (retry) { retry.disabled = true; retry.textContent = '重试中…'; }
    // Independent endpoints run concurrently; a failed endpoint cannot erase the other result.
    const [reports, tools] = await Promise.allSettled([
      readPermissions('/api/report-visibility'), readPermissions('/api/tool-visibility')
    ]);
    const reportsOk = reports.status === 'fulfilled', toolsOk = tools.status === 'fulfilled';
    if (reportsOk) {
      for (const key of ['dhh','jd','jdLowActivity','adpflux'])
        if (typeof reports.value[key] === 'boolean') visibility[key] = reports.value[key];
    }
    if (toolsOk) visibility.bidMonitor = tools.value.bidMonitor === true;
    if (reportsOk && toolsOk) {
      try { sessionStorage.setItem(cacheKey,JSON.stringify({savedAt:Date.now(),visibility})); } catch {}
    }
    // Redirect only on a successful response for this page, never because a request failed.
    const verified = currentReport === 'bidMonitor' ? toolsOk : reportsOk && typeof reports.value[currentReport] === 'boolean';
    renderVisibility(visibility, verified);
    let notice = document.getElementById('permissionNotice');
    if (!reportsOk || !toolsOk) {
      if (!notice) {
        notice = document.createElement('div'); notice.id = 'permissionNotice'; notice.className = 'app-connection-notice'; notice.setAttribute('role','status');
        const text = document.createElement('span'); text.textContent = '部分导航暂未加载完成，可重试加载。';
        const button = document.createElement('button'); button.id = 'permissionRetry'; button.type = 'button'; button.textContent = '重试'; button.onclick = applyVisibility;
        notice.append(text,button); (document.querySelector('body > header') || document.body.firstElementChild)?.after(notice);
      }
      const button = notice.querySelector('button'); button.disabled = false; button.textContent = '重试';
    } else notice?.remove();
    loading = false;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyVisibility, {once: true});
  } else {
    applyVisibility();
  }
})();
