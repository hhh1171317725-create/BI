function extensionFor(url) {
  const match = new URL(url).pathname.match(/\.(jpe?g|png|webp|avif)(?:$|\.)/i);
  return (match?.[1] || "jpg").toLowerCase().replace("jpeg", "jpg");
}

function todayFolder(now = new Date()) {
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

const pendingNames = new Map();

chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  const key = pendingNames.has(item.finalUrl) ? item.finalUrl : item.url;
  const queue = pendingNames.get(key);
  if (!queue?.length) return;
  const filename = queue.shift();
  if (!queue.length) pendingNames.delete(key);
  suggest({ filename, conflictAction: "overwrite" });
});

async function downloadImages(sku, urls) {
  const folder = todayFolder();
  const results = await Promise.allSettled(urls.map((url, index) => {
    const filename = `jd-images/${folder}/${sku}-${String(index + 1).padStart(2, "0")}.${extensionFor(url)}`;
    const queue = pendingNames.get(url) || [];
    queue.push(filename);
    pendingNames.set(url, queue);
    return chrome.downloads.download({
      url,
      filename,
      conflictAction: "overwrite",
      saveAs: false
    });
  }));
  return results.filter((result) => result.status === "fulfilled").length;
}

async function waitForTab(tabId, timeoutMs = 30000) {
  const current = await chrome.tabs.get(tabId);
  if (current.status === "complete") return;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      chrome.tabs.onUpdated.removeListener(listener);
      reject(new Error("商品页加载超时"));
    }, timeoutMs);
    const listener = (updatedId, changeInfo) => {
      if (updatedId !== tabId || changeInfo.status !== "complete") return;
      clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(listener);
      resolve();
    };
    chrome.tabs.onUpdated.addListener(listener);
  });
}

async function collectFromTab(tabId) {
  try {
    return await chrome.tabs.sendMessage(tabId, { type: "collectImages" });
  } catch {
    await chrome.scripting.executeScript({ target: { tabId }, files: ["content.js"] });
    return chrome.tabs.sendMessage(tabId, { type: "collectImages" });
  }
}

async function notifyProgress(payload) {
  try {
    await chrome.runtime.sendMessage({ type: "batchProgress", ...payload });
  } catch {
    // The popup may be closed while the background task continues.
  }
}

async function batchDownload(skus, progress = notifyProgress) {
  const results = [];
  for (const [position, sku] of skus.entries()) {
    await progress({ position: position + 1, total: skus.length, sku, state: "loading" });
    let tab;
    try {
      tab = await chrome.tabs.create({ url: `https://item.jd.com/${sku}.html`, active: false });
      await waitForTab(tab.id);
      await new Promise((resolve) => setTimeout(resolve, 2500));
      const found = await collectFromTab(tab.id);
      if (!found?.matchedElements) throw new Error("没有找到图片轮播区域");
      if (!found.urls.length) throw new Error("轮播区域没有图片");
      const completed = await downloadImages(sku, found.urls);
      results.push({ sku, success: completed > 0, images: completed, error: completed ? "" : "下载失败" });
    } catch (error) {
      results.push({ sku, success: false, images: 0, error: error.message || "处理失败" });
    } finally {
      if (tab?.id) await chrome.tabs.remove(tab.id).catch(() => {});
    }
    await progress({ position: position + 1, total: skus.length, sku, state: "complete" });
    if (position < skus.length - 1) await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return results;
}

async function loadAdpfluxConfigs(query = "") {
  const params = new URLSearchParams({ page: "1", pageSize: "100" });
  if (String(query || "").trim()) params.set("query", String(query).trim());
  const response = await fetch(`https://www.huanghaha.fun/api/account-vault?${params}`, {
    credentials: "include",
    cache: "no-store",
    headers: { Accept: "application/json" }
  });
  if (response.status === 401 || response.status === 403) {
    throw new Error("请先登录 huanghaha.fun，再重新加载关键词配置");
  }
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `关键词配置加载失败（HTTP ${response.status}）`);
  }
  const data = await response.json();
  return Array.isArray(data.entries) ? data.entries : [];
}

async function closeAdpfluxAccountDropdown(tabId, values) {
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    args: [Array.isArray(values) ? values.map(String) : []],
    func: async (selectedIds) => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const configTraces = [];
      const summarizeConfig = (status, body, requestBody) => {
        let payload = body;
        let request = requestBody;
        try { payload = typeof body === "string" ? JSON.parse(body) : body; } catch {}
        try { request = typeof requestBody === "string" ? JSON.parse(requestBody) : requestBody; } catch {}
        const pixels = Array.isArray(payload?.data?.pixel) ? payload.data.pixel : [];
        configTraces.push({
          requestType: request?.type || "unknown",
          advertiserId: String(request?.advertiser_id || ""),
          status,
          code: payload?.code,
          message: String(payload?.message || payload?.msg || ""),
          pixelCount: pixels.length,
          pixelNames: pixels.slice(0, 8).map((pixel) => String(pixel?.pixel_name || pixel?.name || pixel?.pixel_id || ""))
        });
      };

      const nativeOpen = XMLHttpRequest.prototype.open;
      const nativeSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        this.__adpfluxConfigUrl = String(url || "");
        return nativeOpen.call(this, method, url, ...rest);
      };
      XMLHttpRequest.prototype.send = function(body) {
        if (String(this.__adpfluxConfigUrl || "").includes("/api/v1/tiktok/get_config")) {
          this.addEventListener("loadend", () => summarizeConfig(this.status, this.responseText, body), { once: true });
        }
        return nativeSend.call(this, body);
      };

      const nativeFetch = window.fetch;
      window.fetch = async function(resource, init) {
        const response = await nativeFetch.call(this, resource, init);
        const url = typeof resource === "string" ? resource : resource?.url;
        if (String(url || "").includes("/api/v1/tiktok/get_config")) {
          response.clone().text().then((body) => summarizeConfig(response.status, body, init?.body)).catch(() => {});
        }
        return response;
      };
      const restoreNetwork = () => {
        XMLHttpRequest.prototype.open = nativeOpen;
        XMLHttpRequest.prototype.send = nativeSend;
        window.fetch = nativeFetch;
      };
      const restoreTimer = window.setTimeout(restoreNetwork, 15000);

      let input = document.getElementById("advertiser_id");
      if (!input) {
        window.clearTimeout(restoreTimer);
        restoreNetwork();
        return { closed: false, method: "input-not-found" };
      }

      const accountProps = (element) => {
        const fiberKey = Object.keys(element).find((key) => key.startsWith("__reactFiber$"));
        let fiber = fiberKey ? element[fiberKey] : null;
        while (fiber) {
          for (const props of [fiber.memoizedProps, fiber.pendingProps]) {
            if (props?.placeholder === "请选择广告账户"
              && props?.mode === "multiple"
              && typeof props?.onChange === "function") {
              return props;
            }
          }
          fiber = fiber.return;
        }
        return null;
      };

      let commitHandlerCount = 0;
      let matchedOptionCount = 0;
      const props = accountProps(input);
      if (props && selectedIds.length) {
        const options = Array.isArray(props.options) ? props.options : [];
        const selectedOptions = selectedIds.map((id) => options.find((option) => String(option?.value) === id))
          .filter(Boolean);
        matchedOptionCount = selectedOptions.length;
        props.onChange(selectedIds, selectedOptions);
        commitHandlerCount = 1;
        await wait(350);
        input = document.getElementById("advertiser_id") || input;
      }

      const wasOpen = input.getAttribute("aria-expanded") === "true";

      const target = [...document.querySelectorAll("input")].find((element) => {
        const placeholder = String(element.getAttribute("placeholder") || "");
        return element.id !== "advertiser_id" && /系列名称|推广系列/.test(placeholder);
      }) || document.querySelector("main") || document.body;

      if (wasOpen) {
        target.focus?.();
        const MouseEventType = window.PointerEvent || window.MouseEvent;
        for (const type of ["pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
          const EventType = type.startsWith("pointer") ? MouseEventType : window.MouseEvent;
          target.dispatchEvent(new EventType(type, {
            bubbles: true,
            cancelable: true,
            composed: true,
            button: 0,
            buttons: type.endsWith("down") ? 1 : 0,
            view: window
          }));
        }

        for (let attempt = 0; attempt < 20; attempt += 1) {
          await wait(100);
          if (input.getAttribute("aria-expanded") !== "true") break;
        }
      }

      const invoked = [];
      const fiberKey = Object.keys(input).find((key) => key.startsWith("__reactFiber$"));
      let fiber = fiberKey ? input[fiberKey] : null;
      const seen = new Set();
      while (fiber) {
        for (const props of [fiber.memoizedProps, fiber.pendingProps]) {
          if (!props || typeof props !== "object") continue;
          const isAccountSelect = props.id === "advertiser_id"
            || props.placeholder === "请选择广告账户"
            || (props.mode === "multiple" && typeof props.onDropdownVisibleChange === "function");
          if (!isAccountSelect) continue;
          for (const name of ["onDropdownVisibleChange", "onOpenChange"]) {
            const handler = props[name];
            if (typeof handler !== "function" || seen.has(handler)) continue;
            seen.add(handler);
            handler(false);
            invoked.push(name);
          }
        }
        fiber = fiber.return;
      }

      await wait(800);
      for (let attempt = 0; attempt < 50 && !configTraces.some((trace) => trace.requestType === "pixel"); attempt += 1) {
        await wait(100);
      }
      window.clearTimeout(restoreTimer);
      restoreNetwork();
      const closed = input.getAttribute("aria-expanded") !== "true";
      return {
        closed,
        method: invoked.length ? "react-dropdown-handler" : (closed ? "outside-click" : "outside-click-rejected"),
        handlerCount: invoked.length,
        commitHandlerCount,
        matchedOptionCount,
        pixelRequest: configTraces.find((trace) => trace.requestType === "pixel") || null
      };
    }
  });
  return result || { closed: false, method: "no-result" };
}

async function selectAdpfluxTreeOption(tabId, value) {
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    args: [String(value || "")],
    func: async (wanted) => {
      const normalize = (text) => String(text || "").replace(/\s+/g, "").trim().toLowerCase();
      const visible = (element) => {
        const rect = element?.getBoundingClientRect();
        const style = element ? getComputedStyle(element) : null;
        return Boolean(rect?.width && rect?.height && style?.display !== "none" && style?.visibility !== "hidden");
      };
      const findNode = () => [...document.querySelectorAll(".ant-cascader-menu-item, .ant-select-tree-treenode")]
        .filter(visible)
        .find((node) => {
          const label = node.querySelector(".ant-cascader-menu-item-content, .ant-select-tree-title");
          return normalize(label?.textContent) === normalize(wanted);
        });
      const isSelected = () => {
        const node = findNode();
        const checkbox = node?.querySelector(".ant-cascader-checkbox, .ant-select-tree-checkbox");
        const select = document.getElementById("location_ids")?.closest(".ant-select");
        const tagSelected = [...(select?.querySelectorAll(".ant-select-selection-item") || [])]
          .some((item) => normalize(item.textContent).includes(normalize(wanted)));
        return tagSelected
          || node?.getAttribute("aria-selected") === "true"
          || node?.getAttribute("aria-checked") === "true"
          || checkbox?.getAttribute("aria-checked") === "true"
          || checkbox?.classList.contains("ant-cascader-checkbox-checked")
          || checkbox?.classList.contains("ant-select-tree-checkbox-checked")
          || false;
      };
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const input = document.getElementById("location_ids");
      const select = input?.closest(".ant-select");
      const selector = select?.querySelector(".ant-select-selector");
      if (!input || !select || !selector) {
        return { found: false, selected: false, method: "input-not-found" };
      }

      if (input.getAttribute("aria-expanded") !== "true") {
        selector.click();
        await wait(180);
      }

      const setInputValue = (nextValue) => {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input, nextValue);
        input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: nextValue }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
        input.dispatchEvent(new KeyboardEvent("keyup", { key: nextValue.slice(-1), bubbles: true }));
      };
      input.focus();
      setInputValue("");
      setInputValue(wanted);

      let node = null;
      for (let attempt = 0; attempt < 24 && !node; attempt += 1) {
        await wait(100);
        node = findNode();
      }

      if (!node) {
        const holder = [...document.querySelectorAll(".ant-cascader-menu, .rc-virtual-list-holder, .ant-select-tree-list-holder")]
          .filter(visible)
          .sort((a, b) => b.scrollHeight - a.scrollHeight)[0];
        if (holder) {
          setInputValue("");
          const step = Math.max(120, Math.floor(holder.clientHeight * 0.8));
          for (let top = 0; top <= holder.scrollHeight && !node; top += step) {
            holder.scrollTop = top;
            holder.dispatchEvent(new Event("scroll", { bubbles: true }));
            await wait(90);
            node = findNode();
          }
        }
      }

      if (!node) return { found: false, selected: false, method: "search-and-scroll-not-found" };
      if (isSelected()) return { found: true, selected: true, method: "already-selected" };

      const checkbox = node.querySelector(".ant-cascader-checkbox, .ant-select-tree-checkbox");
      const content = node.querySelector(".ant-cascader-menu-item-content, .ant-select-tree-node-content-wrapper");
      const targets = [checkbox, content, node].filter(Boolean);

      for (const target of targets) {
        target.scrollIntoView({ block: "nearest" });
        target.click();
        await wait(180);
        if (isSelected()) return { found: true, selected: true, method: "main-world-click" };
      }

      const handlers = [];
      for (const target of targets) {
        const propsKey = Object.keys(target).find((key) => key.startsWith("__reactProps$"));
        const props = propsKey ? target[propsKey] : null;
        const handler = props?.onClick;
        if (typeof handler !== "function") continue;
        handlers.push(target.className || target.tagName);
        const event = {
          type: "click",
          button: 0,
          target,
          currentTarget: target,
          nativeEvent: { target },
          defaultPrevented: false,
          preventDefault() { this.defaultPrevented = true; },
          stopPropagation() {},
          persist() {},
          isDefaultPrevented() { return this.defaultPrevented; },
          isPropagationStopped() { return false; }
        };
        handler(event);
        await wait(180);
        if (isSelected()) return { found: true, selected: true, method: "react-handler" };
      }

      return { found: true, selected: false, method: "rejected", handlers };
    }
  });
  return result || { found: false, selected: false, method: "no-result" };
}

async function selectAdpfluxOption(tabId, inputId, value) {
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    args: [String(inputId || ""), String(value || "")],
    func: async (fieldId, wanted) => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const normalize = (text) => String(text || "").replace(/\s+/g, "").trim().toLowerCase();
      const visible = (element) => {
        const rect = element?.getBoundingClientRect();
        const style = element ? getComputedStyle(element) : null;
        return Boolean(rect?.width && rect?.height && style?.display !== "none" && style?.visibility !== "hidden");
      };
      const inputs = [...document.querySelectorAll(`[id="${CSS.escape(fieldId)}"]`)];
      const input = inputs.find((element) => visible(element.closest(".ant-select"))) || inputs[0];
      const select = input?.closest(".ant-select");
      const selector = select?.querySelector(".ant-select-selector");
      if (!input || !select || !selector) {
        return { opened: false, selected: false, optionCount: 0, method: "input-not-found" };
      }

      if (normalize(select.textContent).includes(normalize(wanted))) {
        return { opened: input.getAttribute("aria-expanded") === "true", selected: true, optionCount: 0, method: "already-selected" };
      }

      if (input.getAttribute("aria-expanded") !== "true") {
        selector.click();
        await wait(220);
      }
      if (input.getAttribute("aria-expanded") !== "true") {
        input.focus();
        input.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", code: "ArrowDown", bubbles: true }));
        input.dispatchEvent(new KeyboardEvent("keyup", { key: "ArrowDown", code: "ArrowDown", bubbles: true }));
        await wait(220);
      }

      const target = normalize(wanted);
      let options = [];
      let option = null;
      for (let attempt = 0; attempt < 50 && !option; attempt += 1) {
        options = [...document.querySelectorAll(".ant-select-dropdown .ant-select-item-option")].filter(visible);
        option = options.find((item) => normalize(item.textContent) === target)
          || options.find((item) => normalize(item.textContent).includes(target));
        if (!option) await wait(100);
      }

      if (!option) {
        return {
          opened: input.getAttribute("aria-expanded") === "true",
          selected: false,
          optionCount: options.length,
          options: options.slice(0, 8).map((item) => String(item.textContent || "").trim()),
          method: "option-not-found"
        };
      }

      option.scrollIntoView({ block: "nearest" });
      option.click();
      for (let attempt = 0; attempt < 25; attempt += 1) {
        await wait(100);
        if (normalize(select.textContent).includes(target)) {
          return { opened: true, selected: true, optionCount: options.length, method: "main-world-click" };
        }
      }
      return { opened: true, selected: false, optionCount: options.length, method: "click-rejected" };
    }
  });
  return result || { opened: false, selected: false, optionCount: 0, method: "no-result" };
}

async function selectAdpfluxMaterialAccount(tabId, value) {
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    args: [String(value || "")],
    func: async (wanted) => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const normalize = (text) => String(text || "").replace(/\s+/g, "").trim().toLowerCase();
      const visible = (element) => {
        const rect = element?.getBoundingClientRect();
        const style = element ? getComputedStyle(element) : null;
        return Boolean(rect?.width && rect?.height && style?.display !== "none" && style?.visibility !== "hidden");
      };
      const textOf = (value) => {
        if (value == null) return "";
        if (["string", "number"].includes(typeof value)) return String(value);
        if (Array.isArray(value)) return value.map(textOf).join(" ");
        return textOf(value.props?.children);
      };
      const findInput = () => {
        const inputs = [...document.querySelectorAll("input")];
        return inputs.find((input) => /账户|广告账户/.test(input.placeholder || ""))
          || inputs.find((input) => {
            const root = input.closest(".ant-select, .arco-select");
            return root && visible(root) && /账户/.test(root.parentElement?.textContent || "");
          })
          || inputs.find((input) => visible(input.closest(".ant-select, .arco-select")));
      };
      const input = findInput();
      const root = input?.closest(".ant-select, .arco-select") || input?.parentElement;
      const selector = root?.querySelector(".ant-select-selector, .arco-select-view") || root;
      if (!input || !root || !selector) return { selected: false, method: "input-not-found", options: [] };
      const isSelected = () => normalize(root.textContent).includes(normalize(wanted));
      if (isSelected()) return { selected: true, method: "already-selected", options: [] };

      const optionText = (option) => `${option?.value ?? ""} ${option?.label ?? ""} ${textOf(option?.label)} ${option?.name ?? ""}`;
      const flatten = (items) => (Array.isArray(items) ? items.flatMap((item) => [item, ...flatten(item?.options), ...flatten(item?.children)]) : []);
      const available = [];
      const seenHandlers = new Set();
      const fiberKey = Object.keys(input).find((key) => key.startsWith("__reactFiber$"));
      let fiber = fiberKey ? input[fiberKey] : null;
      while (fiber) {
        for (const props of [fiber.memoizedProps, fiber.pendingProps]) {
          if (!props || typeof props !== "object") continue;
          const choices = flatten(props.options);
          for (const option of choices) {
            const text = optionText(option).trim();
            if (text && available.length < 12 && !available.includes(text)) available.push(text);
          }
          const candidate = choices.find((option) => normalize(optionText(option)).includes(normalize(wanted)));
          if (!candidate || typeof props.onChange !== "function" || seenHandlers.has(props.onChange)) continue;
          seenHandlers.add(props.onChange);
          props.onChange(candidate.value, candidate);
          await wait(500);
          if (isSelected()) return { selected: true, method: "react-onchange", options: available };
        }
        fiber = fiber.return;
      }

      selector.click();
      await wait(300);
      input.focus();
      const setValue = (next) => {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input, next);
        input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: next }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
        input.dispatchEvent(new KeyboardEvent("keyup", { key: next.slice(-1), bubbles: true }));
      };
      setValue("");
      setValue(wanted);

      let nodes = [];
      let option = null;
      for (let attempt = 0; attempt < 60 && !option; attempt += 1) {
        nodes = [...document.querySelectorAll("[role='option'], .ant-select-item-option, .arco-select-option")].filter(visible);
        option = nodes.find((node) => normalize(node.textContent).includes(normalize(wanted)));
        if (!option) await wait(100);
      }
      const domOptions = nodes.map((node) => String(node.textContent || "").trim()).filter(Boolean).slice(0, 12);
      if (!option) return { selected: false, method: "option-not-found", options: domOptions.length ? domOptions : available };
      option.scrollIntoView({ block: "nearest" });
      option.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window }));
      option.click();
      for (let attempt = 0; attempt < 30; attempt += 1) {
        await wait(100);
        if (isSelected()) return { selected: true, method: "main-world-click", options: domOptions };
      }
      return { selected: false, method: "click-rejected", options: domOptions };
    }
  });
  return result || { selected: false, method: "no-result", options: [] };
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "loadAdpfluxConfigs") {
    loadAdpfluxConfigs(message.query)
      .then((entries) => sendResponse({ entries }))
      .catch((error) => sendResponse({ error: error.message || "关键词配置加载失败" }));
    return true;
  }
  if (message?.type === "openAdpfluxMaterial") {
    try {
      const url = new URL(String(message.url || ""));
      if (url.protocol !== "https:") throw new Error("素材地址必须使用 HTTPS");
      chrome.tabs.create({ url: url.href }).then(() => sendResponse({ ok: true }));
    } catch (error) {
      sendResponse({ error: error.message || "素材地址无效" });
    }
    return true;
  }
  if (message?.type === "selectAdpfluxTreeOption") {
    let trusted = false;
    try {
      trusted = new URL(_sender.url).hostname === "www.adpflux.com";
    } catch {}
    if (!trusted || !_sender.tab?.id) {
      sendResponse({ error: "地域勾选请求来源无效" });
      return false;
    }
    selectAdpfluxTreeOption(_sender.tab.id, message.value)
      .then(sendResponse)
      .catch((error) => sendResponse({ error: error.message || "地域勾选失败" }));
    return true;
  }
  if (message?.type === "closeAdpfluxAccountDropdown") {
    let trusted = false;
    try {
      trusted = new URL(_sender.url).hostname === "www.adpflux.com";
    } catch {}
    if (!trusted || !_sender.tab?.id) {
      sendResponse({ error: "账户选择请求来源无效" });
      return false;
    }
    closeAdpfluxAccountDropdown(_sender.tab.id, message.values)
      .then(sendResponse)
      .catch((error) => sendResponse({ error: error.message || "账户下拉框关闭失败" }));
    return true;
  }
  if (message?.type === "selectAdpfluxOption") {
    let trusted = false;
    try {
      trusted = new URL(_sender.url).hostname === "www.adpflux.com";
    } catch {}
    if (!trusted || !_sender.tab?.id) {
      sendResponse({ error: "下拉选择请求来源无效" });
      return false;
    }
    selectAdpfluxOption(_sender.tab.id, message.inputId, message.value)
      .then(sendResponse)
      .catch((error) => sendResponse({ error: error.message || "下拉选项选择失败" }));
    return true;
  }
  if (message?.type === "selectAdpfluxMaterialAccount") {
    let trusted = false;
    try {
      trusted = new URL(_sender.url).hostname === "www.adpflux.com";
    } catch {}
    if (!trusted || !_sender.tab?.id) {
      sendResponse({ error: "素材账户选择请求来源无效" });
      return false;
    }
    selectAdpfluxMaterialAccount(_sender.tab.id, message.value)
      .then(sendResponse)
      .catch((error) => sendResponse({ error: error.message || "素材账户选择失败" }));
    return true;
  }
  if (message?.type === "downloadImages") {
    downloadImages(message.sku, message.urls).then((completed) => {
      sendResponse({ completed, total: message.urls.length });
    });
    return true;
  }
  if (message?.type === "batchDownload") {
    if (message.bridge) {
      let trusted = false;
      try {
        trusted = ["huanghaha.fun", "www.huanghaha.fun"].includes(new URL(_sender.url).hostname);
      } catch {}
      if (!trusted) {
        sendResponse({ error: "网页来源未授权" });
        return false;
      }
    }
    const progress = message.bridge && _sender.tab?.id
      ? (payload) => chrome.tabs.sendMessage(_sender.tab.id, { type: "batchProgress", ...payload }).catch(() => {})
      : notifyProgress;
    batchDownload(message.skus, progress)
      .then((results) => sendResponse({ results }))
      .catch((error) => sendResponse({ error: error.message || "批量任务失败" }));
    return true;
  }
  return false;
});
