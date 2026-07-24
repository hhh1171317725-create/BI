(() => {
  "use strict";

  const history = [];
  let busy = false;

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
          <button class="data-pet-close" type="button" aria-label="关闭对话">×</button>
        </header>
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
        body: JSON.stringify({ message, context, history: history.slice(-8) }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || "分析失败");
      thinking.remove();
      addMessage("assistant", result.reply);
      mode.textContent = result.mode === "ai" ? "AI 对话 · 当前报表数据" : "本地数据分析";
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
  root.querySelector(".data-pet-form").addEventListener("submit", (event) => {
    event.preventDefault();
    ask(input.value);
  });
  root.querySelector(".data-pet-quick").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-question]");
    if (button) ask(button.dataset.question);
  });

  addMessage("assistant", "嗨，我是数数鲸！可以问我当前报表的消耗、利润、ROI、有效订单、优化师排名或异常预警。");
})();
