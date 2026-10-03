chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(
    {
      gpcEnabled: true,
      dntEnabled: true,
      fingerprintShieldEnabled: true,
    },
    (items) => {
      chrome.storage.local.set(items);
    }
  );
});

// Listen for settings requests from content scripts (MAIN world)
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
    return true; // Keep the message channel open for the async sendResponse
  }
});
