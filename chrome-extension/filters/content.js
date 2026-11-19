(function () {
  "use strict";

  let debugEnabled = false;

  function log(category, message) {
    if (debugEnabled || category === "cosmetic" || category === "scriptlet") {
      chrome.runtime.sendMessage({
        action: "recordFilterEvent",
        category,
        message,
        hostname: location.hostname
      }).catch((error) => {
        console.error("xPrivacyGuard: Could not record a filter event:", error);
      });
    }
  }

  function applyCosmeticRules(selectors) {
    const valid = [];
    const probe = document.createElement("div");
    let size = 0;
    for (const selector of selectors) {
      try {
        probe.matches(selector);
        size += selector.length;
        if (size > 500000) break;
        valid.push(selector);
      } catch (error) {
        log("unsupported", `Invalid cosmetic selector ignored: ${selector}`);
      }
    }
    if (!valid.length) return 0;

    const style = document.createElement("style");
    style.dataset.xprivacyguard = "cosmetic";
    style.textContent = `${valid.join(",\n")}{display:none!important}`;
    (document.head || document.documentElement).appendChild(style);
    return valid.length;
  }

  function applyFilters(response) {
    debugEnabled = response.debug === true;
    const count = applyCosmeticRules(response.selectors || []);
    if (count) log("cosmetic", `Applied ${count} cosmetic selectors: ${(response.selectors || []).slice(0, 10).join(", ")}`);

    for (const rule of response.scriptlets || []) {
      if (FilterScriptlets.runScriptlet(rule)) {
        log("scriptlet", `${rule.name} applied.`);
      } else {
        log("unsupported", `Scriptlet ${rule.name} could not be applied.`);
      }
    }
  }

  chrome.runtime.sendMessage({
    action: "getPageFilters",
    url: location.href
  }).then((response) => {
    if (!response || !response.success) throw new Error("The filter engine did not return page rules.");
    if (document.documentElement) {
      applyFilters(response);
      return;
    }
    const observer = new MutationObserver(() => {
      if (!document.documentElement) return;
      observer.disconnect();
      applyFilters(response);
    });
    observer.observe(document, { childList: true, subtree: true });
  }).catch((error) => {
    console.error("xPrivacyGuard: Could not apply page filters:", error);
  });
})();
