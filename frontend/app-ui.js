(() => {
  const current = location.pathname.replace(/\/$/, '') || '/';
  document.body.dataset.route = current;
  document.querySelectorAll('nav a[href]').forEach(link => {
    const target = new URL(link.href, location.origin).pathname.replace(/\/$/, '') || '/';
    if (target === current) link.setAttribute('aria-current', 'page');
  });
  document.querySelectorAll('.table-wrap,.table-shell').forEach(element => {
    if (!element.hasAttribute('tabindex')) element.tabIndex = 0;
    if (!element.hasAttribute('aria-label')) element.setAttribute('aria-label', '数据表格，可横向滚动');
  });
})();
