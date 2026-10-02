chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(
    ["gpcEnabled", "dntEnabled", "fingerprintShieldEnabled"],
    (result) => {
      const defaults = {
        gpcEnabled: true,
        dntEnabled: true,
        fingerprintShieldEnabled: true,
      };
      const update = {};
      for (const key in defaults) {
        if (result[key] === undefined) {
          update[key] = defaults[key];
        }
      }
      if (Object.keys(update).length > 0) {
        chrome.storage.local.set(update);
      }
    }
  );
});

// Listen for requests from content scripts running in MAIN world (where chrome.storage is unavailable)
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === "getSettings") {
    chrome.storage.local.get(
      {
        gpcEnabled: true,
        dntEnabled: true,
        fingerprintShieldEnabled: true,
      },
      (items) => {
        sendResponse({ settings: items });
      }
    );
    return true; // Keep message channel open for async sendResponse
  }
});
