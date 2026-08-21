(() => {
  if (window.__adpfluxMaterialHelperLoaded) return;
  window.__adpfluxMaterialHelperLoaded = true;

  const params = new URLSearchParams(location.search);
  const accountId = String(params.get("bi_account_id") || "").trim();
  if (params.get("bi_material_autofill") !== "1" || !accountId) return;

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

  async function selectAccount() {
    showStatus(`正在选择素材账户 ${accountId}...`);
    const result = await send({ type: "selectAdpfluxMaterialAccount", value: accountId });
    if (!result.selected) {
      const available = (result.options || []).map((item) => String(item).trim()).filter(Boolean);
      const detail = available.length ? `；当前可用：${available.slice(0, 5).join("、")}` : "";
      throw new Error(`当前管理员账号下未找到素材账户 ${accountId}${detail}`);
    }

    const cleanUrl = new URL(location.href);
    ["bi_entry_id", "bi_keyword", "bi_account_id", "bi_material_autofill"].forEach((key) => cleanUrl.searchParams.delete(key));
    history.replaceState(null, "", cleanUrl);
  }

  selectAccount()
    .then(() => showStatus(`已选择素材账户 ${accountId}`, "success"))
    .catch((error) => showStatus(error.message || "素材账户选择失败", "error"));
})();
