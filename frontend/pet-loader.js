(() => {
  if (window.biPetLoadScheduled || document.querySelector('script[data-pet-loader]')) return;
  window.biPetLoadScheduled = true;
  function loadPet() {
  const css = document.createElement('link');
  css.rel = 'stylesheet';
  css.href = '/pet.css?v=20260907-global';
  document.head.appendChild(css);
  const script = document.createElement('script');
  script.dataset.petLoader = 'true';
  script.src = '/pet.js?v=20260909-bid';
  document.head.appendChild(script);
  }
  function schedule() {
    if ('requestIdleCallback' in window) window.requestIdleCallback(loadPet, {timeout:1500});
    else window.setTimeout(loadPet, 300);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', schedule, {once:true});
  else schedule();
})();
