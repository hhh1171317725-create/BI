(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  let current = null, baseline = '', busy = false, ready = false, view = 'all', offset = 0;
  let listRequest = 0, detailRequest = 0, listController, searchTimer;
  let draftPrefix = '', draftTimer, offeredDraft = null, draftSaved = false;
  const values = () => ({title: $('#title').value, content: $('#content').value, tags: $('#tags').value, pinned: $('#pinned').checked});
  const dirty = () => !$('#editor').hidden && JSON.stringify(values()) !== baseline;
  const date = value => new Date(value).toLocaleString('zh-CN', {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});
  function message(text = '', error = false) { $('#message').textContent = text; $('#message').dataset.error = String(error); }
  const draftKey = () => draftPrefix + (current?.id || 'new');
  function clearDraft() {
    clearTimeout(draftTimer); draftSaved = false;
    try { if (draftPrefix) sessionStorage.removeItem(draftKey()); } catch {}
  }
  function readDraft(key) {
    try {
      const draft = JSON.parse(sessionStorage.getItem(key) || 'null');
      if (!draft || Date.now() - draft.at > 7 * 86400000 || !draft.values
          || !['title','content','tags'].every(k => typeof draft.values[k] === 'string')
          || draft.values.title.length > 160 || draft.values.content.length > 50000 || draft.values.tags.length > 256
          || typeof draft.values.pinned !== 'boolean') return null;
      return draft;
    } catch { return null; }
  }
  function persistDraft() {
    clearTimeout(draftTimer);
    if (!draftPrefix || offeredDraft || current?.deleted || $('#editor').hidden) return;
    if (!dirty()) { clearDraft(); return; }
    try {
      sessionStorage.setItem(draftKey(), JSON.stringify({id:current?.id || null,version:current?.version,at:Date.now(),values:values()}));
      draftSaved = true; controls();
    } catch { draftSaved = false; }
  }
  function offerDraft() {
    offeredDraft = current?.deleted ? null : readDraft(draftKey());
    $('#draftNotice').hidden = !offeredDraft;
    if (offeredDraft) $('#draftText').textContent = `发现 ${date(offeredDraft.at)} 的未保存草稿。${current?.id && offeredDraft.version !== current.version ? '网站内容已有新版本，请核对后恢复。' : '可恢复后继续编辑。'}`;
    controls();
  }
  async function api(path, options = {}) {
    const response = await fetch('/api/memos' + path, {cache:'no-store', ...options, headers:{'Content-Type':'application/json'}});
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401) throw Error('登录已失效，请先复制未保存的正文，再重新登录');
      throw Error(data.error || `请求失败（${response.status}），请重试`);
    }
    return data;
  }
  function controls() {
    const deleted = !!current?.deleted;
    $('#fields').disabled = busy || deleted || !!offeredDraft;
    $('#save').disabled = busy || !dirty();
    $('#save').hidden = deleted;
    $('#remove').hidden = !current?.id || deleted;
    $('#restore').hidden = !deleted;
    for (const id of ['remove','restore','copy']) $('#' + id).disabled = busy;
    $('#newMemo').disabled = $('#emptyNew').disabled = busy || !ready;
    $('#charCount').textContent = `${$('#content').value.length.toLocaleString()} / 50,000 字符`;
    $('#saveState').textContent = busy ? '处理中…' : deleted ? '已在回收站 · 恢复后可编辑' : dirty() ? (draftSaved ? '未保存到网站 · 已暂存本标签页' : '有未保存的修改') : current?.id ? '已保存到网站' : '新建备忘录';
  }
  function renderEditor(item) {
    current = item;
    $('#emptyEditor').hidden = true; $('#editor').hidden = false;
    $('#title').value = item.title || ''; $('#content').value = item.content || '';
    $('#tags').value = item.tags || ''; $('#pinned').checked = !!item.pinned;
    baseline = JSON.stringify(values());
    $('#timestamps').textContent = item.id ? `创建于 ${new Date(item.createdAt).toLocaleDateString('zh-CN')} · 更新于 ${date(item.updatedAt)}` : '填写标题和内容后保存';
    markSelection(); controls();
    offerDraft();
  }
  function markSelection() {
    document.querySelectorAll('.memo-card').forEach(card => card.setAttribute('aria-current', String(card.dataset.id === String(current?.id))));
  }
  function canLeave() { return !busy && (!dirty() || confirm('有尚未保存的修改，确定放弃这些修改吗？')); }
  function newMemo() {
    if (!ready || !canLeave()) return;
    if (dirty()) clearDraft();
    detailRequest++; message(); renderEditor({}); $('#title').focus();
  }
  async function openMemo(id) {
    if (!canLeave()) return;
    const request = ++detailRequest;
    busy = true; controls(); message();
    try {
      const item = await api('/' + id);
      if (request === detailRequest) { if (dirty()) clearDraft(); renderEditor(item); }
    } catch (error) { if (request === detailRequest) message(error.message, true); }
    finally { if (request === detailRequest) { busy = false; controls(); } }
  }
  function node(tag, className, text) { const element = document.createElement(tag); element.className = className; element.textContent = text; return element; }
  function card(item) {
    const button = node('button','memo-card',''); button.type = 'button'; button.dataset.id = item.id;
    button.append(node('strong','',item.title), node('span','memo-preview',item.preview || '暂无正文'));
    const meta = node('span','memo-card-meta','');
    if (item.pinned) meta.append(node('span','pin-badge','置顶'));
    item.tags.split(',').filter(Boolean).slice(0,2).forEach(tag => meta.append(node('span','memo-badge',tag)));
    const time = node('time','',date(item.updatedAt)); time.dateTime = new Date(item.updatedAt).toISOString(); meta.append(time); button.append(meta);
    button.onclick = () => openMemo(item.id); return button;
  }
  async function list(more = false) {
    const request = ++listRequest;
    listController?.abort(); listController = new AbortController();
    $('#loadMore').disabled = true; $('#refresh').disabled = true;
    $('#listStatus').textContent = '正在查询…';
    if (!more) { offset = 0; $('#memoList').replaceChildren(); $('#loadMore').hidden = true; }
    const params = new URLSearchParams({q:$('#search').value.trim(),tag:$('#tagFilter').value,view,offset:String(offset)});
    try {
      const data = await api('?' + params, {signal:listController.signal});
      if (request !== listRequest) return;
      $('#memoList').append(...data.items.map(card));
      offset = data.nextOffset; $('#loadMore').hidden = !data.hasMore;
      $('#listStatus').textContent = `已显示 ${offset} 条`;
      if (!offset) $('#memoList').append(node('p','list-empty',view === 'trash' ? '回收站里没有匹配的备忘录' : '暂无匹配内容，试试其他关键词或新建备忘录'));
      markSelection();
    } catch (error) {
      if (error.name !== 'AbortError' && request === listRequest) { $('#listStatus').textContent = '加载失败，点击刷新重试'; message(error.message, true); }
    } finally {
      if (request === listRequest) { $('#loadMore').disabled = false; $('#refresh').disabled = false; }
    }
  }
  async function meta() {
    const data = await api('/meta');
    $('#totalCount').textContent = data.total; $('#pinCount').textContent = data.pinned; $('#trashCount').textContent = data.trash;
    const selected = $('#tagFilter').value;
    $('#tagFilter').replaceChildren(new Option('全部标签',''));
    Object.entries(data.tags).forEach(([tag,count]) => $('#tagFilter').add(new Option(`${tag} (${count})`,tag)));
    if (selected && !Object.hasOwn(data.tags,selected)) $('#tagFilter').add(new Option(selected,selected));
    $('#tagFilter').value = selected;
  }
  async function refresh() { await Promise.all([list(),meta().catch(error => message(error.message,true))]); }
  async function save(event) {
    event?.preventDefault();
    if (busy || !ready || !dirty() || current?.deleted || !$('#editor').reportValidity()) return;
    busy = true; controls(); message();
    try {
      const item = await api(current?.id ? '/' + current.id : '', {method:current?.id ? 'PUT' : 'POST',body:JSON.stringify({...values(),version:current?.version})});
      clearDraft(); renderEditor(item); message('已保存'); await refresh();
    } catch (error) { message(error.message, true); }
    finally { busy = false; controls(); }
  }
  async function changeDeleted(restore) {
    if (!current?.id || busy || (!restore && !confirm(dirty() ? '这条备忘录有未保存修改。确定放弃修改并移入回收站吗？' : '将这条备忘录移入回收站？之后可以恢复。'))) return;
    busy = true; controls(); message();
    try {
      await api('/' + current.id + (restore ? '/restore' : '?version=' + current.version), {method:restore ? 'POST' : 'DELETE', ...(restore ? {body:JSON.stringify({version:current.version})} : {})});
      clearDraft(); $('#draftNotice').hidden = true;
      current = null; baseline = ''; $('#editor').hidden = true; $('#emptyEditor').hidden = false;
      message(restore ? '已恢复，可在全部备忘录中找到' : '已移入回收站'); await refresh();
    } catch (error) { message(error.message,true); }
    finally { busy = false; controls(); }
  }
  $('#editor').onsubmit = save; $('#editor').oninput = () => {
    draftSaved = false; controls(); clearTimeout(draftTimer); draftTimer = setTimeout(persistDraft,400);
  };
  $('#recoverDraft').onclick = () => {
    if (!offeredDraft || busy) return;
    if (current?.id && offeredDraft.version !== current.version && !confirm('网站内容已有新版本。恢复草稿后保存会替换当前内容，确定恢复吗？')) return;
    const draft = offeredDraft.values;
    $('#title').value = draft.title; $('#content').value = draft.content; $('#tags').value = draft.tags; $('#pinned').checked = draft.pinned;
    $('#draftNotice').hidden = true; offeredDraft = null; persistDraft(); controls();
  };
  $('#discardDraft').onclick = () => { if (busy) return; clearDraft(); offeredDraft = null; $('#draftNotice').hidden = true; controls(); };
  $('#newMemo').onclick = $('#emptyNew').onclick = newMemo;
  $('#remove').onclick = () => changeDeleted(false); $('#restore').onclick = () => changeDeleted(true);
  $('#copy').onclick = async () => { try { await navigator.clipboard.writeText($('#content').value); message('正文已复制'); } catch { $('#content').focus(); $('#content').select(); message('请按 Ctrl / ⌘ + C 复制选中的正文'); } };
  $('#search').oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => list(),250); };
  $('#searchForm').onsubmit = event => { event.preventDefault(); clearTimeout(searchTimer); list(); };
  $('#tagFilter').onchange = () => list(); $('#loadMore').onclick = () => list(true); $('#refresh').onclick = refresh;
  document.querySelectorAll('[data-view]').forEach(button => button.onclick = () => { view = button.dataset.view; document.querySelectorAll('[data-view]').forEach(item => item.setAttribute('aria-pressed',String(item === button))); list(); });
  window.addEventListener('beforeunload', event => { persistDraft(); if (dirty() || busy) { event.preventDefault(); event.returnValue = ''; } });
  document.addEventListener('visibilitychange', () => { if (document.hidden) persistDraft(); });
  document.addEventListener('keydown', event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); if (!$('#editor').hidden) save(); } });
  async function start() {
    try {
      const response = await fetch('/api/session',{cache:'no-store'});
      if (!response.ok) throw Error('登录状态加载失败，请刷新页面重试');
      const session = await response.json();
      if (!session.authenticated) { location.replace('/login'); return; }
      // Drafts are isolated by login account and browser tab; no database write occurs until Save.
      draftPrefix = `bi-memo-draft:${session.user.id}:`;
      ready = true; controls(); await refresh();
      let latest = null;
      try {
        for (const key of Object.keys(sessionStorage).filter(key => key.startsWith(draftPrefix))) {
          const draft = readDraft(key);
          if (!draft) { sessionStorage.removeItem(key); continue; }
          if (!latest || draft.at > latest.at) latest = draft;
        }
      } catch {}
      if (latest && !current && !dirty()) { if (latest.id) await openMemo(latest.id); else renderEditor({}); }
    } catch (error) { message(error.message,true); $('#listStatus').textContent = '无法加载'; }
  }
  start();
})();
