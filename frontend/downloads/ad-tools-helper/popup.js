const skuInput = document.querySelector("#sku-input");
const openPage = document.querySelector("#open-page");
const batchButton = document.querySelector("#batch-download");
const currentButton = document.querySelector("#download-current");
const status = document.querySelector("#status");
const extensionVersion = globalThis.chrome?.runtime?.getManifest?.().version || "1.1.1";
document.querySelector("#version").textContent = `v${extensionVersion}`;

function setStatus(message, type = "") {
  status.textContent = message;
  status.className = `status ${type}`.trim();
}

function parseSkus() {
  return [...new Set(skuInput.value.match(/(?<!\d)\d{5,20}(?!\d)/g) || [])].slice(0, 20);
}

async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

async function collectImages(tab) {
  try {
    return await chrome.tabs.sendMessage(tab.id, { type: "collectImages" });
  } catch {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
    return chrome.tabs.sendMessage(tab.id, { type: "collectImages" });
  }
}

async function initialize() {
  const tab = await activeTab();
  const sku = tab?.url?.match(/item\.jd\.com\/(\d+)\.html/)?.[1];
  if (sku) {
    skuInput.value = sku;
    setStatus(`当前 SKU：${sku}`);
  }
}

openPage.addEventListener("click", async () => {
  const [sku] = parseSkus();
  if (!sku) {
    setStatus("请输入有效 SKU", "error");
    return;
  }
  await chrome.tabs.update((await activeTab()).id, { url: `https://item.jd.com/${sku}.html` });
  window.close();
});

currentButton.addEventListener("click", async () => {
  currentButton.disabled = true;
  setStatus("正在读取当前页面...");
  try {
    const tab = await activeTab();
    if (!tab?.url?.match(/^https:\/\/item\.jd\.com\/\d+\.html/)) throw new Error("当前页面不是京东商品详情页");
    const result = await collectImages(tab);
    if (!result?.matchedElements) throw new Error("没有找到图片轮播区域");
    if (!result.urls.length) throw new Error("轮播区域没有图片");
    const outcome = await chrome.runtime.sendMessage({ type: "downloadImages", sku: result.sku, urls: result.urls });
    setStatus(`完成：${outcome.completed}/${outcome.total} 张`, outcome.completed ? "success" : "error");
  } catch (error) {
    setStatus(error.message || "处理失败", "error");
  } finally {
    currentButton.disabled = false;
  }
});

batchButton.addEventListener("click", async () => {
  const skus = parseSkus();
  if (!skus.length) {
    setStatus("请输入至少一个有效 SKU", "error");
    return;
  }
  batchButton.disabled = true;
  currentButton.disabled = true;
  openPage.disabled = true;
  setStatus(`准备处理 ${skus.length} 个 SKU...`);
  try {
    const outcome = await chrome.runtime.sendMessage({ type: "batchDownload", skus });
    const successes = outcome.results.filter((item) => item.success);
    const failures = outcome.results.filter((item) => !item.success);
    const imageTotal = successes.reduce((total, item) => total + item.images, 0);
    const suffix = failures.length ? `；失败：${failures.map((item) => item.sku).join("、")}` : "";
    setStatus(`完成：${successes.length}/${skus.length} 个 SKU，${imageTotal} 张图片${suffix}`, failures.length ? "error" : "success");
  } catch (error) {
    setStatus(error.message || "批量任务失败", "error");
  } finally {
    batchButton.disabled = false;
    currentButton.disabled = false;
    openPage.disabled = false;
  }
});

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type !== "batchProgress") return;
  setStatus(`处理中 ${message.position}/${message.total}：${message.sku}`);
});

initialize();
