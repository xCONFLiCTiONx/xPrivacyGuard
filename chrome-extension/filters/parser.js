(function (root) {
  "use strict";

  const RESOURCE_TYPES = {
    script: ["script"],
    image: ["image"],
    stylesheet: ["stylesheet"],
    font: ["font"],
    media: ["media"],
    object: ["object"],
    xmlhttprequest: ["xmlhttprequest"],
    xhr: ["xmlhttprequest"],
    fetch: ["xmlhttprequest"],
    websocket: ["websocket"],
    document: ["main_frame"],
    subdocument: ["sub_frame"],
    ping: ["ping"],
    other: ["other"]
  };
  const ALL_RESOURCE_TYPES = Array.from(new Set([...Object.values(RESOURCE_TYPES).flat(), "csp_report"]));
  const SCRIPTLET_NAMES = new Set(["remove-class", "remove-node-text"]);
  function parseArguments(input) {
    const args = [];
    let current = "";
    let quote = "";
    let escaped = false;

    for (const char of input) {
      if (escaped) {
        current += char;
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (quote) {
        if (char === quote) quote = "";
        else current += char;
      } else if (char === "'" || char === '"') {
        quote = char;
      } else if (char === ",") {
        args.push(current.trim());
        current = "";
      } else {
        current += char;
      }
    }
    if (quote || escaped) return null;
    args.push(current.trim());
    return args;
  }

  function parseDomains(value) {
    const include = [];
    const exclude = [];
    for (const raw of value.split("|")) {
      const isExcluded = raw.startsWith("~");
      const domain = (isExcluded ? raw.slice(1) : raw).trim().toLowerCase();
      if (!/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$/.test(domain)) {
        return null;
      }
      (isExcluded ? exclude : include).push(domain);
    }
    return { include: Array.from(new Set(include)), exclude: Array.from(new Set(exclude)) };
  }

  function parseNetworkRule(line, exception) {
    const separator = line.lastIndexOf("$");
    const pattern = (separator < 0 ? line : line.slice(0, separator)).trim();
    if (!pattern || pattern.length > 2000 || /[\s<>{}]/.test(pattern)) {
      return { type: "unsupported", reason: "Empty patterns or unsafe characters are not supported." };
    }

    const isRegex = pattern.startsWith("/") && pattern.endsWith("/") && pattern.length >= 2;
    const rule = {
      type: "network",
      exception,
      ...(isRegex ? { regex: pattern.slice(1, -1) } : { pattern }),
      resourceTypes: [],
      excludedResourceTypes: [],
      domain: null,
      denyallow: [],
      domainType: null,
      important: false
    };
    if (separator < 0) return rule;

    const options = line.slice(separator + 1).split(",");
    for (const rawOption of options) {
      const option = rawOption.trim().toLowerCase();
      if (!option) return { type: "unsupported", reason: "Empty network option." };
      if (option === "important") {
        rule.important = true;
        continue;
      }
      if (option.startsWith("redirect=")) {
        return { type: "unsupported", reason: "Redirect rules are not supported; media must never be replaced with placeholder content." };
      }
      if (option === "third-party" || option === "~third-party") {
        const value = option === "third-party" ? "thirdParty" : "firstParty";
        if (rule.domainType && rule.domainType !== value) {
          return { type: "unsupported", reason: "Conflicting first-party options." };
        }
        rule.domainType = value;
        continue;
      }
      if (option.startsWith("domain=")) {
        rule.domain = parseDomains(option.slice(7));
        if (!rule.domain || (!rule.domain.include.length && !rule.domain.exclude.length)) {
          return { type: "unsupported", reason: "Invalid domain option." };
        }
        continue;
      }
      if (option.startsWith("denyallow=")) {
        const domains = parseDomains(option.slice(10));
        if (!domains || domains.exclude.length || !domains.include.length) {
          return { type: "unsupported", reason: "Invalid denyallow option." };
        }
        rule.denyallow.push(...domains.include);
        continue;
      }
      if (Object.prototype.hasOwnProperty.call(RESOURCE_TYPES, option.replace(/^~/, ""))) {
        const isExcluded = option.startsWith("~");
        const types = RESOURCE_TYPES[isExcluded ? option.slice(1) : option];
        (isExcluded ? rule.excludedResourceTypes : rule.resourceTypes).push(...types);
        continue;
      }
      return { type: "unsupported", reason: `Unsupported network option: ${option}` };
    }

    rule.resourceTypes = Array.from(new Set(rule.resourceTypes));
    rule.excludedResourceTypes = Array.from(new Set(rule.excludedResourceTypes));
    rule.denyallow = Array.from(new Set(rule.denyallow));
    if (rule.resourceTypes.length && rule.excludedResourceTypes.length) {
      rule.resourceTypes = rule.resourceTypes.filter((type) => !rule.excludedResourceTypes.includes(type));
      rule.excludedResourceTypes = [];
      if (!rule.resourceTypes.length) {
        return { type: "unsupported", reason: "Conflicting resource-type options." };
      }
    } else if (rule.excludedResourceTypes.length) {
      rule.resourceTypes = ALL_RESOURCE_TYPES.filter((type) => !rule.excludedResourceTypes.includes(type));
      rule.excludedResourceTypes = [];
    }
    if (!rule.resourceTypes.length) delete rule.resourceTypes;
    if (!rule.excludedResourceTypes.length) delete rule.excludedResourceTypes;
    if (!rule.denyallow.length) delete rule.denyallow;
    return rule;
  }

  function parseRule(rawLine) {
    const line = rawLine.trim().replace(/^\uFEFF/, "");
    if (!line || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@#")) return null;

    const cosmetic = line.match(/^(.*?)((?:#@#)|##)(.*)$/);
    if (cosmetic) {
      const hosts = cosmetic[1] ? parseDomains(cosmetic[1]) : { include: [], exclude: [] };
      if (!hosts) {
        return { type: "unsupported", reason: "Invalid cosmetic domain list." };
      }
      const selector = cosmetic[3].trim();
      const scriptlet = selector.match(/^\+js\((.*)\)$/);
      if (scriptlet) {
        const args = parseArguments(scriptlet[1]);
        if (!args || !SCRIPTLET_NAMES.has(args[0])) {
          return { type: "unsupported", reason: "Unsupported or malformed scriptlet." };
        }
        if (args.length !== 3 || args.slice(1).some((arg) => !arg || arg.length > 512)) {
          return { type: "unsupported", reason: "Invalid scriptlet argument count." };
        }
        return {
          type: "scriptlet",
          name: args[0],
          args: args.slice(1),
          includeDomains: hosts.include,
          excludeDomains: hosts.exclude
        };
      }
      if (!selector || selector.length > 2048 || /[{};<@]/.test(selector)) {
        return { type: "unsupported", reason: "Unsafe or empty cosmetic selector." };
      }
      return {
        type: cosmetic[2] === "#@#" ? "cosmeticException" : "cosmetic",
        selector,
        includeDomains: hosts.include,
        excludeDomains: hosts.exclude
      };
    }

    if (line.includes("#?#") || line.includes("#$#") || line.includes("#%#")) {
      return { type: "unsupported", reason: "Unsupported procedural cosmetic syntax." };
    }
    return parseNetworkRule(line.startsWith("@@") ? line.slice(2) : line, line.startsWith("@@"));
  }

  function getRuleKey(rule) {
    if (rule.type === "network") {
      return `n|${rule.exception ? 1 : 0}|${rule.pattern || rule.regex || ""}|${rule.domainType || ""}|${rule.important ? 1 : 0}|${rule.redirect || ""}|${rule.resourceTypes ? rule.resourceTypes.join(",") : ""}`;
    }
    if (rule.type === "cosmetic" || rule.type === "cosmeticException") {
      return `c|${rule.type}|${rule.selector}|${rule.includeDomains.join(",")}|${rule.excludeDomains.join(",")}`;
    }
    if (rule.type === "scriptlet") {
      return `s|${rule.name}|${rule.args.join(",")}|${rule.includeDomains.join(",")}|${rule.excludeDomains.join(",")}`;
    }
    return `${rule.type}|${rule.pattern || ""}`;
  }

  function parseFilterList(text) {
    if (typeof text !== "string") throw new TypeError("Filter list must be text.");
    const parsed = {
      network: [],
      cosmetic: [],
      cosmeticExceptions: [],
      scriptlets: [],
      cosmeticIndex: {},
      cosmeticExceptionIndex: {},
      scriptletIndex: {},
      unsupported: 0
    };
    const seen = new Set();
    const errors = [];
    for (const [index, line] of text.split(/\r?\n/).entries()) {
      const rule = parseRule(line);
      if (!rule) continue;
      if (rule.type === "unsupported") {
        parsed.unsupported += 1;
        if (errors.length < 100) errors.push({ line: index + 1, reason: rule.reason });
        continue;
      }
      const key = getRuleKey(rule);
      if (seen.has(key)) continue;
      seen.add(key);
      if (rule.type === "network") {
        if (parsed.network.length < 30000) parsed.network.push(rule);
      }
      else if (rule.type === "cosmetic") parsed.cosmetic.push(rule);
      else if (rule.type === "cosmeticException") parsed.cosmeticExceptions.push(rule);
      else parsed.scriptlets.push(rule);
    }
    for (const [rules, index] of [
      [parsed.cosmetic, parsed.cosmeticIndex],
      [parsed.cosmeticExceptions, parsed.cosmeticExceptionIndex],
      [parsed.scriptlets, parsed.scriptletIndex]
    ]) {
      for (const rule of rules) {
        const domains = rule.includeDomains.length ? rule.includeDomains : [""];
        for (const domain of domains) {
          if (!index[domain]) index[domain] = [];
          index[domain].push(rule);
        }
      }
    }
    return { parsed, errors };
  }

  root.FilterParser = { parseArguments, parseRule, parseFilterList };
})(globalThis);
