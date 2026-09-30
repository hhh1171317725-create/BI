(function (root) {
  'use strict';
  const empty = Object.freeze([]);
  function createRowsCache() {
    const cache = new WeakMap();
    const collator = new Intl.Collator('zh-CN', {numeric:true, sensitivity:'base'});
    function remember(rows, key, load) {
      let entries = cache.get(rows);
      if (!entries) { entries = new Map(); cache.set(rows, entries); }
      if (entries.has(key)) return entries.get(key);
      const value = load();
      entries.set(key, value);
      if (entries.size > 12) entries.delete(entries.keys().next().value);
      return value;
    }
    return {
      sort(rows, setting) {
        if (!setting.sortColumn) return rows;
        const {sortColumn:column, sortDirection} = setting;
        const direction = sortDirection === 'desc' ? -1 : 1;
        return remember(rows, JSON.stringify(['sort', column, direction]), () => [...rows].sort((a, b) => {
          const left = a[column], right = b[column], x = Number(left), y = Number(right);
          return (Number.isFinite(x) && Number.isFinite(y) ? x - y : collator.compare(String(left ?? ''), String(right ?? ''))) * direction;
        }));
      },
      select(source, conditions, asText = false) {
        const rows = source || empty;
        const fields = Object.entries(conditions);
        return remember(rows, JSON.stringify(['filter', fields, asText]), () => rows.filter(row =>
          fields.every(([field, value]) => (asText ? String(row[field]) : row[field]) === value)));
      },
    };
  }
  if (typeof module === 'object' && module.exports) { module.exports = {createRowsCache}; return; }
  root.BIDailyRows = {create: createRowsCache};

  let library;
  function loadCharts() {
    if (root.echarts) return Promise.resolve(root.echarts);
    if (library) return library;
    library = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/echarts.min.js';
      script.async = true;
      const timeout = setTimeout(() => finish(new Error('图表加载超时')), 20000);
      function finish(error) {
        clearTimeout(timeout);
        script.onload = script.onerror = null;
        if (error) { script.remove(); reject(error); } else resolve(root.echarts);
      }
      script.onload = () => finish(root.echarts ? null : new Error('图表组件不可用'));
      script.onerror = () => finish(new Error('图表组件加载失败'));
      document.head.append(script);
    }).catch(error => { library = null; throw error; });
    return library;
  }
  const entries = new WeakMap();
  const observer = typeof IntersectionObserver === 'function' ? new IntersectionObserver(changes => {
    for (const change of changes) {
      const entry = entries.get(change.target);
      if (entry) { entry.visible = change.isIntersecting; if (entry.visible) schedule(entry); }
    }
  }, {rootMargin:'160px'}) : null;
  function status(entry, text, retry = false) {
    if (entry.instance) { entry.instance.dispose(); entry.instance = null; }
    entry.element.replaceChildren();
    entry.element.setAttribute('role', 'group');
    const message = document.createElement('div');
    message.className = 'daily-chart-status';
    message.setAttribute('role', 'status');
    const label = document.createElement('span');
    label.textContent = text;
    message.append(label);
    if (retry) {
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'secondary'; button.textContent = '重试图表';
      button.onclick = () => { entry.failed = false; schedule(entry); };
      message.append(button);
    }
    entry.element.append(message);
  }
  function schedule(entry) {
    if (entry.scheduled || !entry.visible || entry.failed || entry.drawn === entry.rows) return;
    entry.scheduled = true;
    requestAnimationFrame(async () => {
      try {
        if (!entry.visible || !entry.rows.length || !entry.element.getClientRects().length) return;
        if (!entry.instance) status(entry, '正在加载图表…');
        const api = await loadCharts();
        if (!entry.visible || !entry.rows.length || !entry.element.getClientRects().length) return;
        if (!entry.instance) entry.element.replaceChildren();
        // Read the latest callback after loading: filters may have changed in the meantime.
        entry.instance = entry.draw(api, entry.instance);
        entry.drawn = entry.rows;
        entry.element.setAttribute('role', 'img');
        entry.element.dataset.chartState = 'ready';
      } catch {
        if (!entry.rows.length) return;
        entry.failed = true;
        entry.drawn = null;
        entry.element.dataset.chartState = 'error';
        status(entry, '图表暂不可用，表格数据可正常查看。', true);
      } finally { entry.scheduled = false; }
    });
  }
  root.BIDailyCharts = {
    render(element, rows, draw) {
      let entry = entries.get(element);
      if (!entry) {
        entry = {element, visible:!observer, instance:null, drawn:null, failed:false, scheduled:false};
        entry.handle = {resize() { entry.instance?.resize(); }};
        entries.set(element, entry);
        status(entry, '图表将在滚动到此处时加载');
        observer?.observe(element);
      }
      if (entry.rows !== rows) entry.failed = false;
      entry.rows = rows; entry.draw = draw;
      if (!rows.length) {
        if (entry.drawn !== rows) status(entry, '当前筛选暂无趋势数据');
        entry.drawn = rows;
        entry.element.dataset.chartState = 'empty';
        return entry.handle;
      }
      schedule(entry);
      return entry.handle;
    },
  };
})(typeof window === 'undefined' ? globalThis : window);
