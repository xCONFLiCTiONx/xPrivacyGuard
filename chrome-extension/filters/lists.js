(function (root) {
  "use strict";

  const MAX_LIST_BYTES = 20 * 1024 * 1024;

  function validateListUrl(value) {
    let url;
    try {
      url = new URL(value);
    } catch (error) {
      throw new Error("Enter a valid HTTP or HTTPS filter-list URL.");
    }
    if (url.protocol !== "https:" && url.protocol !== "http:") {
      throw new Error("Filter-list URLs must use HTTP or HTTPS.");
    }
    if (url.username || url.password) throw new Error("Filter-list URLs cannot include credentials.");
    return url.href;
  }

  async function downloadList(url) {
    const sourceUrl = new URL(validateListUrl(url));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetch(sourceUrl.href, {
        signal: controller.signal,
        credentials: "omit",
        cache: "no-cache",
        redirect: "follow"
      });
      if (!response.ok) throw new Error(`Filter list request failed with HTTP ${response.status}.`);
      const finalUrl = new URL(response.url);
      if ((finalUrl.protocol !== "https:" && finalUrl.protocol !== "http:")
        || finalUrl.username || finalUrl.password
        || (sourceUrl.protocol === "https:" && finalUrl.protocol !== "https:")) {
        throw new Error("Filter-list redirects must end at a credential-free HTTP(S) URL.");
      }
      const declaredLength = Number(response.headers.get("content-length") || 0);
      if (declaredLength > MAX_LIST_BYTES) throw new Error("Filter list exceeds the 20 MB size limit.");
      if (!response.body) throw new Error("The filter-list response has no readable body.");
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8", { fatal: true });
      let text = "";
      let byteCount = 0;
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        byteCount += chunk.value.byteLength;
        if (byteCount > MAX_LIST_BYTES) {
          await reader.cancel();
          throw new Error("Filter list exceeds the 20 MB size limit.");
        }
        text += decoder.decode(chunk.value, { stream: true });
      }
      text += decoder.decode();
      if (!text.trim()) throw new Error("The downloaded filter list is empty.");
      return text;
    } finally {
      clearTimeout(timer);
    }
  }

  root.FilterLists = { validateListUrl, downloadList };
})(globalThis);
