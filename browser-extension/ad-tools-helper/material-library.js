(() => {
  if (window.__adpfluxMaterialHelperLoaded) return;
  window.__adpfluxMaterialHelperLoaded = true;

  const params = new URLSearchParams(location.search);
  const accountId = String(params.get("bi_account_id") || "").trim();
  if (params.get("bi_material_autofill") !== "1" || !accountId) return;

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const normalize = (value) => String(value || "").replace(/\s+/g, "").toLowerCase();
  const visible = (element) => {
    const rect = element?.getBoundingClientRect();
    const style = element ? getComputedStyle(element) : null;
    return Boolean(rect?.width && rect?.height && style?.display !== "none" && style?.visibility !== "hidden");
  };

  function showStatus(message, type = "loading") {
    let node = document.getElementById("bi-material-account-status");
    if (!node) {
      node = document.createElement("div");
      node.id = "bi-material-account-status";
      Object.assign(node.style, {
        position: "fixed", right: "20px", bottom: "20px", zIndex: "2147483647",
        maxWidth: "420px", padding: "11px 14px", borderRadius: "6px",
        color: "#fff", font: '14px "Microsoft YaHei", sans-serif',
        boxShadow: "0 10px 30px rgba(0,0,0,.2)"
      });
      document.body.appendChild(node);
    }
    node.style.background = type === "error" ? "#b42318" : type === "success" ? "#067647" : "#1756a9";
    node.textContent = message;
    if (type !== "loading") setTimeout(() => node.remove(), 5000);
  }

  function accountInput() {
    const inputs = [...document.querySelectorAll("input[role='combobox'], input[autocomplete='off'], input")].filter(visible);
    return inputs.find((input) => /账户|广告账户/.test(input.placeholder || ""))
      || inputs.find((input) => input.closest(".ant-select, .arco-select") && /账户/.test(input.closest(".ant-select, .arco-select")?.parentElement?.textContent || ""))
      || inputs.find((input) => input.closest(".ant-select, .arco-select"));
  }

  function selectRoot(input) {
    return input?.closest(".ant-select, .arco-select") || input?.parentElement;
  }

  function selected(input) {
    return normalize(selectRoot(input)?.textContent).includes(normalize(accountId));
  }

  function setInputValue(input, value) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input, value);
    input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    input.dispatchEvent(new KeyboardEvent("keyup", { key: value.slice(-1), bubbles: true }));
  }

  function options() {
    return [...document.querySelectorAll("[role='option'], .ant-select-item-option, .arco-select-option")].filter(visible);
  }

  async function selectAccount() {
    showStatus(`正在选择素材账户 ${accountId}...`);
    let input = null;
    for (let attempt = 0; attempt < 100 && !input; attempt += 1) {
      input = accountInput();
      if (!input) await sleep(150);
    }
    if (!input) throw new Error("素材库账户下拉框尚未加载");
    if (selected(input)) return;

    const root = selectRoot(input);
    const trigger = root?.querySelector(".ant-select-selector, .arco-select-view, [role='combobox']") || root || input;
    trigger.click();
    await sleep(300);
    input.focus();
    setInputValue(input, "");
    setInputValue(input, accountId);

    let option = null;
    for (let attempt = 0; attempt < 60 && !option; attempt += 1) {
      option = options().find((item) => normalize(item.textContent).includes(normalize(accountId)));
      if (!option) await sleep(120);
    }
    if (!option) throw new Error(`当前账号下未找到素材账户 ${accountId}`);

    option.scrollIntoView({ block: "nearest" });
    option.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window }));
    option.click();
    for (let attempt = 0; attempt < 30 && !selected(input); attempt += 1) await sleep(100);
    if (!selected(input)) throw new Error(`找到账户 ${accountId}，但页面未能选中`);

    const cleanUrl = new URL(location.href);
    ["bi_entry_id", "bi_keyword", "bi_account_id", "bi_material_autofill"].forEach((key) => cleanUrl.searchParams.delete(key));
    history.replaceState(null, "", cleanUrl);
  }

  selectAccount()
    .then(() => showStatus(`已选择素材账户 ${accountId}`, "success"))
    .catch((error) => showStatus(error.message || "素材账户选择失败", "error"));
})();
