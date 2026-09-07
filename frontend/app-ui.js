(() => {
  const normalize = path => path.replace(/\.html$/, '').replace(/\/index$/, '/').replace(/\/$/, '') || '/';
  const current = normalize(location.pathname);
  document.body.dataset.route = current;
  document.querySelectorAll('nav a[href]').forEach(link => {
    const url = new URL(link.href, location.origin);
    if (!url.hash && url.origin === location.origin && normalize(url.pathname) === current) link.setAttribute('aria-current', 'page');
  });
  document.querySelectorAll('.table-wrap,.table-shell').forEach(element => {
    if (!element.hasAttribute('tabindex')) element.tabIndex = 0;
    if (!element.hasAttribute('aria-label')) element.setAttribute('aria-label', '数据表格，可横向滚动');
  });
})();
