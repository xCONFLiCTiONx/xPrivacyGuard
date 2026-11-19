"use strict";

const assert = require("node:assert/strict");

// Mock Chrome API
const updatedTabs = [];
const queriedTabs = [
  { id: 1, url: "https://noisy.example.com/stream", mutedInfo: { muted: false } },
  { id: 2, url: "https://silent.example.com/home", mutedInfo: { muted: true } },
  { id: 3, url: "chrome://settings", mutedInfo: { muted: false } },
  { id: 4, url: "https://sub.domain.noisy.example.com/video", mutedInfo: { muted: false } }
];

let localStorageData = {
  mutedSites: ["noisy.example.com"]
};

globalThis.chrome = {
  action: {
    async setBadgeText() {},
    async setBadgeBackgroundColor() {},
    async setBadgeTextColor() {},
    async setIcon() {}
  },
  tabs: {
    onUpdated: { addListener() {} },
    onRemoved: { addListener() {} },
    onReplaced: { addListener() {} },
    async query() {
      return queriedTabs;
    },
    async get(tabId) {
      return queriedTabs.find((t) => t.id === tabId) || null;
    },
    async update(tabId, updateProps) {
      updatedTabs.push({ tabId, ...updateProps });
      const found = queriedTabs.find((t) => t.id === tabId);
      if (found) {
        if ("muted" in updateProps) {
          found.mutedInfo = { muted: updateProps.muted };
        }
      }
      return found;
    }
  },
  runtime: {
    onMessage: { addListener() {} },
    onInstalled: { addListener() {} },
    onStartup: { addListener() {} }
  },
  storage: {
    session: {
      async get() { return {}; },
      async set() {}
    },
    local: {
      async get(keys) {
        const result = {};
        for (const k of keys) {
          if (k in localStorageData) result[k] = localStorageData[k];
        }
        return result;
      },
      async set(items) {
        Object.assign(localStorageData, items);
      }
    },
    onChanged: { addListener() {} }
  },
  alarms: {
    onAlarm: { addListener() {} }
  },
  scripting: {
    async getRegisteredContentScripts() { return []; },
    async registerContentScripts() {},
    async updateContentScripts() {},
    async unregisterContentScripts() {}
  },
  declarativeNetRequest: {
    onRuleMatchedDebug: { addListener() {} },
    async updateEnabledRulesets() {},
    async getDynamicRules() { return []; },
    async updateDynamicRules() {}
  },
  webRequest: {
    onErrorOccurred: { addListener() {} }
  },
  permissions: {
    contains(details, cb) { cb(false); }
  }
};

globalThis.FilterEngine = {
  ensureDefaults: async () => {},
  rebuildDynamicRules: async () => {},
  refreshDueLists: async () => {},
  handleMessage: async () => ({ success: true }),
  addStats: async () => {},
  setDebugEnabled: () => {}
};

const bg = require("../../../desktop/background.js");

(async () => {
  // Test 1: isDomainMuted helper
  assert.equal(bg.isDomainMuted("noisy.example.com", ["noisy.example.com"]), true);
  assert.equal(bg.isDomainMuted("sub.noisy.example.com", ["noisy.example.com"]), true);
  assert.equal(bg.isDomainMuted("notnoisy.example.com", ["noisy.example.com"]), false);
  assert.equal(bg.isDomainMuted("other.com", ["noisy.example.com"]), false);
  assert.equal(bg.isDomainMuted("NOISY.EXAMPLE.COM", ["noisy.example.com"]), true);
  assert.equal(bg.isDomainMuted("noisy.example.com", ["NOISY.EXAMPLE.COM"]), true);
  assert.equal(bg.isDomainMuted("", ["noisy.example.com"]), false);
  assert.equal(bg.isDomainMuted("noisy.example.com", []), false);
  assert.equal(bg.isDomainMuted(null, ["noisy.example.com"]), false);

  // Test 2: applyTabMute on matching domain
  updatedTabs.length = 0;
  await bg.applyTabMute(1, "https://noisy.example.com/video");
  assert.equal(updatedTabs.length, 1);
  assert.deepEqual(updatedTabs[0], { tabId: 1, muted: true });

  // Test 3: applyTabMute on non-matching domain
  updatedTabs.length = 0;
  await bg.applyTabMute(2, "https://clean.example.com/page");
  assert.equal(updatedTabs.length, 0);

  // Test 4: applyTabMute on non-web protocol
  updatedTabs.length = 0;
  await bg.applyTabMute(3, "chrome://settings");
  assert.equal(updatedTabs.length, 0);

  // Reset tab 1 to unmuted before Test 5
  queriedTabs[0].mutedInfo = { muted: false };

  // Test 5: applyAllMutedSites
  updatedTabs.length = 0;
  await bg.applyAllMutedSites();
  // Tab 1 (noisy.example.com) and Tab 4 (sub.domain.noisy.example.com) should be muted.
  // Tab 2 was muted, but is silent.example.com (not in mutedSites), so should be unmuted.
  const mutedTabIds = updatedTabs.filter((u) => u.muted === true).map((u) => u.tabId);
  const unmutedTabIds = updatedTabs.filter((u) => u.muted === false).map((u) => u.tabId);
  assert.ok(mutedTabIds.includes(1), "Tab 1 should be muted");
  assert.ok(mutedTabIds.includes(4), "Tab 4 should be muted");
  assert.ok(unmutedTabIds.includes(2), "Tab 2 should be unmuted");

  // Test 6: Removing domain from mutedSites and applying
  localStorageData.mutedSites = [];
  updatedTabs.length = 0;
  await bg.applyAllMutedSites();
  // Now Tab 1 and Tab 4 should be unmuted
  const newlyUnmuted = updatedTabs.filter((u) => u.muted === false).map((u) => u.tabId);
  assert.ok(newlyUnmuted.includes(1), "Tab 1 should be unmuted after removing domain");
  assert.ok(newlyUnmuted.includes(4), "Tab 4 should be unmuted after removing domain");

  console.log("Muted sites unit tests passed successfully.");
})();

