chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(["gpcEnabled"], (result) => {
    if (result.gpcEnabled === undefined) {
      chrome.storage.local.set({ gpcEnabled: true });
    }
  });
});

async function updateGPCRules(enabled) {
  const ruleId = 1001;
  if (enabled) {
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: [ruleId],
      addRules: [
        {
          id: ruleId,
          priority: 1,
          action: {
            type: "modifyHeaders",
            requestHeaders: [
              { header: "Sec-GPC", operation: "set", value: "1" },
            ],
          },
          condition: {
            urlFilter: "*",
            resourceTypes: ["main_frame", "sub_frame"],
          },
        },
      ],
    });
  } else {
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: [ruleId],
      addRules: [],
    });
  }
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.gpcEnabled) {
    updateGPCRules(changes.gpcEnabled.newValue);
  }
});
