// background.js - Service worker for xPrivacyGuard

async function syncContentScripts() {
  try {
    const data = await chrome.storage.local.get(["excludedDomains"]);
    const excludedDomains = data.excludedDomains || [];

    const excludePatterns = [];
    for (const domain of excludedDomains) {
      const trimmed = domain.trim().toLowerCase();
      if (!trimmed) continue;

      if (trimmed === "localhost" || /^\d+\.\d+\.\d+\.\d+$/.test(trimmed)) {
        excludePatterns.push(`*://${trimmed}/*`);
      } else {
        excludePatterns.push(`*://${trimmed}/*`);
        excludePatterns.push(`*://*.${trimmed}/*`);
      }
    }

    const scriptConfig = {
      id: "xPrivacyGuard-inject",
      matches: ["<all_urls>"],
      js: ["inject.js"],
      runAt: "document_start",
      world: "MAIN"
    };

    if (excludePatterns.length > 0) {
      scriptConfig.excludeMatches = excludePatterns;
    }

    const registered = await chrome.scripting.getRegisteredContentScripts({
      ids: ["xPrivacyGuard-inject"]
    });

    if (registered && registered.length > 0) {
      await chrome.scripting.unregisterContentScripts({ ids: ["xPrivacyGuard-inject"] });
    }

    await chrome.scripting.registerContentScripts([scriptConfig]);
    console.log("xPrivacyGuard: Content script registered with excludeMatches:", excludePatterns);
  } catch (err) {
    console.error("xPrivacyGuard: Error syncing content scripts:", err);
  }
}

// Sync on extension install or update
chrome.runtime.onInstalled.addListener(() => {
  syncContentScripts();
});

// Sync on browser startup
chrome.runtime.onStartup.addListener(() => {
  syncContentScripts();
});

// Sync when storage changes
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === "local" && changes.excludedDomains) {
    syncContentScripts();
  }
});

// Initial sync on service worker load
syncContentScripts();
