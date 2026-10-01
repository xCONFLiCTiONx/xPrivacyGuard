// background.js - Service worker for xPrivacyGuard

let syncQueue = Promise.resolve();

function syncContentScripts() {
  syncQueue = syncQueue
    .then(async () => {
      await doSyncContentScripts();
    })
    .catch((err) => {
      console.error("xPrivacyGuard: Error syncing content scripts:", err);
    });
  return syncQueue;
}

async function doSyncContentScripts() {
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
    await chrome.scripting.updateContentScripts([scriptConfig]);
  } else {
    try {
      await chrome.scripting.registerContentScripts([scriptConfig]);
    } catch (err) {
      if (err && err.message && err.message.includes("Duplicate script ID")) {
        await chrome.scripting.updateContentScripts([scriptConfig]);
      } else {
        throw err;
      }
    }
  }

  console.log("xPrivacyGuard: Content script registered/updated with excludeMatches:", excludePatterns);
}

// Adaptive Icon Rendering
function createIconImageData(size, iconColor) {
  const canvas = new OffscreenCanvas(size, size);
  const ctx = canvas.getContext("2d");

  ctx.clearRect(0, 0, size, size);
  ctx.fillStyle = iconColor;

  ctx.save();
  const scale = size / 24;
  ctx.scale(scale, scale);
  const p = new Path2D(
    "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z"
  );
  ctx.fill(p);
  ctx.restore();

  return ctx.getImageData(0, 0, size, size);
}

async function updateExtensionIcon(isDarkMode) {
  const iconColor = isDarkMode ? "#FFFFFF" : "#000000";

  try {
    await chrome.action.setIcon({
      imageData: {
        16: createIconImageData(16, iconColor),
        32: createIconImageData(32, iconColor),
        48: createIconImageData(48, iconColor),
        128: createIconImageData(128, iconColor)
      }
    });
  } catch (err) {
    console.error("xPrivacyGuard: Failed to set extension icon:", err);
  }
}

// Message Listener for Adaptive Icon Theme
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message && message.action === "updateIcon") {
    updateExtensionIcon(message.isDarkMode);
    sendResponse({ success: true });
    return true;
  }
});

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
