/* Presentation preferences never re-query or recalculate report data. */
(() => {
  const key = 'bi-daily-table-presentation-v1', controls = new Set();
  let preferences = {compact:false, foldTrend:false};
  try {
    const saved = JSON.parse(localStorage.getItem(key) || '{}');
    preferences = {compact:saved.compact === true, foldTrend:saved.foldTrend === true};
  } catch {}
  const resize = () => requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
  function sync() {
    document.body.classList.toggle('daily-compact-rows', preferences.compact);
    const trend = document.getElementById('mainChartSection');
    if (trend && trend.classList.contains('daily-trend-folded') !== preferences.foldTrend)
      trend.classList.toggle('daily-trend-folded', preferences.foldTrend);
    for (const control of controls) {
      control.density.setAttribute('aria-pressed', String(preferences.compact));
      if (control.trend) {
        control.trend.hidden = !trend || trend.classList.contains('hidden');
        const text = preferences.foldTrend ? '显示趋势' : '收起趋势';
        if (control.trend.textContent !== text) control.trend.textContent = text;
        control.trend.setAttribute('aria-expanded', String(!preferences.foldTrend));
        control.trend.disabled = document.fullscreenElement === control.surface;
      }
      if (control.expand) {
        const expanded = document.fullscreenElement === control.surface;
        const text = expanded ? '退出展开' : '展开看表';
        if (control.expand.textContent !== text) control.expand.textContent = text;
        control.expand.setAttribute('aria-pressed', String(expanded));
      }
    }
  }
  function save() {
    try { localStorage.setItem(key, JSON.stringify(preferences)); } catch {}
    sync(); resize();
  }
  document.addEventListener('fullscreenchange', () => { sync(); resize(); });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || document.fullscreenElement?.id !== 'content' || document.querySelector('dialog[open]')) return;
    event.preventDefault(); event.stopPropagation();
    // Preserve table search when Escape exits an expanded report.
    document.exitFullscreen().catch(() => {});
  }, true);
  window.addEventListener('storage', event => {
    if (event.key !== key) return;
    try {
      const saved = JSON.parse(event.newValue || '{}');
      preferences = {compact:saved.compact === true, foldTrend:saved.foldTrend === true};
      sync(); resize();
    } catch {}
  });
  rootInit();
  function rootInit() {
    sync();
    const tabs = document.getElementById('tabs');
    if (tabs) {
      tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', '报表汇总维度');
      const updateTabs = () => tabs.querySelectorAll('button[data-key]').forEach(button => {
        const selected = button.classList.contains('active');
        button.setAttribute('role', 'tab'); button.setAttribute('aria-selected', String(selected));
        button.setAttribute('aria-controls', 'content'); button.tabIndex = selected ? 0 : -1;
      });
      updateTabs();
      new MutationObserver(updateTabs).observe(tabs, {subtree:true, attributes:true, attributeFilter:['class']});
      tabs.addEventListener('keydown', event => {
        if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
        const available = [...tabs.querySelectorAll('button[data-key]')].filter(button => !button.disabled && button.getClientRects().length);
        const index = available.indexOf(event.target);
        if (index < 0) return;
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? available.length - 1 :
          (index + (event.key === 'ArrowRight' ? 1 : -1) + available.length) % available.length;
        available[next].focus({preventScroll:true}); available[next].click();
      });
    }
    const trend = document.getElementById('mainChartSection');
    if (trend) new MutationObserver(sync).observe(trend, {attributes:true, attributeFilter:['class']});
    window.BIDailyTablePresentation = {
      attach(element, tools, scope) {
        const button = text => {
          const result = document.createElement('button'); result.type = 'button';
          result.className = 'daily-view-button'; result.textContent = text;
          element.append(result); return result;
        };
        const density = button('紧凑行距'); density.title = '缩小行距，在同一屏幕查看更多数据';
        density.onclick = () => { preferences.compact = !preferences.compact; save(); };
        const control = {density};
        if (scope.startsWith('main:')) {
          control.surface = tools.closest('#content');
          control.trend = button('收起趋势');
          control.trend.setAttribute('aria-controls', 'mainChartSection');
          control.trend.onclick = () => { preferences.foldTrend = !preferences.foldTrend; save(); };
          if (document.fullscreenEnabled && control.surface?.requestFullscreen) {
            control.surface.classList.add('daily-table-expandable');
            control.expand = button('展开看表');
            control.expand.classList.add('daily-expand-button');
            control.expand.title = '展开当前汇总表格；按 Esc 或点击退出展开返回';
            const status = document.createElement('span'); status.className = 'daily-view-status';
            status.setAttribute('role','status'); element.append(status);
            control.expand.onclick = async () => {
              status.textContent = ''; control.expand.disabled = true;
              try {
                if (document.fullscreenElement === control.surface) await document.exitFullscreen();
                else await control.surface.requestFullscreen();
              } catch { status.textContent = '浏览器未允许展开，请重试。'; }
              finally { control.expand.disabled = false; sync(); }
            };
          }
        }
        controls.add(control); sync();
      },
    };
  }
})();
