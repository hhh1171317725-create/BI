(() => {
  "use strict";

  const history = [];
  let busy = false;
  const aiConfigStorageKey = "data-pet-ai-config-v1";

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
          <div><div class="data-pet-name">🐳 数数鲸</div><div class="data-pet-mode">报表对话与数据分析</div></div>
          <div class="data-pet-head-actions">
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
      <button class="data-pet-toggle" type="button" aria-label="打开数据分析宠物" aria-expanded="false">🐳<span class="data-pet-dot"></span></button>
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
    panel.hidden = false;
    toggle.setAttribute("aria-expanded", "true");
    input.focus();
  }

  function closePet() {
    panel.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
  }

  async function ask(question) {
    const message = String(question || "").trim();
    if (!message || busy) return;
    busy = true;
    input.value = "";
    send.disabled = true;
    addMessage("user", message);
    const thinking = addMessage("assistant", "正在查看当前报表…", "thinking");
    try {
      const context = typeof window.getPetReportContext === "function"
        ? window.getPetReportContext()
        : { reportType: "未知报表", summary: {} };
      const response = await fetch("/api/pet/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, context, history: history.slice(-8), aiConfig: savedAiConfig() }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || "分析失败");
      thinking.remove();
      addMessage("assistant", result.reply);
      mode.textContent = result.mode === "ai"
        ? `${result.provider === "deepseek" ? "DeepSeek" : "OpenAI"} 对话 · 当前报表数据`
        : "本地数据分析";
      history.push({ role: "user", content: message }, { role: "assistant", content: result.reply });
    } catch (error) {
      thinking.textContent = error instanceof Error ? error.message : "分析失败，请稍后重试。";
    } finally {
      busy = false;
      send.disabled = false;
      input.focus();
    }
  }

  toggle.addEventListener("click", () => panel.hidden ? openPet() : closePet());
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

  addMessage("assistant", "嗨，我是数数鲸！可以问我当前报表的消耗、利润、ROI、有效订单、优化师排名或异常预警。");
  updateAiMode();
})();
