"use strict";

const assert = require("node:assert/strict");
require("../parser.js");
require("../network.js");
require("../procedural.js");
require("../defaults.js");

const { parseRule, parseFilterList } = globalThis.FilterParser;

const basic = parseRule("||example.com/ads^");
assert.equal(basic.type, "network");
assert.equal(basic.exception, false);
assert.equal(basic.pattern, "||example.com/ads^");
assert.deepEqual(
  globalThis.FilterNetwork.compileNetworkRule(basic, 1, 100).condition,
  { urlFilter: "||example.com/ads^" }
);
assert.equal(parseRule("\uFEFF||example.com^").pattern, "||example.com^");

const scoped = parseRule("||example.com^$script,third-party,domain=site.com|~private.site,denyallow=allowed.example");
assert.deepEqual(scoped.resourceTypes, ["script"]);
assert.equal(scoped.domainType, "thirdParty");
assert.deepEqual(scoped.domain, {
  include: ["site.com"],
  exclude: ["private.site"]
});
assert.deepEqual(scoped.denyallow, ["allowed.example"]);
const compiled = globalThis.FilterNetwork.compileNetworkRule(scoped, 2, 100);
assert.deepEqual(compiled.condition.initiatorDomains, ["site.com"]);
assert.deepEqual(compiled.condition.excludedInitiatorDomains, ["private.site"]);
assert.deepEqual(compiled.condition.excludedRequestDomains, ["allowed.example"]);

const exception = parseRule("@@||example.com/allowed.js$script");
assert.equal(exception.exception, true);
assert.equal(
  globalThis.FilterNetwork.compileNetworkRule(exception, 3, 150).action.type,
  "allow"
);
assert.equal(parseRule("||example.com^$important").important, true);
assert.equal(parseRule("||example.com^$~script").resourceTypes.includes("script"), false);
assert.equal(parseRule("||example.com^$script,~script").type, "unsupported");
assert.equal(parseRule("||example.com^$redirect=https://other.example").type, "unsupported");
assert.throws(
  () => globalThis.FilterNetwork.compileNetworkRule({
    pattern: "||ark.tubi.video/v2/*.mp4",
    resourceTypes: ["media"],
    redirect: "/resources/noop.mp4"
  }, 99, 100),
  /Redirect rules are not supported/
);

const cosmetic = parseRule("example.com##.advertisement");
const cosmeticException = parseRule("example.com#@#.advertisement");
assert.equal(cosmetic.type, "cosmetic");
assert.equal(cosmeticException.type, "cosmeticException");
const scriptlet = parseRule("example.com##+js(remove-class, sponsored, .promo)");
assert.equal(scriptlet.type, "scriptlet");
assert.deepEqual(scriptlet.args, ["sponsored", ".promo"]);
assert.equal(parseRule("example.com##+js(remote-code, alert(1))").type, "unsupported");

const regexRule = parseRule("/^https?://example\\.com/ads/.*$/$script");
assert.equal(regexRule.type, "network");
assert.equal(regexRule.regex, "^https?://example\\.com/ads/.*$");
assert.deepEqual(
  globalThis.FilterNetwork.compileNetworkRule(regexRule, 10, 100).condition,
  { regexFilter: "^https?://example\\.com/ads/.*$", resourceTypes: ["script"] }
);

const list = parseFilterList([
  "! comment",
  "||ads.example^",
  "example.com##.ad",
  "example.com#@#.ad",
  "example.com##+js(remove-class, promo, .sponsored)",
  "||ads.example^$removeparam=utm_source"
].join("\n"));
assert.equal(list.parsed.network.length, 1);
assert.equal(list.parsed.cosmetic.length, 1);
assert.equal(list.parsed.cosmeticExceptions.length, 1);
assert.equal(list.parsed.scriptlets.length, 1);
assert.equal(list.parsed.unsupported, 1);
const pageRules = globalThis.FilterProcedural.getCosmeticRules(
  [list.parsed],
  "www.example.com"
);
assert.deepEqual(pageRules.selectors, []);
assert.equal(pageRules.scriptlets.length, 1);
assert.deepEqual(globalThis.DEFAULT_FILTER_LISTS.map((entry) => entry.name), []);

// Test large filter list parsing (50k+ rules)
const largeLines = [];
for (let i = 0; i < 55000; i++) {
  largeLines.push(`||ads${i}.example^`);
}
const largeList = parseFilterList(largeLines.join("\n"));
assert.ok(largeList.parsed.network.length <= 30000);

// Placeholder redirects can replace real media and cause streaming players to skip content.
assert.equal(
  parseRule("||ark.tubi.video/v2/*.mp4$media,redirect=noopmp4,important").type,
  "unsupported"
);
assert.equal(
  parseRule("@@||ark.tubi.video/v2/*.mp4$redirect=noopmp4").type,
  "unsupported"
);
assert.equal(
  parseRule("||ark.tubi.video/v2/*.mp4$redirect=unknown").type,
  "unsupported"
);

console.log("Filter parser tests passed.");
