"use strict";

const assert = require("node:assert/strict");
require("../parser.js");
require("../network.js");
require("../procedural.js");
require("../defaults.js");
require("../engine.js");

const data = {
  filterLists: [{ id: "fixture", enabled: true }],
  filterDisabledDomains: ["paused.example"],
  excludedDomains: [],
  customFilterRules: []
};
const parsed = globalThis.FilterParser.parseFilterList([
  "||ads.test^",
  "@@||ads.test/allowed^",
  "||critical.test^$important",
  "@@||critical.test^",
  "site.test##.ad",
  "site.test#@#.ad",
  "site.test##.sponsored"
].join("\n")).parsed;

globalThis.chrome = {
  storage: {
    local: {
      async get(keys) {
        return Object.fromEntries(keys.filter((key) => key in data).map((key) => [key, data[key]]));
      },
      async set(values) {
        Object.assign(data, values);
      }
    }
  },
  declarativeNetRequest: {
    rules: [],
    updates: 0,
    async getDynamicRules() {
      return this.rules;
    },
    async updateDynamicRules({ removeRuleIds, addRules }) {
      this.updates += 1;
      this.rules = this.rules.filter((rule) => !removeRuleIds.includes(rule.id)).concat(addRules);
    }
  }
};
globalThis.FilterStorage = {
  async get(id) {
    return id === "fixture" ? { id, parsed } : undefined;
  },
  async getAll() {
    return [{ id: "fixture", parsed }];
  },
  async remove() {
    return undefined;
  }
};

(async () => {
  await Promise.all([
    globalThis.FilterEngine.rebuildDynamicRules(),
    globalThis.FilterEngine.rebuildDynamicRules()
  ]);
  const { rules } = globalThis.chrome.declarativeNetRequest;
  assert.equal(globalThis.chrome.declarativeNetRequest.updates, 2);
  assert.equal(rules.length, 6);
  assert.equal(rules.find((rule) => rule.condition.urlFilter === "||ads.test^").priority, 100);
  assert.equal(rules.find((rule) => rule.condition.urlFilter === "||ads.test/allowed^").priority, 150);
  assert.equal(rules.find((rule) => rule.condition.urlFilter === "||critical.test^" && rule.action.type === "block").priority, 200);
  assert.equal(rules.find((rule) => rule.condition.urlFilter === "||critical.test^" && rule.action.type === "allow").priority, 150);
  assert.deepEqual(rules.find((rule) => rule.id === 1000000).condition.requestDomains, ["paused.example"]);
  const freewheelRule = rules.find((rule) => rule.condition.urlFilter === "||fwmrm.net^");
  assert.deepEqual(freewheelRule.condition.initiatorDomains, ["peacocktv.com"]);
  assert.deepEqual(freewheelRule.condition.resourceTypes, ["xmlhttprequest", "other"]);
  assert.equal(rules.some((rule) => rule.condition.resourceTypes && rule.condition.resourceTypes.length === 0), false);

  const page = await globalThis.FilterEngine.getPageFilters("https://www.site.test/");
  assert.deepEqual(page.selectors, [".sponsored"]);
  assert.deepEqual(
    (await globalThis.FilterEngine.getPageFilters("https://www.peacocktv.com/watch/")).scriptlets,
    [{ name: "skip-video-ads", args: [] }]
  );
  const disabled = await globalThis.FilterEngine.getPageFilters("https://paused.example/");
  assert.deepEqual(disabled.selectors, []);
  assert.deepEqual(disabled.scriptlets, []);
  data.filterDisabledDomains.push("peacocktv.com");
  assert.deepEqual(
    (await globalThis.FilterEngine.getPageFilters("https://www.peacocktv.com/watch/")).scriptlets,
    []
  );
  await globalThis.FilterEngine.rebuildDynamicRules();
  assert.deepEqual(
    globalThis.chrome.declarativeNetRequest.rules
      .find((rule) => rule.condition.urlFilter === "||fwmrm.net^")
      .condition.excludedInitiatorDomains,
    ["peacocktv.com", "paused.example"]
  );
  data.filterDisabledDomains.pop();
  assert.deepEqual(
    await globalThis.FilterEngine.handleMessage({
      action: "toggleFilterSite",
      hostname: "video.paused.example",
      enabled: true
    }, {}),
    { success: true, disabledDomains: [] }
  );

  assert.deepEqual(await globalThis.FilterEngine.getPageFilters(""), {
    selectors: [],
    scriptlets: [],
    debug: false
  });
  assert.deepEqual(
    await globalThis.FilterEngine.handleMessage({ action: "getPageFilters" }, {}),
    { success: true, selectors: [], scriptlets: [], debug: false }
  );
  assert.deepEqual(
    await globalThis.FilterEngine.handleMessage({
      action: "recordFilterEvent",
      category: "blocked",
      message: "Fallback host used."
    }, {}),
    { success: true }
  );

  await globalThis.FilterEngine.handleMessage(
    { action: "setFilterDebug", enabled: true },
    {}
  );
  await globalThis.FilterEngine.addStats("blocked", "www.site.test", "Blocked fixture request.");
  const summary = await globalThis.FilterEngine.handleMessage(
    { action: "getFilterData", hostname: "www.site.test" },
    {}
  );
  assert.equal(summary.stats.blocked, 1);
  assert.equal(summary.debugLog[0].category, "blocked");

  assert.deepEqual(
    await globalThis.FilterEngine.handleMessage({ action: "setDisableFilterStats", disabled: true }, {}),
    { success: true }
  );
  await globalThis.FilterEngine.addStats("blocked", "www.site.test", "Ignored while disabled.");
  const disabledSummary = await globalThis.FilterEngine.handleMessage(
    { action: "getFilterData", hostname: "www.site.test" },
    {}
  );
  assert.equal(disabledSummary.disableStats, true);
  assert.equal(disabledSummary.stats.blocked, 1);

  assert.deepEqual(
    await globalThis.FilterEngine.handleMessage({ action: "resetFilterStats" }, {}),
    { success: true }
  );
  const resetSummary = await globalThis.FilterEngine.handleMessage(
    { action: "getFilterData", hostname: "www.site.test" },
    {}
  );
  assert.equal(resetSummary.stats.blocked, 0);

  console.log("Filter engine tests passed.");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
