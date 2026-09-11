(() => {
  const nav = document.querySelector('.section-nav');
  const sections = ['report', 'strategy-lab', 'sync-settings', 'pricing-settings', 'dingtalk-settings'];
  const panels = sections.map(id => document.getElementById(id));
  const formula = document.querySelector('main > .band:last-child');
  const all = document.createElement('a');
  all.href = '#all';
  all.textContent = '全部展开';
  nav.append(all);
  const links = [...nav.querySelectorAll('a')];
  const refresh = document.createElement('button');
  refresh.type = 'button';
  refresh.id = 'reportRefresh';
  refresh.className = 'primary report-refresh';
  refresh.textContent = '刷新全部数据';
  refresh.title = '一次刷新计划快照、任务、单价和 gap';
  refresh.addEventListener('click', async () => {
    refresh.disabled = true;
    refresh.setAttribute('aria-busy', 'true');
    refresh.textContent = '正在刷新…';
    try {
      await syncLoad(true);
    } catch (error) {
      document.getElementById('message').textContent = error.message;
    } finally {
      refresh.disabled = false;
      refresh.removeAttribute('aria-busy');
      refresh.textContent = '刷新全部数据';
    }
  });
  document.querySelector('.report-toolbar').append(refresh);

  function showView() {
    const requested = location.hash.slice(1);
    const active = [...sections, 'all'].includes(requested) ? requested : 'report';
    panels.forEach(panel => {
      panel.hidden = active !== 'all' && panel.id !== active;
      if (panel.id === active && panel.id !== 'report') {
        const details = panel.querySelector(':scope > details');
        if (details) details.open = true;
      }
    });
    formula.hidden = active !== 'report' && active !== 'all';
    links.forEach(link => {
      if (link.hash === '#' + active) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    });
    document.body.dataset.bidView = active;
  }
  window.addEventListener('hashchange', showView);
  showView();

  const header = document.querySelector('body > header');
  new ResizeObserver(() => {
    document.body.style.setProperty('--bid-header-height', header.offsetHeight + 'px');
  }).observe(header);
})();
