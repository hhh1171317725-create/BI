function normalizeImageUrl(value) {
  const parsed = new URL(value);
  parsed.pathname = parsed.pathname
    .replace(/\/n\d+\/s\d+x\d+_jfs\//i, "/n0/jfs/")
    .replace(/\/n\d+\//i, "/n0/")
    .replace(/\/s\d+x\d+_jfs\//i, "/s1440x1440_jfs/")
    .replace(/(\.(?:jpe?g|png))\.(?:avif|webp)$/i, "$1");
  return parsed.href;
}

function collectCarouselImages() {
  const elements = [...document.querySelectorAll(".image-carousel-content .image")];
  const urls = [];

  const add = (value) => {
    if (!value) return;
    const matches = String(value).match(/(?:https?:)?\/\/[^"',)\s]+/g) || [];
    for (let url of matches) {
      if (url.startsWith("//")) url = `https:${url}`;
      url = normalizeImageUrl(url);
      if (!urls.includes(url)) urls.push(url);
    }
  };

  for (const element of elements) {
    const candidates = element.matches("img,source")
      ? [element]
      : [...element.querySelectorAll("img,source")];
    for (const image of candidates) {
      add(image.currentSrc);
      add(image.src);
      add(image.srcset);
      for (const name of ["data-src", "data-lazy-img", "data-original", "data-url"]) {
        add(image.getAttribute(name));
      }
    }
    add(getComputedStyle(element).backgroundImage);
    for (const name of ["data-src", "data-lazy-img", "data-original", "data-url"]) {
      add(element.getAttribute(name));
    }
  }

  return {
    sku: location.pathname.match(/\/(\d+)\.html/)?.[1] || "jd-image",
    urls,
    matchedElements: elements.length
  };
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== "collectImages") return false;
  sendResponse(collectCarouselImages());
  return false;
});
