// background.js - xPrivacy Guard Mobile

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get({
    gpcEnabled: true,
    dntEnabled: true,
    cleanUrlsEnabled: true,
    fingerprintShieldEnabled: true,
    blockedCount: 0
  }, (items) => {
    chrome.storage.local.set(items);
  });

  try {
    if (chrome.privacy && chrome.privacy.network && chrome.privacy.network.webRTCIPHandlingPolicy) {
      chrome.privacy.network.webRTCIPHandlingPolicy.set({
        value: 'default_public_interface_only'
      });
    }
  } catch (e) {
    console.log('WebRTC privacy setting not available in this environment');
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === 'incrementBlockedCount') {
    const increment = message.count || 1;
    chrome.storage.local.get({ blockedCount: 0 }, (data) => {
      const newCount = data.blockedCount + increment;
      chrome.storage.local.set({ blockedCount: newCount }, () => {
        sendResponse({ success: true, newCount });
      });
    });
    return true;
  }
});
