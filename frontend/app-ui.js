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

  const header=document.querySelector('body > header');
  if(!header)return;
  const make=(tag,className,text)=>{const element=document.createElement(tag);element.className=className;if(text)element.textContent=text;return element;};
  const icons={
    dhh:'M4 19V9m8 10V4m8 15v-7',jd:'M4 5h16v14H4z M4 10h16 M9 10v9',
    jdLowActivity:'M4 16l5-5 4 3 7-9 M16 5h4v4',bidMonitor:'M12 3v4m0 10v4M3 12h4m10 0h4 M12 7a5 5 0 1 0 0 10a5 5 0 0 0 0-10',
    adpflux:'M15 3v12a5 5 0 1 1-4-5 M15 3c0 4 3 6 6 6',
    tools:'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
    memos:'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5',
    account:'M12 3a4 4 0 1 0 0 8a4 4 0 0 0 0-8 M4 21v-3a5 5 0 0 1 5-5h6a5 5 0 0 1 5 5v3',
    menu:'M4 6h16M4 12h16M4 18h16'};
  function icon(key){const element=document.createElementNS('http://www.w3.org/2000/svg','svg');element.setAttribute('viewBox','0 0 24 24');element.setAttribute('aria-hidden','true');const path=document.createElementNS(element.namespaceURI,'path');path.setAttribute('d',icons[key]);element.append(path);return element;}
  const modules=[
    {key:'dhh',href:'/',label:'大航海日报'},
    {key:'jd',href:'/jd',label:'京东广义新'},
    {key:'jdLowActivity',href:'/jd-low-activity',label:'京东低活'},
    {key:'bidMonitor',href:'/bid-monitor.html',label:'出价监测'},
    {key:'adpflux',href:'/adpflux',label:'TikTok 账户'}
  ];
  const sidebar=make('aside','app-sidebar');sidebar.id='appSidebar';sidebar.setAttribute('aria-label','工作台导航');
  const brand=make('div','app-sidebar-brand');brand.append(make('span','app-sidebar-mark','数'),make('strong','app-sidebar-name','营销数据工作台'));
  const close=make('button','app-sidebar-close','×');close.type='button';close.setAttribute('aria-label','关闭导航');brand.append(close);
  const navigation=make('nav','app-sidebar-nav');navigation.setAttribute('aria-label','主导航');
  const caption=make('p','app-sidebar-caption','数据分析');
  const reports=make('div','app-sidebar-group');reports.id='appReportLinks';
  const workspace=make('div','app-sidebar-group');
  function navLink(item){const link=make('a','app-sidebar-link');link.href=item.href;link.title=item.label;link.dataset.module=item.key;link.append(icon(item.key),make('span','app-sidebar-label',item.label));if(normalize(item.href)===current)link.setAttribute('aria-current','page');return link;}
  const reportLinks=modules.map(item=>{const link=navLink(item);link.hidden=true;reports.append(link);return link;});
  workspace.append(navLink({key:'memos',href:'/memos.html',label:'备忘录'}),navLink({key:'tools',href:'/tools',label:'工具中心'}),navLink({key:'account',href:'/account',label:'账户与设置'}));
  navigation.append(caption,reports,make('p','app-sidebar-caption','工作空间'),workspace);
  const collapse=make('button','app-sidebar-collapse');collapse.type='button';collapse.append(icon('menu'),make('span','app-sidebar-label','收起导航'));
  sidebar.append(brand,navigation,collapse);
  const backdrop=make('button','app-sidebar-backdrop');backdrop.type='button';backdrop.tabIndex=-1;backdrop.setAttribute('aria-label','关闭导航');backdrop.hidden=true;
  document.body.prepend(sidebar,backdrop);document.body.classList.add('app-shell');
  const toggle=make('button','app-nav-toggle');toggle.type='button';toggle.setAttribute('aria-controls',sidebar.id);toggle.append(icon('menu'));toggle.setAttribute('aria-label','展开或收起导航');
  const brandTarget=header.querySelector('.brand,.product-brand')||header.querySelector('.header-row')||header;
  brandTarget.prepend(toggle);
  const mobile=matchMedia('(max-width: 1000px)');let collapsed=false,opened=false;
  try{collapsed=localStorage.getItem('bi-navigation-collapsed')==='true';}catch{}
  function syncNavigation(){
    document.body.classList.toggle('app-sidebar-compact',collapsed);
    document.body.classList.toggle('app-sidebar-open',mobile.matches&&opened);
    sidebar.inert=mobile.matches&&!opened;backdrop.hidden=!(mobile.matches&&opened);
    sidebar.setAttribute('role',mobile.matches?'dialog':'complementary');
    if(mobile.matches&&opened)sidebar.setAttribute('aria-modal','true');else sidebar.removeAttribute('aria-modal');
    toggle.setAttribute('aria-expanded',String(mobile.matches?opened:!collapsed));
    collapse.title=collapsed?'展开导航':'收起导航';collapse.setAttribute('aria-label',collapse.title);
    collapse.querySelector('.app-sidebar-label').textContent=collapse.title;
    if(!mobile.matches)opened=false;
  }
  function closeNavigation(){opened=false;syncNavigation();toggle.focus({preventScroll:true});}
  toggle.onclick=()=>{if(mobile.matches){opened=!opened;syncNavigation();if(opened)close.focus();}else{collapsed=!collapsed;try{localStorage.setItem('bi-navigation-collapsed',String(collapsed));}catch{}syncNavigation();}};
  collapse.onclick=()=>toggle.click();close.onclick=backdrop.onclick=closeNavigation;
  sidebar.addEventListener('keydown',event=>{
    if(!mobile.matches||!opened)return;
    if(event.key==='Escape'){event.preventDefault();closeNavigation();}
    if(event.key==='Tab'){
      const focusable=[...sidebar.querySelectorAll('a[href],button')].filter(element=>element.getClientRects().length&&!element.hidden);
      const first=focusable[0],last=focusable.at(-1);
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
    }
  });
  mobile.addEventListener('change',()=>{opened=false;syncNavigation();});syncNavigation();
  function updateModules(visibility){
    reportLinks.forEach((link,index)=>{link.hidden=visibility[modules[index].key]!==true;});
    caption.hidden=reportLinks.every(link=>link.hidden);
    const destinations=new Set([...modules.map(item=>normalize(item.href)),'/tools','/account']);
    header.querySelectorAll('a[href]').forEach(link=>{const url=new URL(link.href,location.origin);if(url.origin===location.origin&&destinations.has(normalize(url.pathname)))link.classList.add('app-relocated-link');});
  }
  document.addEventListener('bi:report-visibility',event=>updateModules(event.detail));
  updateModules(window.biReportVisibility||{});
})();
