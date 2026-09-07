(() => {
  if (document.querySelector('script[data-pet-loader]')) return;
  const css = document.createElement('link');
  css.rel = 'stylesheet';
  css.href = '/pet.css?v=20260907-global';
  document.head.appendChild(css);
  const script = document.createElement('script');
  script.dataset.petLoader = 'true';
  script.src = '/pet.js?v=20260907-global';
  document.head.appendChild(script);
})();
