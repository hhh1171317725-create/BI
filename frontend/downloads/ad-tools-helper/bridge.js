const PAGE_SOURCE = "jd-image-page";
const EXTENSION_SOURCE = "jd-image-extension";
const ALLOWED_HOSTS = new Set(["huanghaha.fun", "www.huanghaha.fun"]);

function post(payload) {
  window.postMessage({ source: EXTENSION_SOURCE, ...payload }, location.origin);
}

window.addEventListener("message", (event) => {
  if (event.source !== window || event.origin !== location.origin) return;
  if (!ALLOWED_HOSTS.has(location.hostname) || event.data?.source !== PAGE_SOURCE) return;
  if (event.data.type === "ping") {
    post({ type: "ready", version: chrome.runtime.getManifest().version });
    return;
  }
  if (event.data.type !== "batchDownload") return;
  const requestId = String(event.data.requestId || "");
  const skus = [...new Set((event.data.skus || []).map(String).filter((sku) => /^\d{5,20}$/.test(sku)))].slice(0, 20);
  if (!requestId || !skus.length) {
    post({ type: "response", requestId, error: "没有有效 SKU" });
    return;
  }
  chrome.runtime.sendMessage({ type: "batchDownload", bridge: true, requestId, skus }, (response) => {
    const error = chrome.runtime.lastError?.message || response?.error || "";
    post({ type: "response", requestId, response, error });
  });
});

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === "batchProgress") post({ type: "progress", ...message });
});

post({ type: "ready", version: chrome.runtime.getManifest().version });
