(() => {
  const reportPaths = {
    dhh: '/',
    jd: '/jd',
    jdLowActivity: '/jd-low-activity',
    adpflux: '/adpflux'
  };
  const currentReport = Object.entries(reportPaths)
      .find(([, path]) => location.pathname === path)?.[0];
  const style = document.createElement('style');
  style.textContent = '.report-visibility-hidden{display:none!important}'
      + 'html.report-visibility-checking body{visibility:hidden}';
  document.head.appendChild(style);
  if (currentReport) document.documentElement.classList.add('report-visibility-checking');

  async function applyVisibility() {
    let visibility = {dhh: true, jd: true, jdLowActivity: true, adpflux: true};
    try {
      const response = await fetch('/api/report-visibility', {cache: 'no-store'});
      if (response.status === 401) {
        location.replace('/login');
        return;
      }
      if (response.ok) visibility = {...visibility, ...await response.json()};
    } catch {
      // Keep every report visible if the preference endpoint is temporarily unavailable.
    }

    const navigation = document.querySelector('.header-actions, nav.nav');
    const supplementalLinks = [{path: '/adpflux', label: 'TikTok账户'}];
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

    if (currentReport && visibility[currentReport] === false) {
      const destination = Object.entries(reportPaths)
          .find(([key]) => visibility[key] !== false)?.[1] || '/tools';
      location.replace(destination);
      return;
    }
    document.documentElement.classList.remove('report-visibility-checking');
    document.documentElement.classList.add('report-visibility-ready');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyVisibility, {once: true});
  } else {
    applyVisibility();
  }
})();
