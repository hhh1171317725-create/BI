(() => {
  const nav = document.querySelector('.section-nav');
  const sections = ['report', 'sync-settings', 'pricing-settings', 'dingtalk-settings'];
  const panels = sections.map(id => document.getElementById(id));
  const formula = document.querySelector('main > .band:last-child');
  const all = document.createElement('a');
  all.href = '#all';
  all.textContent = '全部展开';
  nav.append(all);
  const links = [...nav.querySelectorAll('a')];
  const refresh = document.createElement('button');
  refresh.type = 'button';
  refresh.textContent = '读取最新快照';
  refresh.addEventListener('click', async () => {
    refresh.disabled = true;
    try {
      await syncLoad(true);
      feedback.textContent = document.getElementById('syncStatus').textContent;
    } catch (error) {
      feedback.textContent = error.message;
    } finally {
      refresh.disabled = false;
    }
  });
  const feedback = document.createElement('p');
  feedback.className = 'muted';
  feedback.setAttribute('role', 'status');
  document.querySelector('.report-toolbar').append(refresh);
  document.querySelector('.report-toolbar').after(feedback);

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
