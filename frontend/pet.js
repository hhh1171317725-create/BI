(() => {
  "use strict";

  const history = [];
  let busy = false;
  const aiConfigStorageKey = "data-pet-ai-config-v1";
  const petPositionStorageKey = "data-pet-position-v1";
  const petRoamStorageKey = "data-pet-roam-enabled-v1";
  const petMotionSources = {
    idle: "/assets/ai-assistant-idle.webp",
    greet: "/assets/ai-assistant-greet.webp",
    thinking: "/assets/ai-assistant-thinking.webp",
    success: "/assets/ai-assistant-success.webp",
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
            <picture class="data-pet-avatar-frame">
              <source data-pet-motion media="(prefers-reduced-motion: no-preference)" srcset="/assets/ai-assistant-idle.webp" type="image/webp" />
              <img class="data-pet-avatar" src="/assets/ai-assistant-v2.png" alt="" />
            </picture>
            <div><div class="data-pet-name">AI 数据助手</div><div class="data-pet-mode">报表对话与数据分析</div></div>
          </div>
          <div class="data-pet-head-actions">
            <button class="data-pet-roam-toggle" type="button" aria-label="暂停自动漫游" title="暂停自动漫游">Ⅱ</button>
            <button class="data-pet-settings-toggle" type="button" aria-label="AI 设置" title="AI 设置">⚙</button>
            <button class="data-pet-close" type="button" aria-label="关闭对话">×</button>
          </div>
        </header>
        <div class="data-pet-settings" hidden>
          <select class="data-pet-provider" aria-label="AI 提供商">
            <option value="deepseek">DeepSeek</option>
            <option value="openai">OpenAI</option>
          </select>
          <input class="data-pet-api-key" type="password" autocomplete="off" placeholder="粘贴 API Key" aria-label="AI API Key" />
          <button class="data-pet-settings-save" type="button">保存</button>
          <div class="data-pet-settings-note">Key 仅保存在当前浏览器，通过本站后端转发，不写入服务器文件。</div>
        </div>
        <div class="data-pet-messages" aria-live="polite"></div>
        <div class="data-pet-quick">
          <button type="button" data-question="帮我总结当前报表">总结报表</button>
          <button type="button" data-question="分析消耗和利润">消耗利润</button>
          <button type="button" data-question="哪个优化师消耗最高？">优化师排名</button>
          <button type="button" data-question="分析当前异常预警">异常预警</button>
        </div>
        <form class="data-pet-form">
          <input class="data-pet-input" maxlength="500" autocomplete="off" placeholder="问问当前报表…" aria-label="输入问题" />
          <button class="data-pet-send" type="submit">发送</button>
        </form>
      </section>
      <button class="data-pet-toggle" type="button" aria-label="打开 AI 数据助手" aria-expanded="false">
        <picture class="data-pet-character">
          <source data-pet-motion media="(prefers-reduced-motion: no-preference)" srcset="/assets/ai-assistant-idle.webp" type="image/webp" />
          <img src="/assets/ai-assistant-v2.png" alt="" draggable="false" />
        </picture>
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
  const providerInput = root.querySelector(".data-pet-provider");
  const apiKeyInput = root.querySelector(".data-pet-api-key");
  const head = root.querySelector(".data-pet-head");
  const roamToggle = root.querySelector(".data-pet-roam-toggle");
  let dragState = null;
  let motionTimer = 0;
  let roamTimer = 0;
  let roamMoveTimer = 0;
  let petHovered = false;
  let roamEnabled = true;

  function setPetMotion(name, resetAfter = 0) {
    const source = petMotionSources[name] || petMotionSources.idle;
    window.clearTimeout(motionTimer);
    root.querySelectorAll("source[data-pet-motion]").forEach((item) => {
      if (item.getAttribute("srcset") !== source) item.setAttribute("srcset", source);
    });
    motionTimer = resetAfter > 0
      ? window.setTimeout(() => {
        if (!busy) setPetMotion("idle");
      }, resetAfter)
      : 0;
  }

  function savedRoamEnabled() {
    try {
      return localStorage.getItem(petRoamStorageKey) !== "false";
    } catch {
      return true;
    }
  }

  function updateRoamToggle() {
    roamToggle.textContent = roamEnabled ? "Ⅱ" : "▶";
    roamToggle.setAttribute("aria-label", roamEnabled ? "暂停自动漫游" : "开启自动漫游");
    roamToggle.title = roamEnabled ? "暂停自动漫游" : "开启自动漫游";
  }

  function canRoam() {
    return roamEnabled
      && window.innerWidth > 720
      && document.visibilityState === "visible"
      && panel.hidden
      && !busy
      && !dragState
      && !petHovered;
  }

  function stopPetRoam(keepCurrentPosition = true) {
    window.clearTimeout(roamTimer);
    window.clearTimeout(roamMoveTimer);
    roamTimer = 0;
    roamMoveTimer = 0;
    if (!root.classList.contains("data-pet-roaming")) return;
    const rect = root.getBoundingClientRect();
    root.classList.remove("data-pet-roaming");
    if (keepCurrentPosition) setPetPosition(rect.left, rect.top);
  }

  function schedulePetRoam(delay = 4200 + Math.random() * 4200) {
    window.clearTimeout(roamTimer);
    if (!canRoam()) return;
    roamTimer = window.setTimeout(startPetRoam, delay);
  }

  function startPetRoam() {
    if (!canRoam()) return;
    const initialRect = root.getBoundingClientRect();
    if (!root.style.left) setPetPosition(initialRect.left, initialRect.top);
    const rect = root.getBoundingClientRect();
    const minLeft = 14;
    const maxLeft = Math.max(minLeft, window.innerWidth - rect.width - 14);
    const minTop = Math.min(
      Math.max(86, window.innerHeight * .56),
      Math.max(14, window.innerHeight - rect.height - 14),
    );
    const maxTop = Math.max(minTop, window.innerHeight - rect.height - 14);
    const direction = Math.random() < .5 ? -1 : 1;
    const distance = 130 + Math.random() * 260;
    let targetLeft = Math.min(maxLeft, Math.max(minLeft, rect.left + direction * distance));
    if (Math.abs(targetLeft - rect.left) < 70) {
      targetLeft = direction < 0 ? Math.min(maxLeft, rect.left + distance) : Math.max(minLeft, rect.left - distance);
    }
    const targetTop = Math.min(maxTop, Math.max(minTop, rect.top + (Math.random() - .5) * 90));
    const duration = 2200 + Math.random() * 1800;
    root.classList.toggle("data-pet-facing-left", targetLeft < rect.left);
    root.style.setProperty("--data-pet-roam-duration", `${Math.round(duration)}ms`);
    root.classList.add("data-pet-roaming");
    setPetMotion("greet");
    requestAnimationFrame(() => setPetPosition(targetLeft, targetTop));
    roamMoveTimer = window.setTimeout(() => {
      root.classList.remove("data-pet-roaming");
      setPetPosition(targetLeft, targetTop);
      setPetMotion("idle");
      schedulePetRoam();
    }, duration + 80);
  }

  function setRoamEnabled(enabled) {
    roamEnabled = enabled;
    updateRoamToggle();
    try {
      localStorage.setItem(petRoamStorageKey, String(enabled));
    } catch {
      // 自动漫游不依赖浏览器是否允许保存偏好。
    }
    if (enabled) {
      schedulePetRoam(800);
    } else {
      stopPetRoam();
      if (!busy) setPetMotion("idle");
    }
  }

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
    stopPetRoam();
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
      schedulePetRoam(2600);
    } else if (source === "toggle" && event.type === "pointerup") {
      panel.hidden ? openPet() : closePet();
    }
  }

  function savedAiConfig() {
    try {
      const value = JSON.parse(localStorage.getItem(aiConfigStorageKey) || "{}");
      return value && typeof value === "object" ? value : {};
    } catch {
      return {};
    }
  }

  function updateAiMode() {
    const config = savedAiConfig();
    mode.textContent = config.apiKey
      ? `${config.provider === "openai" ? "OpenAI" : "DeepSeek"} 已配置 · 当前浏览器`
      : "点击 ⚙ 配置 AI · 本地分析";
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
    stopPetRoam();
    panel.hidden = false;
    toggle.setAttribute("aria-expanded", "true");
    if (!busy) setPetMotion("greet", 2600);
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
    if (!busy) setPetMotion("idle");
    schedulePetRoam(3200);
  }

  async function ask(question) {
    const message = String(question || "").trim();
    if (!message || busy) return;
    busy = true;
    stopPetRoam();
    input.value = "";
    send.disabled = true;
    addMessage("user", message);
    const thinking = addMessage("assistant", "正在查看当前报表…", "thinking");
    setPetMotion("thinking");
    let succeeded = false;
    try {
      const context = typeof window.getPetReportContext === "function"
        ? window.getPetReportContext()
        : { reportType: "未知报表", summary: {} };
      const response = await fetch("/api/pet/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, context, history: history.slice(-8), aiConfig: savedAiConfig() }),
      });
      if (response.status === 401) {
        location.replace("/login");
        throw new Error("登录已失效");
      }
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || "分析失败");
      thinking.remove();
      addMessage("assistant", result.reply);
      mode.textContent = result.mode === "ai"
        ? `${result.provider === "deepseek" ? "DeepSeek" : "OpenAI"} 对话 · 当前报表数据`
        : "本地数据分析";
      history.push({ role: "user", content: message }, { role: "assistant", content: result.reply });
      succeeded = true;
      setPetMotion("success", 3600);
    } catch (error) {
      thinking.textContent = error instanceof Error ? error.message : "分析失败，请稍后重试。";
    } finally {
      busy = false;
      if (!succeeded) setPetMotion("idle");
      send.disabled = false;
      input.focus();
      schedulePetRoam(succeeded ? 5200 : 3000);
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
  toggle.addEventListener("pointerenter", () => {
    petHovered = true;
    stopPetRoam();
    if (!busy && panel.hidden) setPetMotion("greet", 2600);
  });
  toggle.addEventListener("pointerleave", () => {
    petHovered = false;
    schedulePetRoam(2800);
  });
  roamToggle.addEventListener("click", () => setRoamEnabled(!roamEnabled));
  root.querySelector(".data-pet-close").addEventListener("click", closePet);
  root.querySelector(".data-pet-settings-toggle").addEventListener("click", () => {
    const config = savedAiConfig();
    providerInput.value = config.provider === "openai" ? "openai" : "deepseek";
    apiKeyInput.value = config.apiKey || "";
    settings.hidden = !settings.hidden;
    if (!settings.hidden) apiKeyInput.focus();
  });
  root.querySelector(".data-pet-settings-save").addEventListener("click", () => {
    const apiKey = apiKeyInput.value.trim();
    try {
      if (apiKey) {
        localStorage.setItem(aiConfigStorageKey, JSON.stringify({
          provider: providerInput.value === "openai" ? "openai" : "deepseek",
          apiKey,
        }));
      } else {
        localStorage.removeItem(aiConfigStorageKey);
      }
      settings.hidden = true;
      updateAiMode();
      addMessage("assistant", apiKey ? "AI 设置已保存，可以开始对话了。" : "AI 设置已清除，已切换为本地分析。");
    } catch {
      addMessage("assistant", "浏览器未允许保存设置，请检查本地存储权限。");
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

  addMessage("assistant", "嗨，我是你的 AI 数据助手！可以问我当前报表的消耗、利润、ROI、有效订单、优化师排名或异常预警。");
  roamEnabled = savedRoamEnabled();
  updateRoamToggle();
  updateAiMode();
  restorePetPosition();
  schedulePetRoam(2600);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      schedulePetRoam(1800);
    } else {
      stopPetRoam();
    }
  });
})();
