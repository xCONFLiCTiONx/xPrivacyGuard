"use strict";

const assert = require("node:assert/strict");

// 1. Test Settings Export & Import helpers
const settingsModule = require("../../settings.js");
const { cleanDomain, buildSettingsExportPayload, parseAndValidateImportSettings } = settingsModule;

// Test cleanDomain
assert.equal(cleanDomain("https://www.example.com/path?query=1#hash"), "example.com");
assert.equal(cleanDomain("HTTP://Sub.Example.Com:8080/"), "sub.example.com");
assert.equal(cleanDomain("   example.org/   "), "example.org");

// Test buildSettingsExportPayload
const sampleStorageData = {
  mode: "cautious",
  enableGPC: true,
  excludedDomains: ["excluded.com"],
  mutedSites: ["noisy.com"],
  filterDisabledDomains: ["unfiltered.com"],
  filterLists: [{ id: "l1", name: "Custom List", url: "https://example.com/list.txt", enabled: true }],
  customFilterRules: [{ id: "r1", rule: "||ads.test^", enabled: true }],
  filterUpdateHours: 48,
  disableFilterStats: false,
  filterDebug: true
};

const payload = buildSettingsExportPayload(sampleStorageData);
assert.equal(payload.version, 1);
assert.ok(payload.exportedAt);
assert.equal(payload.settings.mode, "cautious");
assert.equal(payload.settings.enableGPC, true);
assert.deepEqual(payload.settings.excludedDomains, ["excluded.com"]);
assert.deepEqual(payload.settings.mutedSites, ["noisy.com"]);
assert.deepEqual(payload.settings.filterDisabledDomains, ["unfiltered.com"]);
assert.equal(payload.settings.filterUpdateHours, 48);
assert.equal(payload.settings.filterDebug, true);
assert.equal(payload.settings.disableFilterStats, false);
assert.equal(payload.settings.filterLists.length, 1);
assert.equal(payload.settings.customFilterRules.length, 1);

// Test parseAndValidateImportSettings with nested format
const importedNested = parseAndValidateImportSettings(payload);
assert.equal(importedNested.mode, "cautious");
assert.equal(importedNested.enableGPC, true);
assert.deepEqual(importedNested.excludedDomains, ["excluded.com"]);
assert.deepEqual(importedNested.mutedSites, ["noisy.com"]);

// Test parseAndValidateImportSettings with flat format and domain cleaning
const flatPayload = {
  mode: "daily",
  enableGPC: false,
  excludedDomains: ["https://www.site1.com/abc", "site2.com"],
  mutedSites: ["https://video.com/player"],
  filterUpdateHours: "24",
  disableFilterStats: true
};
const importedFlat = parseAndValidateImportSettings(flatPayload);
assert.equal(importedFlat.mode, "daily");
assert.equal(importedFlat.enableGPC, false);
assert.deepEqual(importedFlat.excludedDomains, ["site1.com", "site2.com"]);
assert.deepEqual(importedFlat.mutedSites, ["video.com"]);
assert.equal(importedFlat.filterUpdateHours, 24);
assert.equal(importedFlat.disableFilterStats, true);

// Test parseAndValidateImportSettings rejection
assert.throws(() => parseAndValidateImportSettings(null), /Invalid settings/);
assert.throws(() => parseAndValidateImportSettings({}), /No recognizable settings/);

console.log("Settings export and import unit tests passed.");

// 2. Test GPC synchronization in background.js
let enabledRulesets = [];
let disabledRulesets = [];
let registeredScripts = [];
let unregisteredScriptIds = [];

const localStorageData = {
  enableGPC: true,
  excludedDomains: ["excluded-from-fingerprint.com", "another-excluded.org"]
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
    async query() { return []; },
    async get() { return null; }
  },
  runtime: {
    onMessage: { addListener() {} },
    onInstalled: { addListener() {} },
    onStartup: { addListener() {} }
  },
  storage: {
    local: {
      async get(keys) {
        const res = {};
        for (const k of keys) {
          if (k in localStorageData) res[k] = localStorageData[k];
        }
        return res;
      },
      async set(items) {
        Object.assign(localStorageData, items);
      }
    },
    session: {
      async get() { return {}; },
      async set() {}
    },
    onChanged: { addListener() {} }
  },
  alarms: {
    onAlarm: { addListener() {} },
    async get() { return null; },
    async create() {}
  },
  scripting: {
    async getRegisteredContentScripts({ ids }) {
      return registeredScripts.filter((s) => ids.includes(s.id));
    },
    async registerContentScripts(scripts) {
      registeredScripts.push(...scripts);
    },
    async updateContentScripts(scripts) {
      for (const s of scripts) {
        const idx = registeredScripts.findIndex((r) => r.id === s.id);
        if (idx >= 0) registeredScripts[idx] = s;
        else registeredScripts.push(s);
      }
    },
    async unregisterContentScripts({ ids }) {
      unregisteredScriptIds.push(...ids);
      registeredScripts = registeredScripts.filter((s) => !ids.includes(s.id));
    }
  },
  declarativeNetRequest: {
    onRuleMatchedDebug: { addListener() {} },
    async updateEnabledRulesets(options) {
      if (options.enableRulesetIds) enabledRulesets.push(...options.enableRulesetIds);
      if (options.disableRulesetIds) disabledRulesets.push(...options.disableRulesetIds);
    },
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
  // Test GPC enabled: gpc-main-world content script should be registered WITHOUT excludeMatches,
  // regardless of excludedDomains in storage!
  await bg.syncGpcState(true);

  assert.ok(enabledRulesets.includes("gpc_rules"), "gpc_rules should be enabled in declarativeNetRequest");
  const gpcScript = registeredScripts.find((s) => s.id === "gpc-main-world");
  assert.ok(gpcScript, "gpc-main-world script should be registered");
  assert.deepEqual(gpcScript.matches, ["<all_urls>", "file:///*"]);
  assert.deepEqual(gpcScript.js, ["gpc.js"]);
  assert.equal(gpcScript.excludeMatches, undefined, "gpc-main-world must NOT have excludeMatches even when sites are excluded");

  // Test GPC disabled
  await bg.syncGpcState(false);
  assert.ok(disabledRulesets.includes("gpc_rules"), "gpc_rules should be disabled");
  assert.ok(unregisteredScriptIds.includes("gpc-main-world"), "gpc-main-world should be unregistered");

  console.log("GPC state synchronization unit tests passed successfully.");
})().catch((err) => {
  console.error("Test failed:", err);
  process.exit(1);
});

