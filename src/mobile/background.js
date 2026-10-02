// background.js - xPrivacy Guard Mobile

// Sync GPC declarativeNetRequest ruleset with storage state
function syncGpcRuleset(enabled) {
  try {
    if (enabled) {
      chrome.declarativeNetRequest.updateEnabledRulesets({
        enableRulesetIds: ['gpc_rules']
      });
    } else {
      chrome.declarativeNetRequest.updateEnabledRulesets({
        disableRulesetIds: ['gpc_rules']
      });
    }
  } catch (e) {
    console.error('xPrivacyGuard Mobile: Error updating GPC ruleset:', e);
  }
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get({
    gpcEnabled: true,
    dntEnabled: true,
    cleanUrlsEnabled: true,
    fingerprintShieldEnabled: true,
    blockedCount: 0
  }, (items) => {
    chrome.storage.local.set(items);
    // Sync ruleset to match stored setting on install
    syncGpcRuleset(items.gpcEnabled);
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

chrome.runtime.onStartup.addListener(() => {
  chrome.storage.local.get({ gpcEnabled: true }, (items) => {
    syncGpcRuleset(items.gpcEnabled);
  });
});

// React to settings changes from the popup
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === 'local' && changes.gpcEnabled) {
    syncGpcRuleset(changes.gpcEnabled.newValue !== false);
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

