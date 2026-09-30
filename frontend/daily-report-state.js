(function () {
  'use strict';
  window.BIReportState = {
    create() {
      const panel = document.getElementById('filterPanel');
      panel.classList.remove('hidden');
      const box = document.createElement('section');
      box.className = 'daily-query-state';
      box.setAttribute('aria-label', '报表查询状态');
      box.innerHTML = '<div class="daily-query-copy" role="status" aria-live="polite"><strong></strong><span></span></div><button type="button" class="secondary" hidden>重新查询</button>';
      panel.after(box);
      const title = box.querySelector('strong'), detail = box.querySelector('span'), retry = box.querySelector('button');
      const fields = ['start', 'end', 'accountId', 'excludeUnknownOptimizer'];
      let busy = false, applied = null, current = null, errorText = '';
      function selected() {
        return Object.fromEntries(fields.filter(id => document.getElementById(id)).map(id => {
          const value = document.getElementById(id).value.trim();
          return [id, id === 'excludeUnknownOptimizer' ? value === 'true' : value];
        }));
      }
      function describe() {
        if (!current) return '';
        const dates = current.range || [applied.start || '不限', applied.end || '不限'];
        return `当前结果：${dates[0]} 至 ${dates[1]}${applied.accountId ? ` · 账户 ${applied.accountId}` : ''}`;
      }
      function render() {
        const values = selected();
        const pending = applied && fields.some(id => id in values && values[id] !== applied[id]);
        const empty = current && Number(current.rows) === 0;
        box.dataset.state = busy ? 'loading' : errorText ? 'error' : pending ? 'pending' : empty ? 'empty' : 'ready';
        title.textContent = busy ? '正在查询报表…' : errorText ? '查询未完成' : pending ? '筛选已修改，待应用' : empty ? '所选范围暂无数据' : '数据已更新';
        detail.textContent = busy ? (current ? `${describe()} · 查询完成后更新` : '正在读取所选范围的数据，请稍候。')
          : errorText ? `${errorText}${current ? ` · ${describe()}，仍保留上次结果。` : ' · 可重新查询，或调整筛选范围。'}`
          : pending ? `${describe()} · 点击“应用筛选”更新结果。`
          : empty ? `${describe()} · 请调整日期或账户；如尚未同步，请联系管理员。` : describe();
        retry.hidden = !errorText || busy;
      }
      retry.addEventListener('click', () => document.getElementById('apply').click());
      panel.addEventListener('input', render);
      panel.addEventListener('change', render);
      return {
        refresh: render,
        isBusy: () => busy,
        async query(url, filters) {
          if (busy) throw new Error('查询正在进行，请稍候。');
          busy = true;
          errorText = '';
          const controls = [...document.querySelectorAll('#apply,#reset,#load')];
          const previous = controls.map(control => control.disabled);
          controls.forEach(control => { control.disabled = true; });
          document.getElementById('content').setAttribute('aria-busy', 'true');
          render();
          const controller = new AbortController();
          const timer = setTimeout(() => controller.abort(), 45000);
          try {
            const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(filters), signal: controller.signal});
            if (response.status === 401) { location.replace('/login'); throw new Error('登录已失效，请重新登录。'); }
            let result;
            try { result = await response.json(); }
            catch (error) { if (error.name === 'AbortError') throw error; throw new Error(`服务暂不可用（HTTP ${response.status}）`); }
            if (!response.ok) throw new Error(result.error || `查询失败（HTTP ${response.status}）`);
            if (!result || !Array.isArray(result.range) || !result.summary) throw new Error('返回的数据不完整，请稍后重试。');
            applied = {...filters};
            current = result;
            return result;
          } catch (error) {
            errorText = error.name === 'AbortError' ? '查询超过 45 秒，请缩小日期范围后重试。' : error.message || '网络异常，请稍后重试。';
            throw new Error(errorText);
          } finally {
            clearTimeout(timer);
            busy = false;
            controls.forEach((control, index) => { control.disabled = previous[index]; });
            document.getElementById('content').setAttribute('aria-busy', 'false');
            render();
          }
        },
      };
    },
  };
})();
