"use strict";

const assert = require("node:assert/strict");

// Mock Chrome API
const badgeCalls = [];
const badgeBgCalls = [];
const badgeTextCalls = [];
const messageListeners = [];
const updatedListeners = [];
const removedListeners = [];
const rePlacedListeners = [];
const sessionStorageData = {};
const localStorageData = {};

globalThis.chrome = {
  action: {
    async setBadgeText(details) {
      badgeCalls.push(details);
    },
    async setBadgeBackgroundColor(details) {
      badgeBgCalls.push(details);
    },
    async setBadgeTextColor(details) {
      badgeTextCalls.push(details);
    },
    async setIcon() {}
  },
  tabs: {
    onUpdated: {
      addListener(fn) {
        updatedListeners.push(fn);
      }
    },
    onRemoved: {
      addListener(fn) {
        removedListeners.push(fn);
      }
    },
    onReplaced: {
      addListener(fn) {
        rePlacedListeners.push(fn);
      }
    },
    async query() {
      return [{ id: 101 }, { id: 102 }];
    }
  },
  runtime: {
    onMessage: {
      addListener(fn) {
        messageListeners.push(fn);
      }
    },
    onInstalled: {
      addListener() {}
    },
    onStartup: {
      addListener() {}
    }
  },
  storage: {
    session: {
      async get(key) {
        if (typeof key === "string") return { [key]: sessionStorageData[key] };
        return sessionStorageData;
      },
      async set(items) {
        Object.assign(sessionStorageData, items);
      }
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
    onChanged: {
      addListener() {}
    }
  },
  alarms: {
    onAlarm: { addListener() {} },
    async get() { return null; },
    async create() {}
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
  // Test 1: formatBadgeCount
  assert.equal(bg.formatBadgeCount(0), "");
  assert.equal(bg.formatBadgeCount(-1), "");
  assert.equal(bg.formatBadgeCount(1), "1");
  assert.equal(bg.formatBadgeCount(999), "999");
  assert.equal(bg.formatBadgeCount(1000), "999+");
  assert.equal(bg.formatBadgeCount(5000), "999+");

  // Test 2: initBadgeAppearance
  badgeBgCalls.length = 0;
  badgeTextCalls.length = 0;
  bg.initBadgeAppearance();
  assert.deepEqual(badgeBgCalls, [{ color: "#2563eb" }]);
  assert.deepEqual(badgeTextCalls, [{ color: "#FFFFFF" }]);

  // Test 3: Record blocked requests on Tab 1
  badgeCalls.length = 0;
  bg.clearAllTabBadges();

  bg.recordBlockedRequest(1, "req-1");
  assert.equal(bg.tabBlockCounts.get(1), 1);
  assert.deepEqual(badgeCalls[badgeCalls.length - 1], { text: "1", tabId: 1 });

  // Test 4: Deduplicate same request ID on Tab 1
  bg.recordBlockedRequest(1, "req-1");
  assert.equal(bg.tabBlockCounts.get(1), 1); // should still be 1

  // Test 5: Distinct request on Tab 1 increments
  bg.recordBlockedRequest(1, "req-2");
  assert.equal(bg.tabBlockCounts.get(1), 2);
  assert.deepEqual(badgeCalls[badgeCalls.length - 1], { text: "2", tabId: 1 });

  // Test 6: Tab 2 has independent count
  bg.recordBlockedRequest(2, "req-3");
  assert.equal(bg.tabBlockCounts.get(1), 2);
  assert.equal(bg.tabBlockCounts.get(2), 1);
  assert.deepEqual(badgeCalls[badgeCalls.length - 1], { text: "1", tabId: 2 });

  // Test 7: Ignore invalid tab IDs (e.g. -1 for background/worker requests)
  bg.recordBlockedRequest(-1, "req-4");
  assert.equal(bg.tabBlockCounts.has(-1), false);
  bg.recordBlockedRequest(0, "req-5");
  assert.equal(bg.tabBlockCounts.has(0), false);

  // Test 8: Reset tab counter on tab reload
  // Simulate tab 1 reloading: chrome.tabs.onUpdated with status: "loading"
  const onUpdated = updatedListeners[0];
  assert.ok(typeof onUpdated === "function");
  onUpdated(1, { status: "loading" }, {});
  assert.equal(bg.tabBlockCounts.has(1), false);
  assert.deepEqual(badgeCalls[badgeCalls.length - 1], { text: "", tabId: 1 });
  // Tab 2 count should remain untouched
  assert.equal(bg.tabBlockCounts.get(2), 1);

  // Test 9: Same request ID can be counted again after tab reload
  bg.recordBlockedRequest(1, "req-1");
  assert.equal(bg.tabBlockCounts.get(1), 1);
  assert.deepEqual(badgeCalls[badgeCalls.length - 1], { text: "1", tabId: 1 });

  // Test 10: Message action "getTabBlockCount"
  let responseData = null;
  const messageHandler = messageListeners[0];
  messageHandler({ action: "getTabBlockCount", tabId: 1 }, {}, (res) => {
    responseData = res;
  });
  assert.deepEqual(responseData, { success: true, count: 1 });

  messageHandler({ action: "getTabBlockCount", tabId: 99 }, {}, (res) => {
    responseData = res;
  });
  assert.deepEqual(responseData, { success: true, count: 0 });

  // Test 11: Cleanup on tab close
  const onRemoved = removedListeners[0];
  onRemoved(1);
  assert.equal(bg.tabBlockCounts.has(1), false);

  // Test 12: Clear all badges on resetFilterStats
  messageHandler({ action: "resetFilterStats" }, {}, () => {});
  assert.equal(bg.tabBlockCounts.size, 0);

  console.log("Badge counter unit tests passed successfully.");
})().catch((err) => {
  console.error("Test failed:", err);
  process.exit(1);
});

