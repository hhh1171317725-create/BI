(() => {
  "use strict";
  if (document.querySelector('.data-pet')) return;
  const loginPage = /^\/login(?:\.html)?\/?$/.test(location.pathname);
  const reportPage = typeof window.getPetReportContext === 'function';

  const history = [];
  let queryState = null;
  let contextKey = "";
  let busy = false;
  let activeRequest = null;
  let scrollFrame = 0;
  let composerIsComposing = false;
  const legacyAiConfigStorageKey = "data-pet-ai-config-v1";
  const petPositionStorageKey = "data-pet-position-v1";
  let aiStatus = {
    provider: "deepseek",
    model: "deepseek-v4-flash",
    configured: false,
    canManage: false,
  };

  function createPet() {
    const root = document.createElement("aside");
    root.className = "data-pet";
    root.setAttribute("aria-label", "数据分析宠物");
    root.innerHTML = `
      <section class="data-pet-panel" id="data-pet-panel" aria-label="AI 数据助手对话" hidden>
        <header class="data-pet-head">
          <div class="data-pet-identity">
            <img class="data-pet-avatar" src="/assets/miku-pet.png" alt="" />
            <div><div class="data-pet-name">初音未来 · 数据助手</div><div class="data-pet-mode">报表对话与数据分析</div></div>
          </div>
          <div class="data-pet-head-actions">
            <button class="data-pet-rules-toggle" type="button" aria-label="分析规则" title="制定文字规则或指标条件">规则</button>
            <button class="data-pet-reset" type="button" aria-label="新对话" title="新对话：清除聊天与分析筛选">↺</button>
            <button class="data-pet-settings-toggle" type="button" aria-label="AI 设置" title="AI 设置" hidden>⚙</button>
            <button class="data-pet-close" type="button" aria-label="关闭对话">×</button>
          </div>
        </header>
        <div class="data-pet-settings" hidden>
          <select class="data-pet-provider" aria-label="AI 提供商">
            <option value="deepseek">DeepSeek</option>
            <option value="openai">OpenAI</option>
          </select>
          <input class="data-pet-model" autocomplete="off" placeholder="模型名称" aria-label="AI 模型名称" />
          <input class="data-pet-api-key" type="password" autocomplete="off" placeholder="粘贴 API Key" aria-label="AI API Key" />
          <button class="data-pet-settings-save" type="button">保存</button>
          <div class="data-pet-settings-note">配置保存在服务器，所有已登录设备共享；页面不会回显完整 Key。</div>
        </div>
        <div class="data-pet-messages" role="log" aria-label="对话记录" aria-live="polite"></div>
        <button class="data-pet-latest" type="button" hidden>查看新回复 ↓</button>
        <div class="data-pet-quick">
          <button type="button" data-question="按保存的分析规则检查当前报表">规则分析</button>
          <button type="button" data-question="诊断当前报表的亏损并给出优化建议">诊断亏损</button>
          <button type="button" data-question="按利润给优化师排名">利润排名</button>
          <button type="button" data-question="对比上期，哪些指标变化最大？">对比上期</button>
        </div>
        <form class="data-pet-form">
          <textarea class="data-pet-input" rows="1" maxlength="500" autocomplete="off" placeholder="输入问题，Enter 发送" aria-label="输入问题"></textarea>
          <button class="data-pet-send" type="submit">发送</button>
          <button class="data-pet-stop" type="button" hidden>停止</button>
        </form>
      </section>
      <button class="data-pet-toggle" type="button" aria-label="打开初音数据助手" aria-controls="data-pet-panel" aria-expanded="false">
        <img src="/assets/miku-pet.png" alt="" draggable="false" />
        <span class="data-pet-dot"></span>
      </button>
    `;
    document.body.appendChild(root);
    return root;
  }

  const root = createPet();
  const panel = root.querySelector(".data-pet-panel");
  const toggle = root.querySelector(".data-pet-toggle");
  const messages = root.querySelector(".data-pet-messages");
  const input = root.querySelector(".data-pet-input");
  const send = root.querySelector(".data-pet-send");
  const mode = root.querySelector(".data-pet-mode");
  const settings = root.querySelector(".data-pet-settings");
  const settingsToggle = root.querySelector(".data-pet-settings-toggle");
  const rulesToggle = root.querySelector(".data-pet-rules-toggle");
  const providerInput = root.querySelector(".data-pet-provider");
  const modelInput = root.querySelector(".data-pet-model");
  const apiKeyInput = root.querySelector(".data-pet-api-key");
  const head = root.querySelector(".data-pet-head");
  const stop = root.querySelector('.data-pet-stop');
  const latest = root.querySelector('.data-pet-latest');
  if (!reportPage) {
    const questions = loginPage ? ['如何登录网站？'] : ['这个页面能做什么？', '如何分析投放数据？', 'ROI是什么意思？'];
    root.querySelector('.data-pet-quick').replaceChildren(...questions.map(question => {
      const button = document.createElement('button');
      button.type = 'button'; button.dataset.question = question; button.textContent = question;
      return button;
    }));
  }
  let dragState = null;

  function updatePanelDirection() {
    if (window.innerWidth <= 560) {
      root.classList.remove("data-pet-open-right", "data-pet-open-down");
      return;
    }
    if (panel.hidden) return;
    const rootRect = root.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const gap = 14;
    root.classList.toggle(
      "data-pet-open-right",
      rootRect.left + panelRect.width <= window.innerWidth - 8,
    );
    root.classList.toggle(
      "data-pet-open-down",
      rootRect.bottom + gap + panelRect.height <= window.innerHeight - 8,
    );
  }

  function setPetPosition(left, top, save = false) {
    const rootRect = root.getBoundingClientRect();
    const maxLeft = Math.max(8, window.innerWidth - rootRect.width - 8);
    const maxTop = Math.max(8, window.innerHeight - rootRect.height - 8);
    const nextLeft = Math.min(Math.max(8, left), maxLeft);
    const nextTop = Math.min(Math.max(8, top), maxTop);
    root.style.left = `${nextLeft}px`;
    root.style.top = `${nextTop}px`;
    root.style.right = "auto";
    root.style.bottom = "auto";
    updatePanelDirection();
    if (save) {
      try {
        localStorage.setItem(petPositionStorageKey, JSON.stringify({ left: nextLeft, top: nextTop }));
      } catch {
        // 拖动功能不依赖浏览器是否允许本地存储。
      }
    }
  }

  function keepPanelInViewport() {
    if (panel.hidden || window.innerWidth <= 560) return;
    updatePanelDirection();
    const panelRect = panel.getBoundingClientRect();
    const rootRect = root.getBoundingClientRect();
    const horizontalShift = panelRect.left < 8
      ? 8 - panelRect.left
      : panelRect.right > window.innerWidth - 8
        ? window.innerWidth - 8 - panelRect.right
        : 0;
    const verticalShift = panelRect.top < 8
      ? 8 - panelRect.top
      : panelRect.bottom > window.innerHeight - 8
        ? window.innerHeight - 8 - panelRect.bottom
        : 0;
    if (horizontalShift || verticalShift) {
      setPetPosition(rootRect.left + horizontalShift, rootRect.top + verticalShift);
    }
  }

  function restorePetPosition() {
    try {
      const position = JSON.parse(localStorage.getItem(petPositionStorageKey) || "{}");
      if (Number.isFinite(position.left) && Number.isFinite(position.top)) {
        setPetPosition(position.left, position.top);
      }
    } catch {
      // 使用默认右下角位置。
    }
  }

  function startPetDrag(event, source) {
    if (event.button !== 0 || dragState) return;
    if (source === "head" && event.target.closest("button, input, select")) return;
    const rect = root.getBoundingClientRect();
    dragState = {
      pointerId: event.pointerId,
      source,
      startX: event.clientX,
      startY: event.clientY,
      left: rect.left,
      top: rect.top,
      moved: false,
    };
    root.classList.add("data-pet-dragging");
    event.currentTarget.setPointerCapture?.(event.pointerId);
    event.preventDefault();
  }

  function movePet(event) {
    if (!dragState || event.pointerId !== dragState.pointerId) return;
    const deltaX = event.clientX - dragState.startX;
    const deltaY = event.clientY - dragState.startY;
    if (!dragState.moved && Math.hypot(deltaX, deltaY) < 4) return;
    dragState.moved = true;
    setPetPosition(dragState.left + deltaX, dragState.top + deltaY);
    event.preventDefault();
  }

  function finishPetDrag(event) {
    if (!dragState || event.pointerId !== dragState.pointerId) return;
    const moved = dragState.moved;
    const source = dragState.source;
    dragState = null;
    root.classList.remove("data-pet-dragging");
    if (moved) {
      const rect = root.getBoundingClientRect();
      setPetPosition(rect.left, rect.top);
      keepPanelInViewport();
      const finalRect = root.getBoundingClientRect();
      setPetPosition(finalRect.left, finalRect.top, true);
    } else if (source === "toggle" && event.type === "pointerup") {
      panel.hidden ? openPet() : closePet();
    }
  }

  function clearLegacyAiConfig() {
    try {
      localStorage.removeItem(legacyAiConfigStorageKey);
    } catch {
      // 服务器配置不依赖浏览器本地存储。
    }
  }

  function updateAiMode() {
    if (aiStatus.configured) {
      mode.textContent =
        `${aiStatus.provider === "openai" ? "OpenAI" : "DeepSeek"} 已配置 · 服务器共享`;
      return;
    }
    mode.textContent = aiStatus.canManage ? "点击 ⚙ 配置 AI · 本地分析" : "AI 未配置 · 本地分析";
  }

  async function loadAiConfig() {
    clearLegacyAiConfig();
    const response = await fetch("/api/pet/config", { cache: "no-store", signal: AbortSignal.timeout(10000) });
    if (response.status === 401) {
      location.replace("/login");
      throw new Error("登录已失效");
    }
    let result;
    try { result = await response.json(); } catch { throw new Error('读取 AI 配置失败，请稍后重试。'); }
    if (!response.ok) throw new Error(result.error || "读取 AI 配置失败");
    aiStatus = {
      provider: result.provider === "openai" ? "openai" : "deepseek",
      model: result.model || "",
      configured: Boolean(result.configured),
      canManage: Boolean(result.canManage),
    };
    settingsToggle.hidden = !aiStatus.canManage;
    providerInput.value = aiStatus.provider;
    modelInput.value = aiStatus.model;
    apiKeyInput.placeholder = aiStatus.configured ? "已配置，留空保持不变" : "粘贴 API Key";
    updateAiMode();
  }

  function nearBottom() {
    return messages.scrollHeight - messages.scrollTop - messages.clientHeight < 64;
  }

  function followMessages(follow) {
    if (!follow) { latest.hidden = false; return; }
    latest.hidden = true;
    cancelAnimationFrame(scrollFrame);
    scrollFrame = requestAnimationFrame(() => { messages.scrollTop = messages.scrollHeight; });
  }

  function addMessage(role, text, className = "", forceScroll = false) {
    const follow = forceScroll || nearBottom();
    const item = document.createElement("div");
    item.className = `data-pet-message ${role}${className ? ` ${className}` : ""}`;
    item.textContent = String(text ?? '');
    messages.appendChild(item);
    followMessages(follow);
    return item;
  }

  // Render a small Markdown subset with text nodes only; model HTML is never executed.
  function inlineText(parent, text) {
    for (const part of String(text).split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/g)) {
      if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
        const strong = document.createElement('strong'); strong.textContent = part.slice(2, -2); parent.append(strong);
      } else if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
        const code = document.createElement('code'); code.textContent = part.slice(1, -1); parent.append(code);
      } else parent.append(document.createTextNode(part));
    }
  }

  function renderAnswer(text) {
    const content = document.createElement('div'); content.className = 'data-pet-answer';
    const lines = String(text).replaceAll('\r\n', '\n').split('\n');
    const cells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
    const tableRule = line => line.includes('|') && cells(line).every(cell => /^:?-{3,}:?$/.test(cell));
    let paragraph = null, list = null;
    for (let index = 0; index < lines.length; index++) {
      const line = lines[index];
      if (!line.trim()) { paragraph = list = null; continue; }
      if (line.trim().startsWith('```')) {
        paragraph = list = null; const code = document.createElement('pre'), body = [];
        while (++index < lines.length && !lines[index].trim().startsWith('```')) body.push(lines[index]);
        code.textContent = body.join('\n'); content.append(code); continue;
      }
      if (index + 1 < lines.length && line.includes('|') && tableRule(lines[index + 1])) {
        paragraph = list = null;
        const wrap = document.createElement('div'); wrap.className = 'data-pet-table';
        const table = document.createElement('table'), header = document.createElement('thead'), row = document.createElement('tr');
        const labels = cells(line);
        for (const label of labels) { const cell = document.createElement('th'); cell.scope = 'col'; inlineText(cell, label); row.append(cell); }
        header.append(row); table.append(header); index++;
        const body = document.createElement('tbody');
        while (index + 1 < lines.length && lines[index + 1].includes('|') && lines[index + 1].trim()) {
          const values = cells(lines[++index]), row = document.createElement('tr');
          for (let col = 0; col < labels.length; col++) { const cell = document.createElement('td'); inlineText(cell, values[col] || ''); row.append(cell); }
          body.append(row);
        }
        table.append(body); wrap.append(table); content.append(wrap); continue;
      }
      const heading = line.match(/^\s{0,3}#{1,4}\s+(.+)$/);
      if (heading) { paragraph = list = null; const title = document.createElement('h4'); inlineText(title, heading[1]); content.append(title); continue; }
      const bullet = line.match(/^\s*(?:[-*•]\s+|\d+[.)、]\s+)(.+)$/);
      if (bullet) {
        paragraph = null;
        const type = /^\s*\d/.test(line) ? 'ol' : 'ul';
        if (!list || list.tagName.toLowerCase() !== type) { list = document.createElement(type); content.append(list); }
        const item = document.createElement('li'); inlineText(item, bullet[1]); list.append(item); continue;
      }
      list = null;
      if (!paragraph) { paragraph = document.createElement('p'); content.append(paragraph); }
      else paragraph.append(document.createElement('br'));
      inlineText(paragraph, line);
    }
    return content;
  }

  function addAnswer(result, follow) {
    const card = document.createElement('div'); card.className = 'data-pet-message assistant result';
    if (result.scope) {
      const scope = document.createElement('div'); scope.className = 'data-pet-scope';
      scope.textContent = `分析范围：${result.scope}`; card.append(scope);
    }
    card.append(renderAnswer(result.reply || '本次未返回回答，请重试。'));
    if (Array.isArray(result.rulesApplied)) {
      const rules = document.createElement('div'); rules.className = 'data-pet-rule-status';
      const label = document.createElement('span');
      label.textContent = result.rulesApplied.length
        ? `启用 ${result.rulesApplied.length} 条规则 · v${result.rulesVersion ?? 0}`
        : '当前报表没有启用的分析规则';
      const view = document.createElement('button'); view.type = 'button'; view.textContent = '查看规则';
      view.addEventListener('click', () => void openRules(view)); rules.append(label, view); card.append(rules);
      if (result.mode === 'ai' && result.ruleChecks) {
        const details = document.createElement('details'); details.className = 'data-pet-rule-checks';
        const title = document.createElement('summary'); title.textContent = '查看条件判断依据';
        // The server report is literal data, including administrator supplied rule titles.
        const checks = document.createElement('div'); checks.textContent = result.ruleChecks;
        details.append(title, checks); card.append(details);
      }
    }
    if (result.notice) { const note = document.createElement('div'); note.className = 'data-pet-notice'; note.textContent = result.notice; card.append(note); }
    const tools = document.createElement('div'); tools.className = 'data-pet-answer-tools';
    const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = '复制回答';
    copy.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText([result.scope ? `分析范围：${result.scope}` : '', result.reply, result.notice].filter(Boolean).join('\n\n')); copy.textContent = '已复制'; }
      catch { copy.textContent = '复制失败，请选中文字'; }
    });
    tools.append(copy); card.append(tools); messages.append(card); followMessages(follow);
  }

  let rulesEditorLoading = null;
  function loadRulesEditor() {
    if (window.PetRules && document.querySelector('link[data-pet-rules-style]')?.dataset.petRulesReady === 'true') return Promise.resolve();
    if (rulesEditorLoading) return rulesEditorLoading;
    rulesEditorLoading = new Promise((resolve, reject) => {
      let css = document.querySelector('link[data-pet-rules-style]');
      let timer;
      const script = document.createElement('script');
      const fail = () => {
        clearTimeout(timer); script.remove(); if (css && css.dataset.petRulesReady !== 'true') css.remove();
        reject(new Error('分析规则组件加载失败，请重试'));
      };
      let styleReady = css?.dataset.petRulesReady === 'true', scriptReady = Boolean(window.PetRules);
      const ready = () => {
        if (styleReady && scriptReady) { clearTimeout(timer); window.PetRules ? resolve() : fail(); }
      };
      timer = setTimeout(fail, 15000);
      if (!css) {
        css = document.createElement('link'); css.rel = 'stylesheet';
        css.dataset.petRulesStyle = 'true'; css.href = '/pet-rules.css?v=20261009-rules';
        css.onload = () => { css.dataset.petRulesReady = 'true'; styleReady = true; ready(); }; css.onerror = fail;
        document.head.append(css);
      }
      if (!scriptReady) {
        script.src = '/pet-rules.js?v=20261009-rules';
        script.onload = () => { scriptReady = true; ready(); }; script.onerror = fail;
        document.head.append(script);
      } else ready();
    }).catch(error => { rulesEditorLoading = null; throw error; });
    return rulesEditorLoading;
  }

  async function openRules(button) {
    button.disabled = true;
    try {
      await loadRulesEditor(); button.disabled = false;
      button.focus({preventScroll: true});
      await window.PetRules.open();
    }
    catch (error) { addMessage('assistant', error.message || '分析规则暂时无法打开，请重试。', 'error', true); }
    finally { button.disabled = false; }
  }

  rulesToggle.addEventListener('click', () => void openRules(rulesToggle));
  window.addEventListener('pet:rules-saved', event => {
    addMessage('assistant', `分析规则已保存（v${event.detail?.version ?? 0}）。下一次分析将使用新规则，历史回答保留原来的判断。`, '', true);
  });

  function resizeInput() {
    input.style.height = 'auto';
    input.style.height = `${Math.min(112, Math.max(38, input.scrollHeight))}px`;
  }

  function updateRequestControls() {
    send.disabled = busy; send.hidden = busy; stop.hidden = !busy;
    input.setAttribute('aria-busy', String(busy));
    root.querySelectorAll('.data-pet-quick button').forEach(button => { button.disabled = busy; });
  }

  function showRequestError(request, text) {
    request.thinking.classList.remove('thinking'); request.thinking.classList.add('error');
    request.thinking.textContent = text;
    const retry = document.createElement('button'); retry.type = 'button'; retry.className = 'data-pet-retry'; retry.textContent = '重试';
    retry.addEventListener('click', () => { if (!busy) void ask(request.message, request.thinking); });
    request.thinking.append(retry);
  }

  function stopRequest(text = '已停止等待回复，可重试本轮问题。') {
    const request = activeRequest;
    if (!request) return;
    activeRequest = null; clearTimeout(request.timer); request.controller.abort();
    showRequestError(request, text); busy = false; updateRequestControls();
  }

  function openPet() {
    panel.hidden = false;
    toggle.setAttribute("aria-expanded", "true");
    syncVisibleViewport();
    resizeInput();
    keepPanelInViewport();
    if (root.style.left) {
      const rect = root.getBoundingClientRect();
      setPetPosition(rect.left, rect.top, true);
    }
    if (window.innerWidth > 560) input.focus({preventScroll: true});
  }

  function closePet() {
    panel.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
    if (panel.contains(document.activeElement)) toggle.focus({preventScroll: true});
  }

  function syncVisibleViewport() {
    const viewport = window.visualViewport;
    panel.style.setProperty('--pet-viewport-top', `${viewport?.offsetTop || 0}px`);
    panel.style.setProperty('--pet-viewport-height', `${viewport?.height || window.innerHeight}px`);
  }

  async function ask(question, retryCard = null, fromComposer = false) {
    const message = String(question || "").trim();
    if (!message || busy) return;
    if (fromComposer) { input.value = ''; resizeInput(); }
    if (loginPage) {
      addMessage('user', message);
      addMessage('assistant', '请先使用网站账户登录。忘记密码或没有账户时，请联系管理员。登录后可使用 AI 对话，并在大航海或京东日报中分析数据。');
      return;
    }
    busy = true;
    root.querySelectorAll('.data-pet-retry').forEach(button => button.remove());
    if (!retryCard) addMessage("user", message, '', true);
    const thinking = retryCard || addMessage("assistant", reportPage ? "正在分析当前报表…" : "正在回答…", "thinking", true);
    thinking.classList.remove('error'); thinking.classList.add('thinking'); thinking.textContent = reportPage ? '正在分析当前报表…' : '正在回答…';
    const request = {message, thinking, controller: new AbortController(), timer: null};
    activeRequest = request; updateRequestControls();
    request.timer = setTimeout(() => { if (activeRequest === request) stopRequest('本次等待已超时，请重试或缩小报表日期范围。'); }, 60000);
    try {
      const capturedContext = typeof window.getPetReportContext === "function"
        ? window.getPetReportContext(false, true)
        : { mode: 'page', pagePath: location.pathname };
      // Daily metrics are recomputed on the server; send only the applied query scope.
      const context = capturedContext.mode === 'bid' || capturedContext.mode === 'page' ? capturedContext
        : Object.fromEntries(['loaded', 'reportType', 'range', 'accountId', 'excludeUnknownOptimizer']
          .filter(key => capturedContext[key] !== undefined).map(key => [key, capturedContext[key]]));
      const nextContextKey = JSON.stringify([context.mode, context.pagePath, context.reportType, context.range, context.accountId, context.excludeUnknownOptimizer, context.filters, context.source, context.view]);
      if (contextKey && contextKey !== nextContextKey) {
        history.length = 0;
        queryState = null;
        addMessage("assistant", "报表范围已变化，本轮按新的页面范围分析。");
      }
      contextKey = nextContextKey;
      const response = await fetch("/api/pet/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, context, history: history.slice(-8), queryState }),
        signal: request.controller.signal,
      });
      if (activeRequest !== request) return;
      if (response.status === 401) {
        location.replace("/login");
        throw new Error("登录已失效");
      }
      let result;
      try { result = await response.json(); }
      catch { throw new Error('服务暂时不可用，请稍后重试。'); }
      if (activeRequest !== request) return;
      if (!response.ok) throw new Error(result.error || "分析失败");
      const follow = nearBottom();
      thinking.remove();
      addAnswer(result, follow);
      if (result.queryState) queryState = result.queryState;
      mode.textContent = result.mode === "ai"
        ? `${result.provider === "deepseek" ? "DeepSeek" : "OpenAI"} · ${reportPage ? '报表分析' : '页面帮助'}`
        : result.mode === "clarification" ? "需要补充查询条件" : "规则分析 · AI 未参与本次回答";
      if (Array.isArray(result.suggestions)) {
        const quick = root.querySelector(".data-pet-quick");
        quick.replaceChildren();
        for (const question of result.suggestions.slice(0, 3)) {
          const button = document.createElement("button");
          button.type = "button";
          button.dataset.question = question;
          button.textContent = question;
          quick.appendChild(button);
        }
      }
      history.push({ role: "user", content: message }, { role: "assistant", content: result.reply });
      if (history.length > 8) history.splice(0, history.length - 8);
    } catch (error) {
      if (activeRequest === request) showRequestError(request, error instanceof Error ? error.message : "分析失败，请稍后重试。");
    } finally {
      clearTimeout(request.timer);
      if (activeRequest === request) { activeRequest = null; busy = false; updateRequestControls(); }
    }
  }

  toggle.addEventListener("pointerdown", (event) => startPetDrag(event, "toggle"));
  head.addEventListener("pointerdown", (event) => startPetDrag(event, "head"));
  window.addEventListener("pointermove", movePet);
  window.addEventListener("pointerup", finishPetDrag);
  window.addEventListener("pointercancel", finishPetDrag);
  let resizeFrame = 0;
  window.addEventListener("resize", () => {
    cancelAnimationFrame(resizeFrame);
    resizeFrame = requestAnimationFrame(() => {
      if (root.style.left) {
        const rect = root.getBoundingClientRect();
        setPetPosition(rect.left, rect.top, true);
      } else {
        updatePanelDirection();
      }
    });
  });
  toggle.addEventListener("click", (event) => {
    if (event.detail === 0) panel.hidden ? openPet() : closePet();
  });
  root.querySelector(".data-pet-close").addEventListener("click", closePet);
  root.querySelector(".data-pet-reset").addEventListener("click", () => {
    stopRequest();
    history.length = 0;
    queryState = null;
    contextKey = "";
    input.value = ''; resizeInput();
    messages.replaceChildren();
    latest.hidden = true;
    addMessage("assistant", reportPage ? "已开始新对话，将从当前报表范围分析。可以问“最近7天谁亏损最多”，也可以继续追问某位优化师。" : "已开始新对话。可以询问当前页面用途或投放指标；具体业绩分析请打开对应日报。");
    if (window.innerWidth > 560) input.focus({preventScroll: true});
  });
  stop.addEventListener('click', () => stopRequest());
  latest.addEventListener('click', () => followMessages(true));
  messages.addEventListener('scroll', () => { if (nearBottom()) latest.hidden = true; }, {passive: true});
  window.visualViewport?.addEventListener('resize', syncVisibleViewport);
  window.visualViewport?.addEventListener('scroll', syncVisibleViewport);
  input.addEventListener('input', resizeInput);
  input.addEventListener('compositionstart', () => { composerIsComposing = true; });
  input.addEventListener('compositionend', () => { composerIsComposing = false; });
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composerIsComposing && event.keyCode !== 229) {
      event.preventDefault(); void ask(input.value, null, true);
    }
  });
  panel.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.isComposing) return;
    event.preventDefault();
    if (!settings.hidden) { settings.hidden = true; settingsToggle.focus(); }
    else closePet();
  });
  settingsToggle.addEventListener("click", () => {
    providerInput.value = aiStatus.provider;
    modelInput.value = aiStatus.model;
    apiKeyInput.value = "";
    settings.hidden = !settings.hidden;
    if (!settings.hidden) apiKeyInput.focus();
  });
  providerInput.addEventListener("change", () => {
    modelInput.value = providerInput.value === "openai" ? "gpt-5.6-terra" : "deepseek-v4-flash";
  });
  root.querySelector(".data-pet-settings-save").addEventListener("click", async (event) => {
    const apiKey = apiKeyInput.value.trim();
    const button = event.currentTarget;
    button.disabled = true;
    try {
      const response = await fetch("/api/pet/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          provider: providerInput.value === "openai" ? "openai" : "deepseek",
          apiKey,
          model: modelInput.value.trim(),
        }),
        signal: AbortSignal.timeout(10000),
      });
      if (response.status === 401) {
        location.replace("/login");
        throw new Error("登录已失效");
      }
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || "保存 AI 配置失败");
      aiStatus = {
        provider: result.provider === "openai" ? "openai" : "deepseek",
        model: result.model || "",
        configured: Boolean(result.configured),
        canManage: Boolean(result.canManage),
      };
      apiKeyInput.value = "";
      apiKeyInput.placeholder = "已配置，留空保持不变";
      settings.hidden = true;
      updateAiMode();
      addMessage("assistant", "AI 配置已保存到服务器，其他已登录电脑现在也可以直接使用。");
    } catch (error) {
      addMessage("assistant", error instanceof Error ? error.message : "保存 AI 配置失败。");
    } finally {
      button.disabled = false;
    }
  });
  root.querySelector(".data-pet-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!composerIsComposing) void ask(input.value, null, true);
  });
  root.querySelector(".data-pet-quick").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-question]");
    if (button) ask(button.dataset.question);
  });

  addMessage("assistant", reportPage ? "嗨，我是初音数据助手！可以问我当前报表的消耗、利润、ROI、有效订单、优化师排名或异常预警。" : loginPage ? "嗨，我是初音数据助手！登录后可在全站与我对话。" : "嗨，我是初音数据助手！可以询问当前页面用途、投放指标和分析方法。具体业绩分析请打开大航海或京东日报。");
  mode.textContent = "正在读取 AI 配置…";
  if (loginPage) { mode.textContent = '登录引导 · 登录后启用 AI'; rulesToggle.hidden = true; }
  else loadAiConfig().catch(() => {
    mode.textContent = "AI 配置暂未读取 · 可继续提问";
  });
  restorePetPosition();
})();
