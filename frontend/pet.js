(() => {
  "use strict";
  if (document.querySelector('.data-pet')) return;
  const loginPage = /^\/login(?:\.html)?\/?$/.test(location.pathname);
  const reportPage = typeof window.getPetReportContext === 'function';

  const history = [];
  let queryState = null;
  let contextKey = "";
  let busy = false;
  const legacyAiConfigStorageKey = "data-pet-ai-config-v1";
  const petPositionStorageKey = "data-pet-position-v1";
  let aiStatus = {
    provider: "deepseek",
    model: "deepseek-v4-flash",
    configured: false,
    canManage: false,
  };

  const escapeHtml = (value) => String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");

  function createPet() {
    const root = document.createElement("aside");
    root.className = "data-pet";
    root.setAttribute("aria-label", "数据分析宠物");
    root.innerHTML = `
      <section class="data-pet-panel" hidden>
        <header class="data-pet-head">
          <div class="data-pet-identity">
            <img class="data-pet-avatar" src="/assets/miku-pet.png" alt="" />
            <div><div class="data-pet-name">初音未来 · 数据助手</div><div class="data-pet-mode">报表对话与数据分析</div></div>
          </div>
          <div class="data-pet-head-actions">
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
        <div class="data-pet-messages" aria-live="polite"></div>
        <div class="data-pet-quick">
          <button type="button" data-question="帮我总结当前报表">总结报表</button>
          <button type="button" data-question="诊断当前报表的亏损并给出优化建议">诊断亏损</button>
          <button type="button" data-question="按利润给优化师排名">利润排名</button>
          <button type="button" data-question="对比上期，哪些指标变化最大？">对比上期</button>
        </div>
        <form class="data-pet-form">
          <input class="data-pet-input" maxlength="500" autocomplete="off" placeholder="问问当前报表…" aria-label="输入问题" />
          <button class="data-pet-send" type="submit">发送</button>
        </form>
      </section>
      <button class="data-pet-toggle" type="button" aria-label="打开初音数据助手" aria-expanded="false">
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
  const providerInput = root.querySelector(".data-pet-provider");
  const modelInput = root.querySelector(".data-pet-model");
  const apiKeyInput = root.querySelector(".data-pet-api-key");
  const head = root.querySelector(".data-pet-head");
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
    if (event.button !== 0) return;
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
    const response = await fetch("/api/pet/config", { cache: "no-store" });
    if (response.status === 401) {
      location.replace("/login");
      throw new Error("登录已失效");
    }
    const result = await response.json();
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

  function addMessage(role, text, className = "") {
    const item = document.createElement("div");
    item.className = `data-pet-message ${role}${className ? ` ${className}` : ""}`;
    item.innerHTML = escapeHtml(text);
    messages.appendChild(item);
    messages.scrollTop = messages.scrollHeight;
    return item;
  }

  function openPet() {
    panel.hidden = false;
    toggle.setAttribute("aria-expanded", "true");
    keepPanelInViewport();
    if (root.style.left) {
      const rect = root.getBoundingClientRect();
      setPetPosition(rect.left, rect.top, true);
    }
    input.focus();
  }

  function closePet() {
    panel.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
  }

  async function ask(question) {
    const message = String(question || "").trim();
    if (!message || busy) return;
    if (loginPage) {
      addMessage('user', message);
      addMessage('assistant', '请先使用网站账户登录。忘记密码或没有账户时，请联系管理员。登录后可使用 AI 对话，并在大航海或京东日报中分析数据。');
      input.value = '';
      return;
    }
    busy = true;
    input.value = "";
    send.disabled = true;
    root.querySelector(".data-pet-reset").disabled = true;
    addMessage("user", message);
    const thinking = addMessage("assistant", "正在查看当前报表…", "thinking");
    try {
      const context = typeof window.getPetReportContext === "function"
        ? window.getPetReportContext()
        : { mode: 'page', pagePath: location.pathname };
      const nextContextKey = JSON.stringify([context.mode, context.pagePath, context.reportType, context.range, context.accountId, context.excludeUnknownOptimizer]);
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
      });
      if (response.status === 401) {
        location.replace("/login");
        throw new Error("登录已失效");
      }
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || "分析失败");
      thinking.remove();
      if (result.scope) addMessage("assistant", `分析范围：${result.scope}`);
      addMessage("assistant", result.reply);
      if (result.notice) addMessage("assistant", result.notice);
      if (result.queryState) queryState = result.queryState;
      mode.textContent = result.mode === "ai"
        ? `${result.provider === "deepseek" ? "DeepSeek" : "OpenAI"} 对话 · ${reportPage ? '当前报表数据' : '页面帮助'}`
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
      thinking.textContent = error instanceof Error ? error.message : "分析失败，请稍后重试。";
    } finally {
      busy = false;
      send.disabled = false;
      root.querySelector(".data-pet-reset").disabled = false;
      input.focus();
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
    if (busy) return;
    history.length = 0;
    queryState = null;
    contextKey = "";
    messages.replaceChildren();
    addMessage("assistant", reportPage ? "已开始新对话，将从当前报表范围分析。可以问“最近7天谁亏损最多”，也可以继续追问某位优化师。" : "已开始新对话。可以询问当前页面用途或投放指标；具体业绩分析请打开对应日报。");
    input.focus();
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
    ask(input.value);
  });
  root.querySelector(".data-pet-quick").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-question]");
    if (button) ask(button.dataset.question);
  });

  addMessage("assistant", reportPage ? "嗨，我是初音数据助手！可以问我当前报表的消耗、利润、ROI、有效订单、优化师排名或异常预警。" : loginPage ? "嗨，我是初音数据助手！登录后可在全站与我对话。" : "嗨，我是初音数据助手！可以询问当前页面用途、投放指标和分析方法。具体业绩分析请打开大航海或京东日报。");
  mode.textContent = "正在读取 AI 配置…";
  if (loginPage) mode.textContent = '登录引导 · 登录后启用 AI';
  else loadAiConfig().catch(() => {
    mode.textContent = "本地数据分析";
  });
  restorePetPosition();
})();
