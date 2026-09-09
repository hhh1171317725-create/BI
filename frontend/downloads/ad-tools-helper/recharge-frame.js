(() => {
  if (window.__adpfluxRechargeFrameLoaded) return;
  window.__adpfluxRechargeFrameLoaded = true;

  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const normalize = (text) => String(text || "").replace(/\s+/g, "").trim().toLowerCase();
  const visible = (element) => {
    const rect = element?.getBoundingClientRect();
    const style = element ? getComputedStyle(element) : null;
    return Boolean(rect?.width && rect?.height && style?.display !== "none" && style?.visibility !== "hidden");
  };

  function findAccountInput() {
    return [...document.querySelectorAll("input")].find((input) => {
      const placeholder = String(input.placeholder || "").replace(/\s+/g, "");
      return visible(input) && /多个id.*(逗号|分隔)|(账户|账号)id/i.test(placeholder);
    }) || null;
  }

  async function fillAndQuery(accountId) {
    const accountIds = [...new Set(
      String(accountId || "").split(/[\s,，、]+/).map((value) => value.trim()).filter(Boolean)
    )];
    const accountQuery = accountIds.join(" ");
    if (!accountQuery) return { searched: false, method: "missing-account" };

    let input = null;
    for (let attempt = 0; attempt < 120 && !input; attempt += 1) {
      input = findAccountInput();
      if (!input) await wait(150);
    }
    if (!input) return { searched: false, method: "input-not-found" };

    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    input.focus();
    setter?.call(input, "");
    input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "deleteContentBackward" }));
    setter?.call(input, accountQuery);
    input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: accountQuery }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    await wait(300);

    let queryButton = null;
    for (let attempt = 0; attempt < 60 && !queryButton; attempt += 1) {
      queryButton = [...document.querySelectorAll("button")].find((button) =>
        visible(button) && normalize(button.textContent) === "查询");
      if (!queryButton) await wait(100);
    }
    if (!queryButton) return { searched: false, method: "query-not-found" };

    queryButton.click();
    return { searched: true, method: "embedded-frame", accountCount: accountIds.length };
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type !== "fillRechargeAccountFrame") return false;
    const accountId = String(message.value || "").trim();
    if (!accountId) {
      sendResponse({ searched: false, method: "missing-account" });
      return false;
    }
    fillAndQuery(accountId)
      .then(sendResponse)
      .catch((error) => sendResponse({ searched: false, method: "frame-error", error: error.message }));
    return true;
  });
})();
