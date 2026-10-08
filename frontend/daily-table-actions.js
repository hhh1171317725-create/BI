(function(root) {
  'use strict';
  const percentages = new Set(['ROI','现金ROI','预估ROI','实际ROI','有效首购率']);
  function csvCell(value, column) {
    if (percentages.has(column) && Number.isFinite(Number(value))) value = (Number(value) * 100).toFixed(2) + '%';
    let text = String(value ?? '');
    // Keep untrusted text inert in spreadsheets, and preserve long identifiers as text.
    if (typeof value !== 'number' && (/^[\s]*[=+\-@]/.test(text) || /^[\t\r\n]/.test(text) || /^\d{16,}$/.test(text))) text = "'" + text;
    return '"' + text.replaceAll('"','""') + '"';
  }
  function csv(rows, columns) {
    return '\uFEFF' + [columns.map(column => csvCell(column)).join(','), ...rows.map(row => columns.map(column => csvCell(row[column], column)).join(','))].join('\r\n');
  }
  function createSearch() {
    const sources = new WeakMap(), collator = value => String(value ?? '').normalize('NFKC').toLocaleLowerCase('zh-CN');
    return (rows, keys, query) => {
      const terms = collator(query).trim().split(/\s+/).filter(Boolean);
      if (!terms.length) return rows;
      let source = sources.get(rows);
      const signature = JSON.stringify(keys);
      if (!source || source.signature !== signature) {
        source = {signature, index:rows.map(row => keys.map(key => collator(row[key])).join('\u0000')), last:null};
        sources.set(rows, source);
      }
      const key = JSON.stringify(terms);
      if (source.last?.key === key) return source.last.rows;
      const matched = rows.filter((row, index) => terms.every(term => source.index[index].includes(term)));
      source.last = {key, rows:matched};
      return matched;
    };
  }
  if (typeof module === 'object' && module.exports) { module.exports = {csv, createSearch}; return; }
  root.BIDailyTableActions = {
    create({rerender, reportName, getRange}) {
      const bars = new Map(), search = createSearch();
      return {
        prepare(tools, scope, rows, columns, keys) {
          let bar = bars.get(tools);
          if (!bar) {
            const element = document.createElement('div');
            element.className = 'daily-table-actions';
            element.setAttribute('aria-label', '表格查找和导出');
            const label = document.createElement('label'); label.className = 'daily-table-search';
            const caption = document.createElement('span'); caption.textContent = '表内查找';
            const input = document.createElement('input'); input.type = 'search'; input.maxLength = 100;
            input.placeholder = '输入名称、ID 或日期'; input.setAttribute('aria-label', '搜索当前表格');
            const clear = document.createElement('button'); clear.type = 'button'; clear.className = 'secondary'; clear.textContent = '清除搜索'; clear.hidden = true;
            label.append(caption, input);
            const count = document.createElement('span'); count.className = 'daily-table-count'; count.setAttribute('role','status');
            const download = document.createElement('button'); download.type = 'button'; download.className = 'secondary'; download.textContent = '导出表格';
            download.title = '导出当前已选列和全部匹配行，包含其他页';
            element.append(label, clear, count, download);
            tools.before(element);
            root.BIDailyTablePresentation?.attach(element, tools, scope);
            bar = {element, input, clear, count, download, queries:new Map(), query:'', timer:null, scope};
            bars.set(tools, bar);
            const apply = () => {
              clearTimeout(bar.timer); bar.query = input.value; bar.queries.set(bar.scope, bar.query); rerender(bar.scope);
            };
            input.addEventListener('input', event => {
              clearTimeout(bar.timer);
              if (!event.isComposing) bar.timer = setTimeout(apply, 180);
            });
            input.addEventListener('compositionend', () => { clearTimeout(bar.timer); bar.timer = setTimeout(apply,180); });
            input.addEventListener('keydown', event => {
              if (event.isComposing) return;
              if (event.key === 'Enter') { event.preventDefault(); apply(); }
              if (event.key === 'Escape') { input.value = ''; apply(); }
            });
            clear.onclick = () => { input.value = ''; apply(); input.focus(); };
            download.onclick = () => {
              if (input.value !== bar.query) apply();
              if (!bar.rows?.length) return;
              const range = getRange().map(value => String(value).replace(/[^\d-]/g,'')).join('_');
              const blob = new Blob([csv(bar.rows, bar.columns)], {type:'text/csv;charset=utf-8'});
              const url = URL.createObjectURL(blob), link = document.createElement('a');
              const [kind, view] = bar.scope.split(':');
              const kindName = {main:'汇总',drill:'日期明细','optimizer-breakdown':'优化师明细'}[kind] || '表格';
              const viewName = {by_optimizer:'优化师',by_project:'项目',by_task:'任务',by_date:'日期',by_account:'账户',by_media:'媒体',by_promoter:'推客'}[view] || '';
              link.href = url; link.download = `${reportName}_${viewName}${kindName}_${range}.csv`;
              document.body.append(link); link.click(); link.remove();
              setTimeout(() => URL.revokeObjectURL(url), 1000);
            };
          }
          if (bar.scope !== scope) {
            clearTimeout(bar.timer); bar.scope = scope; bar.query = bar.queries.get(scope) || ''; bar.input.value = bar.query;
          }
          const matching = search(rows, keys, bar.query);
          bar.input.setAttribute('aria-label', scope.startsWith('main:') ? '搜索当前汇总表格' : '搜索当前明细表格');
          bar.rows = matching; bar.columns = columns;
          bar.clear.hidden = !bar.query;
          bar.count.textContent = bar.query ? `匹配 ${matching.length} / ${rows.length} 条 · 汇总与趋势按查询范围` : `共 ${rows.length} 条 · 导出包含所有页`;
          bar.download.disabled = !matching.length;
          return matching;
        },
      };
    },
  };
})(typeof window === 'undefined' ? globalThis : window);
