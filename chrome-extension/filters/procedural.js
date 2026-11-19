(function (root) {
  "use strict";

  function domainMatches(hostname, includeDomains, excludeDomains) {
    const host = String(hostname || "").toLowerCase();
    if (excludeDomains.some((domain) => host === domain || host.endsWith(`.${domain}`))) return false;
    return includeDomains.length === 0 || includeDomains.some((domain) => (
      host === domain || host.endsWith(`.${domain}`)
    ));
  }

  function getCosmeticRules(parsedLists, hostname, customRules) {
    const cosmetic = [];
    const exceptions = new Set();
    const scriptlets = new Map();
    const all = [...parsedLists, ...(customRules ? [customRules] : [])];
    const hostParts = String(hostname || "").toLowerCase().split(".");
    const candidateDomains = [""];
    while (hostParts.length > 1) {
      candidateDomains.push(hostParts.join("."));
      hostParts.shift();
    }

    function indexedRules(parsed, indexName, legacyName) {
      const index = parsed[indexName];
      if (!index) return parsed[legacyName] || [];
      const found = new Set();
      for (const domain of candidateDomains) {
        for (const rule of index[domain] || []) found.add(rule);
      }
      return found;
    }

    for (const parsed of all) {
      for (const rule of indexedRules(parsed, "cosmeticExceptionIndex", "cosmeticExceptions")) {
        if (domainMatches(hostname, rule.includeDomains, rule.excludeDomains)) exceptions.add(rule.selector);
      }
      for (const rule of indexedRules(parsed, "cosmeticIndex", "cosmetic")) {
        if (domainMatches(hostname, rule.includeDomains, rule.excludeDomains)) cosmetic.push(rule.selector);
      }
      for (const rule of indexedRules(parsed, "scriptletIndex", "scriptlets")) {
        if (domainMatches(hostname, rule.includeDomains, rule.excludeDomains)) {
          scriptlets.set(JSON.stringify(rule), rule);
        }
      }
    }
    return {
      selectors: Array.from(new Set(cosmetic.filter((selector) => !exceptions.has(selector)))).slice(0, 5000),
      scriptlets: Array.from(scriptlets.values()).slice(0, 50)
    };
  }

  root.FilterProcedural = { domainMatches, getCosmeticRules };
})(globalThis);
