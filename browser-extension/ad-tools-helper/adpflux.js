(() => {
  if (window.__adpfluxKeywordHelperLoaded) return;
  window.__adpfluxKeywordHelperLoaded = true;

  const state = { entries: [], selected: null, accountInitialization: null, fillRun: 0 };
  const launchParams = new URLSearchParams(location.search);
  const launchEntryId = launchParams.get("bi_entry_id") || "";
  const launchKeyword = launchParams.get("bi_keyword") || "";
  const autoFillRequested = launchParams.get("bi_autofill") === "1";
  const adpfluxDefaults = {
    campaignQuantity: "1",
    dailyBudget: "20",
    dataConnection: "next-game-0803",
    optimizationEvent: "点击按钮"
  };
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const normalize = (value) => String(value || "").replace(/\s+/g, " ").trim();
  const splitValues = (value) => String(value || "").split(/[\n,，、]+/).map(normalize).filter(Boolean);
  const visible = (element) => Boolean(element && element.getClientRects().length && getComputedStyle(element).visibility !== "hidden");

  function datedCampaignName(keyword, now = new Date()) {
    const month = String(now.getMonth() + 1).padStart(2, "0");
    const day = String(now.getDate()).padStart(2, "0");
    return `${month}${day}-${normalize(keyword)}`;
  }

  function campaignQuantity(entry) {
    const value = Number(entry?.campaignQuantity);
    return Number.isInteger(value) && value > 0 ? String(value) : adpfluxDefaults.campaignQuantity;
  }

  function dailyBudget(entry) {
    const raw = String(entry?.dailyBudget ?? "").trim();
    const value = Number(raw);
    return raw && Number.isFinite(value) && value > 0 ? raw : adpfluxDefaults.dailyBudget;
  }

  function send(message) {
    return new Promise((resolve, reject) => {
      chrome.runtime.sendMessage(message, (response) => {
        const error = chrome.runtime.lastError?.message || response?.error;
        if (error) reject(new Error(error));
        else resolve(response || {});
      });
    });
  }

  function nativeValue(input, value, blur = true) {
    const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, "value")?.set?.call(input, value);
    input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    if (blur) input.blur();
  }

  function formItem(label) {
    const labels = [...document.querySelectorAll(".arco-form-item-label, .ant-form-item-label, [class*='form-label'], label")];
    const labelNode = labels.find((node) => normalize(node.textContent).replace(/[：:]/g, "") === label)
      || labels.find((node) => normalize(node.textContent).includes(label));
    if (!labelNode) return null;
    const arcoItem = labelNode.closest(".arco-form-item");
    if (arcoItem) return arcoItem;
    const antItem = labelNode.closest(".ant-form-item");
    if (antItem) return antItem;
    let parent = labelNode.parentElement;
    while (parent && parent !== document.body) {
      const hasControl = parent.querySelector("input, textarea, select, [role='combobox'], [class*='select-view']");
      if (hasControl && normalize(parent.textContent).includes(label)) return parent;
      parent = parent.parentElement;
    }
    return null;
  }

  function inputByLabel(label) {
    return formItem(label)?.querySelector("input:not([type='hidden']), textarea") || null;
  }

  function fillPlaceholder(placeholder, value) {
    const input = [...document.querySelectorAll("input,textarea")]
      .find((item) => item.placeholder === placeholder || item.placeholder?.startsWith(placeholder));
    if (!input) throw new Error(`未找到“${placeholder}”输入框`);
    nativeValue(input, value);
  }

  function fillInputById(inputId, value, label) {
    const input = document.getElementById(inputId);
    if (!input) throw new Error(`未找到“${label}”输入框`);
    nativeValue(input, value);
  }

  function allPopupOptions() {
    return [...document.querySelectorAll("[role='option'], .arco-select-option, .ant-select-item-option, [class*='select-option']")]
      .filter((node) => !node.closest("#adpflux-keyword-helper") && node.getAttribute("aria-disabled") !== "true" && !node.classList.contains("arco-select-option-disabled"));
  }

  function popupOptions() {
    return allPopupOptions().filter(visible);
  }

  function clickLikeUser(element) {
    const Pointer = window.PointerEvent || MouseEvent;
    element.dispatchEvent(new Pointer("pointerdown", { bubbles: true, cancelable: true, view: window, pointerType: "mouse" }));
    element.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window }));
    element.dispatchEvent(new Pointer("pointerup", { bubbles: true, cancelable: true, view: window, pointerType: "mouse" }));
    element.dispatchEvent(new MouseEvent("mouseup", { bubbles: true, cancelable: true, view: window }));
    element.click();
  }

  function pressKey(element, key) {
    for (const type of ["keydown", "keypress", "keyup"]) {
      element.dispatchEvent(new KeyboardEvent(type, { key, code: key, bubbles: true, cancelable: true }));
    }
  }

  function optionFor(value) {
    const target = normalize(value).replace(/\s+/g, "").toLowerCase();
    return popupOptions().find((node) => normalize(node.textContent).replace(/\s+/g, "").toLowerCase().includes(target));
  }

  function openPopup() {
    const popups = [...document.querySelectorAll(".arco-select-popup, .ant-select-dropdown, [class*='select-popup'], [role='listbox']")]
      .filter((node) => visible(node) && !node.closest("#adpflux-keyword-helper"));
    return popups.at(-1) || popupOptions()[0]?.closest("[role='listbox'], .arco-select-popup, [class*='select-popup']") || null;
  }

  async function findByScrolling(value) {
    const target = normalize(value).replace(/\s+/g, "").toLowerCase();
    const existing = allPopupOptions().find((node) => normalize(node.textContent).replace(/\s+/g, "").toLowerCase().includes(target));
    if (existing) {
      existing.scrollIntoView({ block: "nearest" });
      await sleep(180);
      if (visible(existing)) return existing;
    }
    const popup = openPopup();
    if (!popup) return null;
    const scrollables = [popup, ...popup.querySelectorAll("div")]
      .filter((node) => node.scrollHeight > node.clientHeight + 8)
      .sort((a, b) => b.scrollHeight - a.scrollHeight);
    for (const scroller of scrollables.slice(0, 3)) {
      const step = Math.max(80, Math.floor(scroller.clientHeight * 0.75));
      for (let top = 0; top <= scroller.scrollHeight; top += step) {
        scroller.scrollTop = top;
        scroller.dispatchEvent(new Event("scroll", { bubbles: true }));
        await sleep(90);
        const option = optionFor(value);
        if (option) return option;
      }
    }
    return null;
  }

  async function chooseOption(label, value) {
    const item = formItem(label);
    if (!item) throw new Error(`未找到“${label}”字段`);
    if (normalize(item.textContent).replace(/\s+/g, "").includes(String(value).replace(/\s+/g, ""))) return;
    let input = item.querySelector("input[role='combobox'], input[type='search'], input[autocomplete='off']");
    const trigger = item.querySelector(".arco-select-view, .ant-select-selector, .ant-select, [class*='select-view'], [role='combobox']") || input;
    if (!trigger) throw new Error(`未找到“${label}”下拉按钮`);
    if (!popupOptions().length) clickLikeUser(trigger);
    await sleep(400);
    let option = optionFor(value);
    if (option) {
      clickLikeUser(option);
      await sleep(350);
      return;
    }
    const popup = openPopup();
    input = input || [...(popup?.querySelectorAll("input") || [])].find(visible) || null;
    if (input) {
      input.focus();
      nativeValue(input, "", false);
      nativeValue(input, value, false);
      input.dispatchEvent(new Event("keyup", { bubbles: true }));
      await sleep(800);
      option = optionFor(value);
    }
    option = option || await findByScrolling(value);
    if (option) {
      clickLikeUser(option);
      await sleep(500);
      return;
    } else if (input) {
      pressKey(input, "ArrowDown");
      pressKey(input, "Enter");
    } else {
      throw new Error(`下拉列表中没有找到 ${value}`);
    }
    await sleep(500);
    const selectedText = normalize(item.textContent).replace(/\s+/g, "");
    if (!selectedText.includes(String(value).replace(/\s+/g, ""))) {
      throw new Error(`没有找到或未能选中 ${value}`);
    }
  }

  async function chooseMany(label, values) {
    const failures = [];
    try {
      for (const value of values) {
        try {
          await chooseOption(label, value);
        } catch (error) {
          failures.push(error.message);
        }
      }
    } finally {
      pressKey(document.activeElement || document.body, "Escape");
      await sleep(250);
    }
    if (failures.length) throw new Error(failures.join("；"));
  }

  async function waitFor(getter, timeout = 3500, interval = 100) {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      const result = getter();
      if (result) return result;
      await sleep(interval);
    }
    return null;
  }

  function antSelected(select, value) {
    const target = normalize(value).replace(/\s+/g, "");
    return [...select.querySelectorAll(".ant-select-selection-item")]
      .some((item) => normalize(item.textContent).replace(/\s+/g, "").includes(target));
  }

  function antOption(value) {
    const target = normalize(value).replace(/\s+/g, "").toLowerCase();
    return [...document.querySelectorAll(".ant-select-dropdown .ant-select-item-option")]
      .filter(visible)
      .find((option) => normalize(option.textContent).replace(/\s+/g, "").toLowerCase().includes(target));
  }

  async function chooseAntManyById(inputId, values) {
    const input = document.getElementById(inputId);
    const select = input?.closest(".ant-select");
    const selector = select?.querySelector(".ant-select-selector");
    if (!input || !select || !selector) throw new Error("未找到广告账户下拉框，请刷新 Adpflux 页面后重试");

    const failures = [];
    for (const value of values) {
      if (antSelected(select, value)) continue;

      if (input.getAttribute("aria-expanded") !== "true") {
        clickLikeUser(selector);
        await waitFor(() => input.getAttribute("aria-expanded") === "true", 1800);
      }

      input.focus();
      nativeValue(input, "", false);
      nativeValue(input, value, false);
      input.dispatchEvent(new KeyboardEvent("keyup", { key: value.slice(-1), bubbles: true }));

      const option = await waitFor(() => antOption(value), 4000);
      if (!option) {
        failures.push(`${value}（当前管理员账号下没有匹配项）`);
        nativeValue(input, "", false);
        continue;
      }

      clickLikeUser(option);
      const selected = await waitFor(() => antSelected(select, value), 2200);
      if (!selected) failures.push(`${value}（找到选项但未能选中）`);
      await sleep(200);
    }

    await sleep(350);
    const closeResult = await send({ type: "closeAdpfluxAccountDropdown", values });
    state.accountInitialization = closeResult;
    if (closeResult?.error) throw new Error(closeResult.error);
    if (!closeResult?.closed) {
      throw new Error("账户已选中，但页面未触发账户配置加载，请关闭广告账户下拉框后重试");
    }
    nativeValue(input, "", false);
    await sleep(500);
    if (failures.length) throw new Error(failures.join("；"));
  }

  async function chooseAntOptionById(inputId, value, label) {
    const input = document.getElementById(inputId);
    const select = input?.closest(".ant-select");
    const selector = select?.querySelector(".ant-select-selector");
    if (!input || !select || !selector) throw new Error(`未找到“${label}”下拉框`);
    const target = normalize(value).replace(/\s+/g, "").toLowerCase();
    const isSelected = () => normalize(select.textContent).replace(/\s+/g, "").toLowerCase().includes(target);
    if (isSelected()) return;

    let pageResult = null;
    try {
      pageResult = await send({ type: "selectAdpfluxOption", inputId, value });
      if (pageResult?.error) throw new Error(pageResult.error);
      if (pageResult?.selected || isSelected()) return;
    } catch {}

    if (input.getAttribute("aria-expanded") !== "true") {
      clickLikeUser(selector);
      await waitFor(() => input.getAttribute("aria-expanded") === "true", 2000);
    }

    input.focus();
    nativeValue(input, "", false);
    nativeValue(input, value, false);
    input.dispatchEvent(new KeyboardEvent("keyup", { key: value.slice(-1), bubbles: true }));
    const option = await waitFor(() => antOption(value), 10000, 120);
    if (!option) {
      const available = [...document.querySelectorAll(".ant-select-dropdown .ant-select-item-option")]
        .filter(visible)
        .map((item) => normalize(item.textContent))
        .filter(Boolean);
      const init = state.accountInitialization;
      const request = init?.pixelRequest;
      const requestDetail = label === "数据连接" && init
        ? request
          ? `，配置接口：HTTP ${request.status || 0} / code ${request.code ?? "无"} / 返回 ${request.pixelCount || 0} 个${request.message ? ` / ${request.message}` : ""}`
          : "，配置接口：未捕获到请求"
        : "";
      const initDetail = label === "数据连接" && init
        ? `（账户写入 ${init.commitHandlerCount || 0} 次，匹配 ${init.matchedOptionCount || 0} 个账户，初始化回调 ${init.handlerCount || 0} 个${requestDetail}）`
        : "";
      const pageDetail = pageResult
        ? `（主环境：${pageResult.method || "未知"}，已打开：${pageResult.opened ? "是" : "否"}，选项数：${pageResult.optionCount || 0}）`
        : "";
      const detail = available.length ? `，当前可选项：${available.slice(0, 6).join("、")}` : "，当前下拉列表为空";
      throw new Error(`下拉列表中没有找到 ${value}${detail}${initDetail}${pageDetail}`);
    }
    clickLikeUser(option);
    if (!await waitFor(isSelected, 2500, 100)) throw new Error(`找到 ${value}，但未能选中`);
    pressKey(input, "Escape");
    await sleep(350);
  }

  async function chooseAntOptionByLabel(label, value) {
    const item = await waitFor(() => formItem(label), 15000, 150);
    if (!item) throw new Error(`未找到“${label}”字段`);
    const input = item.querySelector("input[role='combobox'], input[type='search'], input[autocomplete='off'], input");
    if (!input) throw new Error(`“${label}”下拉框尚未加载`);
    if (input.id) return chooseAntOptionById(input.id, value, label);
    return chooseOption(label, value);
  }

  async function chooseAntOptionWhenReady(label, value, attempts = 3) {
    let lastError;
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
      try {
        return await chooseAntOptionByLabel(label, value);
      } catch (error) {
        lastError = error;
        pressKey(document.activeElement || document.body, "Escape");
        if (attempt < attempts) {
          setStatus(`${label}数据仍在加载，正在进行第 ${attempt + 1} 次尝试...`);
          await sleep(2500);
        }
      }
    }
    throw lastError;
  }

  function antTreeSelected(select, value) {
    const target = normalize(value).replace(/\s+/g, "");
    const selectedTag = [...select.querySelectorAll(".ant-select-selection-item")]
      .some((item) => normalize(item.textContent).replace(/\s+/g, "").includes(target));
    if (selectedTag) return true;
    const node = antTreeNode(value);
    const checkbox = node?.querySelector(".ant-cascader-checkbox, .ant-select-tree-checkbox");
    return node?.getAttribute("aria-selected") === "true"
      || node?.getAttribute("aria-checked") === "true"
      || checkbox?.getAttribute("aria-checked") === "true"
      || checkbox?.classList.contains("ant-cascader-checkbox-checked")
      || checkbox?.classList.contains("ant-select-tree-checkbox-checked")
      || false;
  }

  function antTreeNode(value) {
    const target = normalize(value).replace(/\s+/g, "").toLowerCase();
    return [...document.querySelectorAll(".ant-cascader-menu-item, .ant-select-tree-treenode")]
      .filter(visible)
      .find((node) => {
        const title = node.querySelector(".ant-cascader-menu-item-content, .ant-select-tree-title");
        return normalize(title?.textContent).replace(/\s+/g, "").toLowerCase() === target;
      });
  }

  async function chooseAntTreeManyById(inputId, values) {
    const input = document.getElementById(inputId);
    const select = input?.closest(".ant-select");
    const selector = select?.querySelector(".ant-select-selector");
    if (!input || !select || !selector) throw new Error("未找到地域选择器，请先确认广告账户已选中");

    const failures = [];
    for (const [index, value] of values.entries()) {
      if (antTreeSelected(select, value)) continue;

      setStatus(`正在勾选地域：${value}（${index + 1}/${values.length}）...`);

      if (input.getAttribute("aria-expanded") !== "true") {
        clickLikeUser(selector);
        await waitFor(() => input.getAttribute("aria-expanded") === "true", 2200);
      }

      input.focus();
      nativeValue(input, "", false);
      nativeValue(input, value, false);
      input.dispatchEvent(new KeyboardEvent("keyup", { key: value.slice(-1), bubbles: true }));

      let selected = false;
      let pageResult = null;

      try {
        pageResult = await send({ type: "selectAdpfluxTreeOption", value });
        selected = Boolean(pageResult.selected || await waitFor(() => antTreeSelected(select, value), 1200, 80));
      } catch {}

      const node = antTreeNode(value);
      const checkbox = node?.querySelector(".ant-cascader-checkbox, .ant-select-tree-checkbox");
      const content = node?.querySelector(".ant-cascader-menu-item-content, .ant-select-tree-node-content-wrapper");
      const inner = node?.querySelector(".ant-cascader-checkbox-inner, .ant-select-tree-checkbox-inner");

      for (const target of selected ? [] : [checkbox, inner, content].filter(Boolean)) {
        target.scrollIntoView({ block: "nearest" });
        clickLikeUser(target);
        selected = Boolean(await waitFor(() => antTreeSelected(select, value), 900, 80));
        if (selected) break;
      }

      if (!selected) {
        input.focus();
        pressKey(input, "ArrowDown");
        pressKey(input, "Enter");
        selected = Boolean(await waitFor(() => antTreeSelected(select, value), 1200, 80));
      }

      if (!selected) failures.push(`${value}（主环境结果：${pageResult?.method || "无响应"}）`);
      nativeValue(input, "", false);
      await sleep(200);
    }

    pressKey(input, "Escape");
    nativeValue(input, "", false);
    if (failures.length) throw new Error(failures.join("；"));
  }

  function setStatus(message, type = "") {
    const status = document.querySelector("#adpflux-keyword-helper .afh-status");
    status.textContent = message;
    status.className = `afh-status ${type}`;
  }

  function collapsePanel(panel) {
    if (!panel || panel.hidden) return;
    const finish = () => {
      panel.hidden = true;
      panel.classList.remove("afh-collapsing");
    };
    panel.classList.add("afh-collapsing");
    panel.addEventListener("animationend", finish, { once: true });
    setTimeout(finish, 260);
  }

  function renderSelected() {
    const entry = state.selected;
    const meta = document.querySelector("#adpflux-keyword-helper .afh-meta");
    const material = document.querySelector("#adpflux-keyword-helper .afh-material");
    if (!entry) {
      meta.textContent = "请选择关键词配置";
      material.disabled = true;
      return;
    }
    meta.textContent = `${entry.accountCount || splitValues(entry.accountIds).length} 个账户 · ${splitValues(entry.country).length} 个国家\nchannel ${entry.channelId || "未填"} · style ${entry.styleId || "未填"}\n系列 ${campaignQuantity(entry)} · 日预算 ${dailyBudget(entry)} USD\n${adpfluxDefaults.dataConnection} · ${adpfluxDefaults.optimizationEvent}`;
    material.disabled = !entry.materialUrl;
  }

  async function loadConfigs(query = "") {
    setStatus("正在读取关键词配置...");
    const result = await send({ type: "loadAdpfluxConfigs", query });
    if (result?.error) throw new Error(result.error);
    state.entries = result.entries || [];
    const select = document.querySelector("#adpflux-keyword-helper .afh-select");
    select.innerHTML = state.entries.length
      ? state.entries.map((entry) => `<option value="${entry.id}">${String(entry.keyword || "未命名关键词").replaceAll("&", "&amp;").replaceAll("<", "&lt;")}</option>`).join("")
      : '<option value="">没有关键词配置</option>';
    state.selected = state.entries.find((entry) => String(entry.id) === launchEntryId)
      || state.entries.find((entry) => normalize(entry.keyword) === normalize(launchKeyword))
      || state.entries[0]
      || null;
    if (state.selected) select.value = String(state.selected.id);
    renderSelected();
    setStatus(state.entries.length ? `已读取 ${state.entries.length} 条配置` : "没有可用的关键词配置", state.entries.length ? "ok" : "bad");
  }

  async function fillForm() {
    const entry = state.selected;
    if (!entry) throw new Error("请先选择关键词配置");
    const fillRun = ++state.fillRun;
    const completed = [];
    const failed = [];
    const run = async (name, task) => {
      try {
        await task();
        completed.push(name);
      } catch (error) {
        failed.push(`${name}：${error.message}`);
      }
    };
    setStatus("正在填写，请不要操作页面...");
    await run("广告账户", () => chooseAntManyById("advertiser_id", splitValues(entry.accountIds)));
    await run("推广系列名称", async () => fillPlaceholder("推广系列名称", datedCampaignName(entry.keyword)));
    await run("推广系列数量", async () => fillPlaceholder("请输入推广系列个数", campaignQuantity(entry)));
    await run("日预算", async () => fillInputById("budget", dailyBudget(entry), "预算"));
    await run("地域", () => chooseAntTreeManyById("location_ids", splitValues(entry.country)));
    await run("目标页面", async () => fillPlaceholder("请输入以https://或者http://开头的完整地址", entry.articleUrl));
    await run("广告文案", async () => fillPlaceholder("请为您的广告输入文案", entry.copyText));
    setStatus("正在等待账户的转化配置加载...");
    await sleep(2500);
    await run("数据连接", () => chooseAntOptionWhenReady("数据连接", adpfluxDefaults.dataConnection));
    await sleep(1200);
    await run("优化事件", () => chooseAntOptionWhenReady("优化事件", adpfluxDefaults.optimizationEvent));
    if (failed.length) {
      setStatus(`已填写：${completed.join("、") || "无"}\n待处理：${failed.join("；")}`, "bad");
      return;
    }
    setStatus(`已填写：${completed.join("、")}。请检查素材及 TikTok 账号后再提交。`, "ok");
    setTimeout(() => {
      if (fillRun !== state.fillRun) return;
      const panel = document.querySelector("#adpflux-keyword-helper .afh-panel");
      collapsePanel(panel);
    }, 800);
  }

  function mount() {
    const root = document.createElement("section");
    root.id = "adpflux-keyword-helper";
    root.innerHTML = `
      <button class="afh-toggle" type="button">关键词填充</button>
      <div class="afh-panel" hidden>
        <div class="afh-head"><strong>关键词配置填充</strong><button class="afh-close" type="button" aria-label="关闭">×</button></div>
        <div class="afh-body">
          <label>关键词<select class="afh-select"><option>正在加载...</option></select></label>
          <div class="afh-meta">正在读取配置</div>
          <div class="afh-actions"><button class="afh-fill" type="button">填充当前表单</button><button class="afh-material" type="button" disabled>打开素材</button></div>
          <div class="afh-status"></div>
        </div>
      </div>`;
    document.body.appendChild(root);
    const panel = root.querySelector(".afh-panel");
    root.querySelector(".afh-toggle").onclick = () => {
      panel.hidden = !panel.hidden;
      if (!panel.hidden && !state.entries.length) loadConfigs().catch((error) => setStatus(error.message, "bad"));
    };
    root.querySelector(".afh-close").onclick = () => { panel.hidden = true; };
    root.querySelector(".afh-select").onchange = (event) => {
      state.selected = state.entries.find((entry) => String(entry.id) === event.target.value) || null;
      renderSelected();
    };
    root.querySelector(".afh-fill").onclick = () => fillForm().catch((error) => setStatus(error.message, "bad"));
    root.querySelector(".afh-material").onclick = () => {
      if (state.selected?.materialUrl) send({ type: "openAdpfluxMaterial", url: state.selected.materialUrl }).catch((error) => setStatus(error.message, "bad"));
    };

    if (autoFillRequested) {
      panel.hidden = false;
      loadConfigs(launchKeyword).then(async () => {
        if (!state.selected || (launchEntryId && String(state.selected.id) !== launchEntryId)) {
          throw new Error("没有找到网站中选择的关键词配置");
        }
        setStatus("已打开创建任务，正在等待 Adpflux 表单...");
        const formReady = await waitFor(() => document.getElementById("advertiser_id"), 15000, 200);
        if (!formReady) throw new Error("Adpflux 创建表单尚未打开，请进入网站转化量表单后点击“填充当前表单”");
        await fillForm();
        const cleanUrl = new URL(location.href);
        ["bi_entry_id", "bi_keyword", "bi_autofill"].forEach((key) => cleanUrl.searchParams.delete(key));
        history.replaceState(null, "", cleanUrl);
      }).catch((error) => setStatus(error.message, "bad"));
    }
  }

  if (document.body) mount();
  else addEventListener("DOMContentLoaded", mount, { once: true });
})();
