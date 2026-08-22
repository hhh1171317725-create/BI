(() => {
  if (window.__adpfluxRechargeHelperLoaded) return;
  window.__adpfluxRechargeHelperLoaded = true;

  const params = new URLSearchParams(location.search);
  const accountId = String(params.get("bi_account_id") || "").trim();
  if (params.get("bi_recharge_autofill") !== "1" || !accountId) return;

  function send(message) {
    return new Promise((resolve, reject) => {
      chrome.runtime.sendMessage(message, (response) => {
        const error = chrome.runtime.lastError?.message || response?.error;
        if (error) reject(new Error(error));
        else resolve(response || {});
      });
    });
  }

  function showStatus(message, type = "loading") {
    let node = document.getElementById("bi-recharge-account-status");
    if (!node) {
      node = document.createElement("div");
      node.id = "bi-recharge-account-status";
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

  async function searchAccount() {
    showStatus(`正在查询充值账户 ${accountId}...`);
    const result = await send({ type: "searchAdpfluxRechargeAccount", value: accountId });
    if (!result.searched) {
      if (result.method === "input-not-found") throw new Error("未找到充值账户输入框，请刷新页面后重试");
      if (result.method === "query-not-found") throw new Error("账号已填写，但未找到查询按钮，请手动点击查询");
      throw new Error(`充值账户 ${accountId} 自动查询失败，请手动查询`);
    }
    const cleanUrl = new URL(location.href);
    ["bi_entry_id", "bi_keyword", "bi_account_id", "bi_recharge_autofill"].forEach((key) => cleanUrl.searchParams.delete(key));
    history.replaceState(null, "", cleanUrl);
  }

  searchAccount()
    .then(() => showStatus(`已查询充值账户 ${accountId}`, "success"))
    .catch((error) => showStatus(error.message || "充值账户查询失败", "error"));
})();
