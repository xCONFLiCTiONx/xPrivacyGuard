(function (root) {
  "use strict";

  const DEBUG_LOG_LIMIT = 200;
  const STATS_HOST_LIMIT = 100;
  const FAILED_UPDATE_RETRY_HOURS = 6;
  const VALID_EVENT_CATEGORIES = new Set(["blocked", "allowed", "cosmetic", "scriptlet", "unsupported"]);
  let rebuildQueue = Promise.resolve();
  let parsedListsCache = null;
  let parsedListsCacheKey = "";
  let filterDebugEnabled = false;
  let pendingStats = Object.create(null);
  let pendingLogs = [];
  let statsFlushQueue = Promise.resolve();
  let statsFlushTimer = null;
  let recentBlockedRequests = new Map();

  function isDuplicateBlock(host, message) {
    const key = `${host}:${message}`;
    const now = Date.now();
    if (recentBlockedRequests.has(key)) {
      const time = recentBlockedRequests.get(key);
      if (now - time < 2000) return true;
    }
    recentBlockedRequests.set(key, now);
    if (recentBlockedRequests.size > 500) {
      const oldestKey = recentBlockedRequests.keys().next().value;
      recentBlockedRequests.delete(oldestKey);
    }
    return false;
  }

  async function getSettings() {
    return chrome.storage.local.get([
      "excludedDomains",
      "filterLists",
      "customFilterRules",
      "filterDisabledDomains",
      "filterDebug",
      "filterUpdateHours",
      "disableFilterStats"
    ]);
  }

  async function saveMetadata(filterLists) {
    await chrome.storage.local.set({ filterLists });
  }

  function recordDebug(category, message, hostname, tabId) {
    if (!filterDebugEnabled) return;
    pendingLogs.unshift({
      time: Date.now(),
      category,
      hostname: String(hostname || "").slice(0, 253),
      message: String(message || "").slice(0, 500),
      tabId: typeof tabId === "number" ? tabId : null
    });
    pendingLogs = pendingLogs.slice(0, DEBUG_LOG_LIMIT);
    scheduleStatsFlush();
  }

  function scheduleStatsFlush() {
    if (statsFlushTimer !== null) return;
    statsFlushTimer = setTimeout(() => {
      statsFlushTimer = null;
      flushStats().catch((error) => console.error("xPrivacyGuard: Could not persist filter statistics:", error));
    }, 1000);
  }

  function flushStats() {
    const statsBatch = pendingStats;
    const logBatch = pendingLogs;
    pendingStats = Object.create(null);
    pendingLogs = [];
    if (!Object.keys(statsBatch).length && !logBatch.length) return statsFlushQueue;

    statsFlushQueue = statsFlushQueue.catch(() => {}).then(async () => {
      try {
        const current = await chrome.storage.local.get(["filterStats", "filterDebugLog"]);
        const stats = Object.assign(
          Object.create(null),
          current.filterStats && typeof current.filterStats === "object" ? current.filterStats : {}
        );
        for (const [host, increments] of Object.entries(statsBatch)) {
          if (!Object.prototype.hasOwnProperty.call(stats, host)) {
            while (Object.keys(stats).length >= STATS_HOST_LIMIT) delete stats[Object.keys(stats)[0]];
            stats[host] = { blocked: 0, allowed: 0, cosmetic: 0, scriptlet: 0 };
          }
          for (const category of ["blocked", "allowed", "cosmetic", "scriptlet"]) {
            stats[host][category] = (stats[host][category] || 0) + (increments[category] || 0);
          }
        }
        const values = { filterStats: stats };
        if (logBatch.length) {
          const logs = Array.isArray(current.filterDebugLog) ? current.filterDebugLog : [];
          values.filterDebugLog = [...logBatch, ...logs].slice(0, DEBUG_LOG_LIMIT);
        }
        await chrome.storage.local.set(values);
      } catch (error) {
        for (const [host, increments] of Object.entries(statsBatch)) {
          const target = pendingStats[host] || (pendingStats[host] = {
            blocked: 0,
            allowed: 0,
            cosmetic: 0,
            scriptlet: 0
          });
          for (const category of ["blocked", "allowed", "cosmetic", "scriptlet"]) {
            target[category] += increments[category] || 0;
          }
        }
        pendingLogs = [...logBatch, ...pendingLogs].slice(0, DEBUG_LOG_LIMIT);
        scheduleStatsFlush();
        throw error;
      }
    });
    return statsFlushQueue;
  }

  function isDisabled(hostname, domains) {
    const host = String(hostname || "").toLowerCase();
    return (Array.isArray(domains) ? domains : []).some((domain) => {
      if (typeof domain !== "string") return false;
      const value = domain.toLowerCase();
      return host === value || host.endsWith(`.${value}`);
    });
  }

  async function ensureDefaults() {
    const data = await getSettings();
    filterDebugEnabled = data.filterDebug === true;
    let lists = Array.isArray(data.filterLists) ? data.filterLists : null;
    const defaultIds = new Set(["easylist", "easyprivacy", "ublock-filters", "ublock-privacy"]);
    if (lists === null) {
      lists = root.DEFAULT_FILTER_LISTS.map((list) => ({
        ...list,
        ruleCount: 0,
        lastUpdated: null,
        lastSuccess: null,
        lastAttempt: null,
        lastError: null
      }));
      await saveMetadata(lists);
    } else {
      const cleaned = lists.filter((list) => !defaultIds.has(list.id) && !list.isDefault);
      if (cleaned.length !== lists.length) {
        for (const list of lists) {
          if (defaultIds.has(list.id) || list.isDefault) {
            try {
              await root.FilterStorage.remove(list.id);
            } catch (e) {}
          }
        }
        lists = cleaned;
        await saveMetadata(lists);
        parsedListsCache = null;
        await rebuildDynamicRules();
      }
    }
    const alarm = await chrome.alarms.get(root.FILTER_UPDATE_ALARM);
    if (!alarm) {
      await chrome.alarms.create(root.FILTER_UPDATE_ALARM, {
        periodInMinutes: (Number(data.filterUpdateHours) || root.FILTER_UPDATE_HOURS) * 60
      });
    }
  }

  async function refreshList(listId) {
    const data = await getSettings();
    const lists = Array.isArray(data.filterLists) ? data.filterLists : [];
    const list = lists.find((item) => item.id === listId);
    if (!list) throw new Error("Filter list was not found.");

    const previousRecord = await root.FilterStorage.get(list.id);
    try {
      const text = await root.FilterLists.downloadList(list.url);
      const result = root.FilterParser.parseFilterList(text);
      const now = Date.now();
      list.lastAttempt = now;
      await saveMetadata(lists);
      await root.FilterStorage.put({ id: list.id, parsed: result.parsed, updatedAt: now });
      parsedListsCache = null;
      await rebuildDynamicRules();
      list.ruleCount = result.parsed.network.length
        + result.parsed.cosmetic.length
        + result.parsed.cosmeticExceptions.length
        + result.parsed.scriptlets.length;
      list.lastUpdated = now;
      list.lastSuccess = now;
      list.lastError = null;
      await saveMetadata(lists);
      if (result.errors.length) {
        await recordDebug("unsupported", `${list.name} has ${result.parsed.unsupported} unsupported rules. First: line ${result.errors[0].line}: ${result.errors[0].reason}`);
      }
      return list;
    } catch (error) {
      try {
        if (previousRecord) await root.FilterStorage.put(previousRecord);
        else await root.FilterStorage.remove(list.id);
        parsedListsCache = null;
        await rebuildDynamicRules();
      } catch (rollbackError) {
        console.error("xPrivacyGuard: Could not restore the previous filter-list rules:", rollbackError);
      }
      list.lastError = error instanceof Error ? error.message : String(error);
      list.lastAttempt = Date.now();
      await saveMetadata(lists);
      await recordDebug("unsupported", `Could not update ${list.name}: ${list.lastError}`);
      throw error;
    }
  }

  async function refreshDueLists() {
    const data = await getSettings();
    const lists = Array.isArray(data.filterLists) ? data.filterLists : [];
    const now = Date.now();
    const interval = (Number(data.filterUpdateHours) || root.FILTER_UPDATE_HOURS) * 60 * 60 * 1000;
    for (const list of lists) {
      if (list.enabled === false) continue;
      if (list.lastSuccess && now - list.lastSuccess < interval) continue;
      if (list.lastError && list.lastAttempt
        && now - list.lastAttempt < FAILED_UPDATE_RETRY_HOURS * 60 * 60 * 1000) continue;
      try {
        await refreshList(list.id);
      } catch (error) {
        console.error(`xPrivacyGuard: Filter list update failed (${list.name}):`, error);
      }
    }
  }

  async function parseCustomRules(customRules) {
    const lines = (Array.isArray(customRules) ? customRules : [])
      .filter((rule) => rule && rule.enabled !== false && typeof rule.rule === "string")
      .map((rule) => rule.rule);
    const result = root.FilterParser.parseFilterList(lines.join("\n"));
    return result.parsed;
  }

  async function getEnabledParsedLists(settings) {
    const ids = (Array.isArray(settings.filterLists) ? settings.filterLists : [])
      .filter((list) => list.enabled !== false)
      .map((list) => list.id)
      .filter((id) => typeof id === "string")
      .sort();
    const key = ids.join("\n");
    if (parsedListsCache && parsedListsCacheKey === key) return parsedListsCache;
    const records = await Promise.all(ids.map((id) => root.FilterStorage.get(id)));
    parsedListsCache = records.filter((record) => record && record.parsed).map((record) => record.parsed);
    parsedListsCacheKey = key;
    return parsedListsCache;
  }

  function rebuildDynamicRules() {
    rebuildQueue = rebuildQueue.catch(() => {}).then(doRebuildDynamicRules);
    return rebuildQueue;
  }

  async function doRebuildDynamicRules() {
    const settings = await getSettings();
    const parsedLists = await getEnabledParsedLists(settings);
    const excludedData = await chrome.storage.local.get(["excludedDomains", "filterDisabledDomains"]);
    const siteExceptionDomains = Array.from(new Set([
      ...(Array.isArray(excludedData.excludedDomains) ? excludedData.excludedDomains : []),
      ...(Array.isArray(excludedData.filterDisabledDomains) ? excludedData.filterDisabledDomains : [])
    ])).filter((domain) => typeof domain === "string")
      .map((domain) => {
        try {
          return new URL(domain.includes("://") ? domain : `https://${domain}`).hostname.toLowerCase();
        } catch (error) {
          return "";
        }
      })
      .filter((domain) => /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$/.test(domain));
    const rules = [{
      pattern: "||fwmrm.net^",
      resourceTypes: ["xmlhttprequest", "other"],
      domain: {
        include: ["peacocktv.com"],
        exclude: siteExceptionDomains.filter((domain) => (
          domain === "peacocktv.com" || domain.endsWith(".peacocktv.com")
        ))
      }
    }];
    for (const parsed of parsedLists) rules.push(...parsed.network);
    const custom = await parseCustomRules(settings.customFilterRules);
    rules.push(...custom.network);

    const unique = new Map();
    for (const rule of rules) {
      const key = root.FilterNetwork.ruleKey(rule);
      if (!unique.has(key)) unique.set(key, rule);
    }
    const candidates = Array.from(unique.values()).map((rule) => ({
      rule,
      priority: rule.exception ? (rule.important ? 300 : 150) : (rule.important ? 200 : 100)
    }));
    candidates.sort((left, right) => right.priority - left.priority);

    const compiled = candidates.slice(0, root.FILTER_DYNAMIC_RULE_LIMIT).map((item, index) => (
      root.FilterNetwork.compileNetworkRule(
        item.rule,
        root.FILTER_DYNAMIC_RULE_ID_START + index,
        item.priority
      )
    ));
    // Network filters are evaluated against the request URL, but a site's
    // disabled state belongs to the page that initiated the request. Exclude
    // those page domains directly from each compiled rule so the toggle takes
    // effect for subresource requests without relying on an allowAllRequests
    // rule having matched the frame navigation first.
    if (siteExceptionDomains.length) {
      for (const rule of compiled) {
        if (rule.action.type !== "block" && rule.action.type !== "redirect") continue;
        rule.condition.excludedInitiatorDomains = Array.from(new Set([
          ...(rule.condition.excludedInitiatorDomains || []),
          ...siteExceptionDomains
        ]));
      }
    }
    if (candidates.length > compiled.length) {
      await recordDebug("unsupported", `DNR rule limit reached; ${candidates.length - compiled.length} rules are inactive.`);
    }

    if (siteExceptionDomains.length) {
      compiled.push({
        id: root.FILTER_SITE_EXCEPTION_RULE_ID,
        priority: 1000,
        action: { type: "allowAllRequests" },
        condition: {
          requestDomains: siteExceptionDomains,
          resourceTypes: ["main_frame", "sub_frame"]
        }
      });
    }

    const existing = await chrome.declarativeNetRequest.getDynamicRules();
    try {
      await chrome.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: existing
          .filter((rule) => rule.id <= root.FILTER_DYNAMIC_RULE_LIMIT
            || rule.id === root.FILTER_SITE_EXCEPTION_RULE_ID)
          .map((rule) => rule.id),
        addRules: compiled
      });
    } catch (error) {
      await recordDebug("unsupported", `DNR compilation failed: ${error.message || error}`);
      throw error;
    }
  }

  function normalizePageUrl(value) {
    if (typeof value !== "string") return "";
    const candidate = value.trim();
    if (!candidate) return "";
    try {
      return new URL(candidate).href;
    } catch (error) {
      return "";
    }
  }

  async function getPageFilters(url) {
    const pageUrl = normalizePageUrl(url);
    const settings = await getSettings();
    if (!pageUrl) {
      return { selectors: [], scriptlets: [], debug: settings.filterDebug === true };
    }
    const parsedUrl = new URL(pageUrl);
    const hostname = parsedUrl.hostname.toLowerCase();
    if (isDisabled(hostname, settings.filterDisabledDomains)) {
      return { selectors: [], scriptlets: [], debug: settings.filterDebug === true };
    }
    const excluded = isDisabled(hostname, settings.excludedDomains);
    const parsedLists = await getEnabledParsedLists(settings);
    const customRules = await parseCustomRules(settings.customFilterRules);
    const pageRules = root.FilterProcedural.getCosmeticRules(
      excluded ? [] : parsedLists,
      hostname,
      excluded ? { cosmetic: [], cosmeticExceptions: [], scriptlets: [] } : customRules
    );
    return {
      ...pageRules,
      scriptlets: !excluded && (
        hostname === "peacocktv.com" || hostname.endsWith(".peacocktv.com")
        || hostname === "pluto.tv" || hostname.endsWith(".pluto.tv")
      )
        ? [...pageRules.scriptlets, { name: "skip-video-ads", args: [] }]
        : pageRules.scriptlets,
      debug: settings.filterDebug === true
    };
  }

  async function toggleSite(hostname, enabled) {
    const host = String(hostname || "").toLowerCase().replace(/\.$/, "");
    if (!/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$/.test(host)) {
      throw new Error("A valid website hostname is required.");
    }
    const data = await chrome.storage.local.get(["filterDisabledDomains"]);
    const domains = Array.isArray(data.filterDisabledDomains) ? data.filterDisabledDomains : [];
    const updated = enabled
      ? domains.filter((domain) => (
        typeof domain !== "string"
        || !(host === domain || host.endsWith(`.${domain}`))
      ))
      : Array.from(new Set([...domains, host]));
    await chrome.storage.local.set({ filterDisabledDomains: updated });
    await rebuildDynamicRules();
    return updated;
  }

  async function updateListMetadata(listId, update) {
    const data = await getSettings();
    const lists = Array.isArray(data.filterLists) ? data.filterLists : [];
    const list = lists.find((item) => item.id === listId);
    if (!list) throw new Error("Filter list was not found.");
    Object.assign(list, update);
    await saveMetadata(lists);
    await rebuildDynamicRules();
    return list;
  }

  async function addList(name, url) {
    const safeName = String(name || "").trim().slice(0, 100);
    if (!safeName) throw new Error("Enter a filter-list name.");
    const safeUrl = root.FilterLists.validateListUrl(String(url || "").trim());
    const id = `custom-${crypto.randomUUID()}`;
    const data = await getSettings();
    const lists = Array.isArray(data.filterLists) ? data.filterLists : [];
    lists.push({
      id,
      name: safeName,
      url: safeUrl,
      enabled: true,
      isDefault: false,
      ruleCount: 0,
      lastUpdated: null,
      lastSuccess: null,
      lastAttempt: null,
      lastError: null
    });
    await saveMetadata(lists);
    await refreshList(id);
    return (await getSettings()).filterLists.find((list) => list.id === id);
  }

  async function removeList(listId) {
    const data = await getSettings();
    const lists = (data.filterLists || []).filter((list) => list.id !== listId);
    if (lists.length === (data.filterLists || []).length) throw new Error("Custom filter list was not found.");
    await root.FilterStorage.remove(listId);
    parsedListsCache = null;
    await saveMetadata(lists);
    await rebuildDynamicRules();
  }

  async function saveCustomRules(rules) {
    if (!Array.isArray(rules) || rules.length > 1000) {
      throw new Error("Custom rules must contain no more than 1,000 entries.");
    }
    const cleaned = rules.map((entry) => {
      const rule = entry && typeof entry.rule === "string" ? entry.rule.trim() : "";
      if (!rule || rule.length > 4096 || /[\r\n]/.test(rule)) {
        throw new Error("Each custom rule must contain 1–4,096 characters on one line.");
      }
      return {
        id: String(entry.id || crypto.randomUUID()),
        rule,
        enabled: entry.enabled !== false
      };
    });
    await chrome.storage.local.set({ customFilterRules: cleaned });
    const parsed = root.FilterParser.parseFilterList(cleaned
      .filter((entry) => entry.enabled)
      .map((entry) => entry.rule)
      .join("\n"));
    if (parsed.errors.length) {
      await recordDebug("unsupported", `${parsed.parsed.unsupported} unsupported custom rules.`);
    }
    await rebuildDynamicRules();
    return cleaned;
  }

  async function addStats(category, hostname, message, tabId) {
    if (!VALID_EVENT_CATEGORIES.has(category)) return;
    const host = String(hostname || "unknown").toLowerCase().slice(0, 253);
    if (category === "blocked" && isDuplicateBlock(host, message)) {
      return;
    }
    const data = await getSettings();
    if (data.disableFilterStats !== true) {
      if (!Object.prototype.hasOwnProperty.call(pendingStats, host)
        && Object.keys(pendingStats).length >= STATS_HOST_LIMIT) {
        delete pendingStats[Object.keys(pendingStats)[0]];
      }
      const site = pendingStats[host] || (pendingStats[host] = {
        blocked: 0,
        allowed: 0,
        cosmetic: 0,
        scriptlet: 0
      });
      if (Object.prototype.hasOwnProperty.call(site, category)) site[category] += 1;
      scheduleStatsFlush();
    }
    recordDebug(category, message, host, tabId);
  }

  async function getStats(hostname) {
    await flushStats();
    const data = await chrome.storage.local.get(["filterStats"]);
    const stats = data.filterStats || {};
    if (hostname) {
      const host = String(hostname).toLowerCase();
      return Object.prototype.hasOwnProperty.call(stats, host)
        ? stats[host]
        : { blocked: 0, allowed: 0, cosmetic: 0, scriptlet: 0 };
    }
    return Object.values(stats).reduce((total, site) => {
      for (const key of ["blocked", "allowed", "cosmetic", "scriptlet"]) total[key] += site[key] || 0;
      return total;
    }, { blocked: 0, allowed: 0, cosmetic: 0, scriptlet: 0 });
  }

  async function handleMessage(message, sender) {
    if (!message || typeof message.action !== "string") return undefined;
    await flushStats();
    const data = await getSettings();
    filterDebugEnabled = data.filterDebug === true;
    switch (message.action) {
      case "getPageFilters": {
        const pageUrl = typeof message.url === "string" && message.url.trim()
          ? message.url
          : (sender && typeof sender.url === "string" ? sender.url : "");
        return { success: true, ...(await getPageFilters(pageUrl)) };
      }
      case "recordFilterEvent": {
        const hostname = message.hostname
          || (sender && typeof sender.url === "string" ? (() => {
            try {
              return new URL(sender.url).hostname;
            } catch (error) {
              return "unknown";
            }
          })() : "unknown");
        const tabId = typeof message.tabId === "number" ? message.tabId : (sender && sender.tab && typeof sender.tab.id === "number" ? sender.tab.id : null);
        await addStats(message.category, hostname, message.message, tabId);
        return { success: true };
      }
      case "getFilterData": {
        const [debugData, stats] = await Promise.all([
          chrome.storage.local.get(["filterDebugLog", "disableFilterStats"]),
          getStats(message.hostname)
        ]);
        return {
          success: true,
          lists: data.filterLists || [],
          rules: data.customFilterRules || [],
          disabledDomains: data.filterDisabledDomains || [],
          debug: data.filterDebug === true,
          disableStats: debugData.disableFilterStats === true,
          updateHours: Number(data.filterUpdateHours) || root.FILTER_UPDATE_HOURS,
          debugLog: debugData.filterDebugLog || [],
          stats
        };
      }
      case "addFilterList":
        return { success: true, list: await addList(message.name, message.url) };
      case "refreshFilterList":
        return { success: true, list: await refreshList(message.id) };
      case "setFilterListEnabled":
      {
        const list = await updateListMetadata(message.id, { enabled: message.enabled === true });
        if (list.enabled && (!list.lastSuccess
          || Date.now() - list.lastSuccess > (Number(data.filterUpdateHours) || root.FILTER_UPDATE_HOURS) * 60 * 60 * 1000)) {
          return { success: true, list: await refreshList(message.id) };
        }
        return { success: true, list };
      }
      case "removeFilterList":
        await removeList(message.id);
        return { success: true };
      case "saveCustomFilterRules":
        return { success: true, rules: await saveCustomRules(message.rules) };
      case "toggleFilterSite": {
        const domains = await toggleSite(message.hostname, message.enabled === true);
        return { success: true, disabledDomains: domains };
      }
      case "setFilterDebug":
        filterDebugEnabled = message.enabled === true;
        await chrome.storage.local.set({ filterDebug: message.enabled === true });
        return { success: true };
      case "setFilterUpdateHours": {
        const hours = Number(message.hours);
        if (![12, 24, 48, 168].includes(hours)) throw new Error("Choose a supported filter-list update interval.");
        await chrome.storage.local.set({ filterUpdateHours: hours });
        await chrome.alarms.create(root.FILTER_UPDATE_ALARM, { periodInMinutes: hours * 60 });
        return { success: true };
      }
      case "clearFilterDebugLog":
        await flushStats();
        pendingLogs = [];
        await chrome.storage.local.set({ filterDebugLog: [] });
        return { success: true };
      case "resetFilterStats":
        await flushStats();
        pendingStats = Object.create(null);
        recentBlockedRequests.clear();
        await chrome.storage.local.set({ filterStats: {} });
        return { success: true };
      case "setDisableFilterStats":
        if (message.disabled === true) {
          pendingStats = Object.create(null);
        }
        await chrome.storage.local.set({ disableFilterStats: message.disabled === true });
        return { success: true };
      default:
        return undefined;
    }
  }

  root.FilterEngine = {
    ensureDefaults,
    refreshDueLists,
    refreshList,
    rebuildDynamicRules,
    getPageFilters,
    handleMessage,
    addStats,
    setDebugEnabled(enabled) {
      filterDebugEnabled = enabled === true;
    }
  };
})(globalThis);
