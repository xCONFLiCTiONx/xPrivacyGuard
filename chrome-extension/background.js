if (typeof importScripts === "function") {
  importScripts(
    "filters/defaults.js",
    "filters/parser.js",
    "filters/network.js",
    "filters/procedural.js",
    "filters/storage.js",
    "filters/lists.js",
    "filters/engine.js"
  );
}

let syncQueue = Promise.resolve();
let debugMatchListenerRegistered = false;
function normalizeExcludedDomains(domains) {
  return Array.from(new Set(
    (Array.isArray(domains) ? domains : [])
      .filter((domain) => typeof domain === "string")
      .map((domain) => {
        const value = domain.trim();
        if (!value) return "";
        try {
          return new URL(value.includes("://") ? value : `https://${value}`)
            .hostname.toLowerCase().replace(/\.$/, "");
        } catch (err) {
          return "";
        }
      })
      .filter(Boolean)
  ));
}

function isDomainMuted(domain, list) {
  if (!domain || !list || !list.length) return false;
  const target = domain.toLowerCase();
  return list.some((item) => {
    const d = item.toLowerCase();
    return target === d || target.endsWith("." + d);
  });
}

async function applyTabMute(tabId, tabUrl) {
  if (typeof tabId !== "number" || tabId <= 0 || !chrome.tabs || !chrome.tabs.update) return;
  try {
    let url = tabUrl;
    if (!url && chrome.tabs.get) {
      const t = await chrome.tabs.get(tabId);
      url = t && t.url;
    }
    if (!url) return;
    const parsed = new URL(url);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;
    const hostname = parsed.hostname.toLowerCase();
    const data = await chrome.storage.local.get(["mutedSites"]);
    const mutedList = normalizeExcludedDomains(data.mutedSites || []);
    const shouldMute = isDomainMuted(hostname, mutedList);
    if (shouldMute) {
      await chrome.tabs.update(tabId, { muted: true });
    }
  } catch (e) {}
}

async function applyAllMutedSites() {
  if (!chrome.tabs || !chrome.tabs.query || !chrome.tabs.update) return;
  try {
    const data = await chrome.storage.local.get(["mutedSites"]);
    const mutedList = normalizeExcludedDomains(data.mutedSites || []);
    const tabs = await chrome.tabs.query({});
    for (const tab of tabs) {
      if (tab.id && tab.url) {
        try {
          const parsed = new URL(tab.url);
          if (parsed.protocol !== "http:" && parsed.protocol !== "https:") continue;
          const hostname = parsed.hostname.toLowerCase();
          const shouldMute = isDomainMuted(hostname, mutedList);
          const isCurrentlyMuted = tab.mutedInfo && tab.mutedInfo.muted;
          if (shouldMute && !isCurrentlyMuted) {
            await chrome.tabs.update(tab.id, { muted: true });
          } else if (!shouldMute && isCurrentlyMuted) {
            await chrome.tabs.update(tab.id, { muted: false });
          }
        } catch (e) {}
      }
    }
  } catch (err) {
    console.error("xPrivacyGuard: Error applying muted sites:", err);
  }
}

function buildExcludePatterns(domains) {
  const patterns = [];
  for (const domain of domains) {
    patterns.push(`*://${domain}/*`);
    if (domain !== "localhost" && !/^\d+\.\d+\.\d+\.\d+$/.test(domain)) {
      patterns.push(`*://*.${domain}/*`);
    }
  }
  return patterns;
}

async function syncFilterContentScript(excludedDomains, filterDisabledDomains) {
  const data = excludedDomains && filterDisabledDomains
    ? { excludedDomains, filterDisabledDomains }
    : await chrome.storage.local.get(["excludedDomains", "filterDisabledDomains"]);
  const patterns = buildExcludePatterns([
    ...normalizeExcludedDomains(data.excludedDomains),
    ...normalizeExcludedDomains(data.filterDisabledDomains)
  ]);
  const config = {
    id: "xPrivacyGuard-filter-content",
    matches: ["<all_urls>", "file:///*"],
    js: ["filters/scriptlets.js", "filters/content.js"],
    runAt: "document_start"
  };
  if (patterns.length) config.excludeMatches = patterns;
  const registered = await chrome.scripting.getRegisteredContentScripts({ ids: [config.id] });
  if (registered.length) {
    await chrome.scripting.updateContentScripts([config]);
  } else {
    await chrome.scripting.registerContentScripts([config]);
  }
}

function syncContentScripts() {
  syncQueue = syncQueue.catch(() => {}).then(doSyncContentScripts);
  return syncQueue;
}

async function doSyncContentScripts() {
  const data = await chrome.storage.local.get([
    "excludedDomains",
    "filterDisabledDomains",
    "mode",
    "enableGPC"
  ]);
  const excludedDomains = normalizeExcludedDomains(data.excludedDomains);
  const filterDisabledDomains = normalizeExcludedDomains(data.filterDisabledDomains);
  const cautiousMode = data.mode === "cautious" || data.mode === "extreme";

  const excludePatterns = buildExcludePatterns(excludedDomains);

  const scriptConfig = {
    id: "xPrivacyGuard-inject",
    matches: ["<all_urls>", "file:///*"],
    js: ["inject.js"],
    css: ["daily.css"],
    runAt: "document_start",
    world: "MAIN"
  };

  if (excludePatterns.length > 0) {
    scriptConfig.excludeMatches = excludePatterns;
  }

  const registered = await chrome.scripting.getRegisteredContentScripts({
    ids: ["xPrivacyGuard-inject"]
  });

  if (registered && registered.length > 0) {
    await chrome.scripting.updateContentScripts([scriptConfig]);
  } else {
    try {
      await chrome.scripting.registerContentScripts([scriptConfig]);
    } catch (err) {
      if (err && err.message && err.message.includes("Duplicate script ID")) {
        await chrome.scripting.updateContentScripts([scriptConfig]);
      } else {
        throw err;
      }
    }
  }

  const extremeScriptConfig = {
    id: "xPrivacyGuard-extreme",
    matches: ["<all_urls>", "file:///*"],
    js: ["extreme.js"],
    css: ["extreme.css"],
    runAt: "document_start",
    world: "MAIN"
  };

  if (excludePatterns.length > 0) {
    extremeScriptConfig.excludeMatches = excludePatterns;
  }

  const extremeRegistered = await chrome.scripting.getRegisteredContentScripts({
    ids: ["xPrivacyGuard-extreme"]
  });

  if (cautiousMode) {
    if (extremeRegistered && extremeRegistered.length > 0) {
      await chrome.scripting.updateContentScripts([extremeScriptConfig]);
    } else {
      await chrome.scripting.registerContentScripts([extremeScriptConfig]);
    }
    await chrome.declarativeNetRequest.updateEnabledRulesets({
      enableRulesetIds: ["extreme_rules"]
    });
  } else {
    if (extremeRegistered && extremeRegistered.length > 0) {
      await chrome.scripting.unregisterContentScripts({
        ids: ["xPrivacyGuard-extreme"]
      });
    }
    await chrome.declarativeNetRequest.updateEnabledRulesets({
      disableRulesetIds: ["extreme_rules"]
    });
  }

  await syncGpcState(data.enableGPC !== false);

  await syncFilterContentScript(excludedDomains, filterDisabledDomains);
  await FilterEngine.rebuildDynamicRules();
}

// GPC State Synchronization
let gpcSyncPromise = Promise.resolve();

function syncGpcState(enabled) {
  gpcSyncPromise = gpcSyncPromise.catch(() => {}).then(async () => {
    if (enabled) {
      await chrome.declarativeNetRequest.updateEnabledRulesets({
        enableRulesetIds: ['gpc_rules']
      });
    } else {
      await chrome.declarativeNetRequest.updateEnabledRulesets({
        disableRulesetIds: ['gpc_rules']
      });
    }

    const scriptId = 'gpc-main-world';
    const registered = await chrome.scripting.getRegisteredContentScripts({
      ids: [scriptId]
    });
    if (registered.length > 0) {
      await chrome.scripting.unregisterContentScripts({ ids: [scriptId] });
    }

    if (enabled) {
      const gpcScriptConfig = {
        id: scriptId,
        matches: ['<all_urls>', 'file:///*'],
        js: ['gpc.js'],
        runAt: 'document_start',
        world: 'MAIN',
        allFrames: true
      };
      try {
        await chrome.scripting.registerContentScripts([gpcScriptConfig]);
      } catch (err) {
        if (err && err.message && err.message.includes('Duplicate script ID')) {
          await chrome.scripting.updateContentScripts([gpcScriptConfig]);
        } else {
          throw err;
        }
      }
    }
  });
  return gpcSyncPromise;
}

function initGpc() {
  chrome.storage.local.get(['enableGPC'], (res) => {
    const enabled = res.enableGPC !== false;
    if (res.enableGPC === undefined) {
      chrome.storage.local.set({ enableGPC: true });
    }
    syncGpcState(enabled).catch((err) => {
      console.error('GPC sync error:', err);
    });
  });
}

// --- Tab Session Block Counter & Icon Badge ---
const tabBlockCounts = new Map();
const seenBlockRequests = new Map();
let disableFilterStats = false;

function initBadgeAppearance() {
  if (!chrome.action) return;
  try {
    if (chrome.action.setBadgeBackgroundColor) {
      chrome.action.setBadgeBackgroundColor({ color: "#2563eb" }).catch(() => {});
    }
    if (chrome.action.setBadgeTextColor) {
      chrome.action.setBadgeTextColor({ color: "#FFFFFF" }).catch(() => {});
    }
  } catch (err) {}
}

function formatBadgeCount(count) {
  if (!count || count <= 0) return "";
  if (count > 999) return "999+";
  return String(count);
}

function updateTabBadge(tabId, count) {
  if (!chrome.action || !chrome.action.setBadgeText) return;
  if (typeof tabId !== "number" || tabId <= 0) return;
  try {
    const text = disableFilterStats ? "" : formatBadgeCount(count);
    chrome.action.setBadgeText({ text, tabId }).catch(() => {});
  } catch (err) {}
}

async function persistSessionCounts() {
  if (chrome.storage && chrome.storage.session) {
    try {
      const stored = Object.fromEntries(tabBlockCounts);
      await chrome.storage.session.set({ tabBlockCounts: stored });
    } catch (e) {}
  }
}

async function initTabBlockCounts() {
  if (chrome.storage && chrome.storage.session) {
    try {
      const data = await chrome.storage.session.get("tabBlockCounts");
      if (data && data.tabBlockCounts && typeof data.tabBlockCounts === "object") {
        for (const [idStr, count] of Object.entries(data.tabBlockCounts)) {
          const id = Number(idStr);
          if (id > 0 && typeof count === "number" && count > 0) {
            tabBlockCounts.set(id, count);
            updateTabBadge(id, count);
          }
        }
      }
    } catch (e) {}
  }
  if (chrome.tabs && chrome.tabs.query) {
    try {
      const tabs = await chrome.tabs.query({});
      if (tabs && tabs.length) {
        const activeIds = new Set(tabs.map((t) => t.id));
        let changed = false;
        for (const id of tabBlockCounts.keys()) {
          if (!activeIds.has(id)) {
            tabBlockCounts.delete(id);
            seenBlockRequests.delete(id);
            changed = true;
          }
        }
        if (changed) {
          await persistSessionCounts();
        }
      }
    } catch (e) {}
  }
}

function recordBlockedRequest(tabId, requestId) {
  if (typeof tabId !== "number" || tabId <= 0) return;
  if (disableFilterStats) return;

  if (requestId) {
    let tabSeen = seenBlockRequests.get(tabId);
    if (!tabSeen) {
      tabSeen = new Set();
      seenBlockRequests.set(tabId, tabSeen);
    }
    if (tabSeen.has(requestId)) {
      return;
    }
    tabSeen.add(requestId);
    if (tabSeen.size > 2000) {
      const iter = tabSeen.values();
      for (let i = 0; i < 500; i++) {
        const item = iter.next().value;
        if (item) tabSeen.delete(item);
      }
    }
  }

  const current = (tabBlockCounts.get(tabId) || 0) + 1;
  tabBlockCounts.set(tabId, current);
  updateTabBadge(tabId, current);
  persistSessionCounts().catch(() => {});
}

function resetTabBlockCount(tabId) {
  if (typeof tabId !== "number" || tabId <= 0) return;
  tabBlockCounts.delete(tabId);
  seenBlockRequests.delete(tabId);
  updateTabBadge(tabId, 0);
  persistSessionCounts().catch(() => {});
}

function clearAllTabBadges() {
  for (const tabId of tabBlockCounts.keys()) {
    updateTabBadge(tabId, 0);
  }
  tabBlockCounts.clear();
  seenBlockRequests.clear();
  persistSessionCounts().catch(() => {});
}

// Reset block counter when a tab is refreshed or navigates
if (chrome.tabs && chrome.tabs.onUpdated) {
  chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.status === "loading") {
      resetTabBlockCount(tabId);
    }
    const urlToCheck = changeInfo.url || (tab && tab.url);
    if (urlToCheck) {
      applyTabMute(tabId, urlToCheck);
    }
  });
}

if (chrome.tabs && chrome.tabs.onRemoved) {
  chrome.tabs.onRemoved.addListener((tabId) => {
    tabBlockCounts.delete(tabId);
    seenBlockRequests.delete(tabId);
    persistSessionCounts().catch(() => {});
  });
}

if (chrome.tabs && chrome.tabs.onReplaced) {
  chrome.tabs.onReplaced.addListener((addedTabId, removedTabId) => {
    tabBlockCounts.delete(removedTabId);
    seenBlockRequests.delete(removedTabId);
    persistSessionCounts().catch(() => {});
  });
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message && message.action === "setPeacockDiagnostics") {
    const enabled = message.enabled === true;
    chrome.storage.local.set({ peacockDiagnosticsEnabled: enabled }).then(
      () => sendResponse({ success: true, enabled }),
      (error) => sendResponse({ success: false, error: error.message })
    );
    return true;
  }
  if (message && message.action === "getPeacockDiagnostics") {
    chrome.storage.local.get(["peacockDiagnosticsEnabled", "peacockDiagnosticsLog"]).then((data) => {
      sendResponse({
        success: true,
        enabled: data.peacockDiagnosticsEnabled === true,
        count: Array.isArray(data.peacockDiagnosticsLog) ? data.peacockDiagnosticsLog.length : 0,
        log: message.includeLog === true && Array.isArray(data.peacockDiagnosticsLog)
          ? data.peacockDiagnosticsLog : undefined
      });
    }, (error) => sendResponse({ success: false, error: error.message }));
    return true;
  }
  if (message && message.action === "clearPeacockDiagnostics") {
    chrome.storage.local.set({ peacockDiagnosticsLog: [] }).then(
      () => sendResponse({ success: true }),
      (error) => sendResponse({ success: false, error: error.message })
    );
    return true;
  }
  if (message && message.action === "syncMutedSites") {
    applyAllMutedSites().then(
      () => sendResponse({ success: true }),
      (err) => sendResponse({ success: false, error: err.message })
    );
    return true;
  }

  if (message && message.action === "syncContentScripts") {
    syncContentScripts().then(
      () => sendResponse({ success: true }),
      (err) => {
        console.error("xPrivacyGuard: Error syncing content scripts:", err);
        sendResponse({
          success: false,
          error: err instanceof Error ? err.message : String(err)
        });
      }
    );
    return true;
  }

  if (message && message.action === "getTabBlockCount") {
    const tabId = typeof message.tabId === "number" ? message.tabId : (sender && sender.tab && sender.tab.id);
    const count = (typeof tabId === "number" && tabBlockCounts.get(tabId)) || 0;
    sendResponse({ success: true, count });
    return true;
  }

  if (message && message.action === "getOpenTabs") {
    if (chrome.tabs && chrome.tabs.query) {
      chrome.tabs.query({}).then((tabs) => {
        const list = (tabs || []).map((t) => ({
          id: t.id,
          title: t.title || "",
          url: t.url || "",
          favIconUrl: t.favIconUrl || ""
        }));
        sendResponse({ success: true, tabs: list });
      }).catch((err) => {
        sendResponse({ success: false, error: err.message, tabs: [] });
      });
      return true;
    } else {
      sendResponse({ success: true, tabs: [] });
      return true;
    }
  }

  if (message && message.action === "resetFilterStats") {
    clearAllTabBadges();
  }

  if (message && message.action === "recordFilterEvent" && message.category === "blocked") {
    const tabId = sender && sender.tab && sender.tab.id;
    if (typeof tabId === "number" && tabId > 0) {
      recordBlockedRequest(tabId, message.requestId);
    }
  }

  if (message && [
    "getPageFilters",
    "recordFilterEvent",
    "getFilterData",
    "addFilterList",
    "refreshFilterList",
    "setFilterListEnabled",
    "removeFilterList",
    "saveCustomFilterRules",
    "toggleFilterSite",
    "setFilterDebug",
    "clearFilterDebugLog",
    "resetFilterStats",
    "setDisableFilterStats",
    "setFilterUpdateHours"
  ].includes(message.action)) {
    FilterEngine.handleMessage(message, sender).then(
      (response) => sendResponse(response || { success: false, error: "Unknown filter action." }),
      (err) => {
        console.error("xPrivacyGuard: Filter operation failed:", err);
        sendResponse({
          success: false,
          error: err instanceof Error ? err.message : String(err)
        });
      }
    );
    return true;
  }
});

chrome.runtime.onInstalled.addListener(() => {
  initBadgeAppearance();
  applyAllMutedSites().catch(() => {});
  syncContentScripts().catch((err) => {
    console.error("xPrivacyGuard: Error syncing content scripts:", err);
  });
  initGpc();
  FilterEngine.ensureDefaults()
    .then(() => FilterEngine.rebuildDynamicRules())
    .then(() => FilterEngine.refreshDueLists())
    .catch((err) => console.error("xPrivacyGuard: Could not initialize filter lists:", err));
});

chrome.runtime.onStartup.addListener(() => {
  initBadgeAppearance();
  initTabBlockCounts();
  syncContentScripts().catch((err) => {
    console.error("xPrivacyGuard: Error syncing content scripts:", err);
  });
  initGpc();
  FilterEngine.ensureDefaults()
    .then(() => FilterEngine.rebuildDynamicRules())
    .then(() => FilterEngine.refreshDueLists())
    .catch((err) => console.error("xPrivacyGuard: Could not initialize filter lists:", err));
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === FILTER_UPDATE_ALARM) {
    FilterEngine.refreshDueLists().catch((err) => {
      console.error("xPrivacyGuard: Scheduled filter-list update failed:", err);
    });
  }
});

chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === "local") {
    if (changes.excludedDomains || changes.mode || changes.enableGPC) {
      syncContentScripts().catch((err) => {
        console.error("xPrivacyGuard: Error syncing protection settings:", err);
      });
    }
    if (changes.mutedSites) {
      applyAllMutedSites().catch((err) => {
        console.error("xPrivacyGuard: Error syncing muted sites:", err);
      });
    }
    if (changes.filterDisabledDomains) {
      syncFilterContentScript().catch((err) => {
        console.error("xPrivacyGuard: Error syncing site filter settings:", err);
      });
    }
    if (changes.disableFilterStats !== undefined) {
      disableFilterStats = changes.disableFilterStats.newValue === true;
      if (disableFilterStats) {
        clearAllTabBadges();
      }
    }
  }
});

initBadgeAppearance();
initTabBlockCounts();
if (chrome.storage && chrome.storage.local) {
  chrome.storage.local.get(["disableFilterStats"], (res) => {
    disableFilterStats = res && res.disableFilterStats === true;
    if (disableFilterStats) {
      clearAllTabBadges();
    }
  });
}

syncContentScripts().catch((err) => {
  console.error("xPrivacyGuard: Error syncing content scripts:", err);
});
initGpc();
FilterEngine.rebuildDynamicRules()
  .catch((err) => console.error("xPrivacyGuard: Could not initialize filter engine:", err));

function registerDebugMatchListener() {
  if (debugMatchListenerRegistered
    || !chrome.declarativeNetRequest.onRuleMatchedDebug
    || !chrome.permissions) return;
  chrome.permissions.contains(
    { permissions: ["declarativeNetRequestFeedback"] },
    (granted) => {
      if (!granted || debugMatchListenerRegistered) return;
      debugMatchListenerRegistered = true;
      chrome.declarativeNetRequest.onRuleMatchedDebug.addListener((details) => {
        const actionType = details.rule && details.rule.action ? details.rule.action.type : "";
        if (actionType !== "block" && actionType !== "redirect" && actionType !== "allow") return;
        const category = actionType === "allow" ? "allowed" : "blocked";
        if (category === "blocked" && details.request && typeof details.request.tabId === "number" && details.request.tabId > 0) {
          recordBlockedRequest(details.request.tabId, details.request.requestId);
        }
        let hostname = "";
        try {
          hostname = new URL(details.request.initiator || details.request.url).hostname;
        } catch (err) {
          return;
        }
        const reqTabId = details.request && typeof details.request.tabId === "number" && details.request.tabId > 0
          ? details.request.tabId
          : null;
        const verb = actionType === "redirect" ? "Redirected" : category === "blocked" ? "Blocked" : "Allowed";
        FilterEngine.addStats(
          category,
          hostname,
          `${verb} ${details.request.url} (rule ${details.rule.ruleId}).`,
          reqTabId
        ).catch((err) => console.error("xPrivacyGuard: Could not record filter match:", err));
      });
    }
  );
}

registerDebugMatchListener();
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === "local" && changes.filterDebug) {
    FilterEngine.setDebugEnabled(changes.filterDebug.newValue === true);
    registerDebugMatchListener();
  }
});

// Temporary, opt-in Peacock request diagnostics. This observes traffic only; it never changes requests.
const PEACOCK_DIAGNOSTICS_MAX_EVENTS = 1000;
let peacockDiagnosticsEnabled = false;
let peacockDiagnosticsWriteQueue = Promise.resolve();

chrome.storage.local.get(["peacockDiagnosticsEnabled"], (data) => {
  peacockDiagnosticsEnabled = data.peacockDiagnosticsEnabled === true;
});
chrome.storage.onChanged.addListener((changes, namespace) => {
  if (namespace === "local" && changes.peacockDiagnosticsEnabled) {
    peacockDiagnosticsEnabled = changes.peacockDiagnosticsEnabled.newValue === true;
  }
});

function isPeacockHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/\.$/, "");
  return host === "peacocktv.com" || host.endsWith(".peacocktv.com");
}

async function peacockRequestContext(details) {
  for (const candidate of [details.initiator, details.documentUrl]) {
    if (!candidate || candidate === "null") continue;
    try {
      const parsed = new URL(candidate);
      if (isPeacockHost(parsed.hostname)) return parsed.origin;
    } catch (_) {}
  }
  // A cross-origin player iframe may initiate media requests. Confirm the
  // top-level tab is Peacock before including those requests.
  if (typeof details.tabId === "number" && details.tabId >= 0 && chrome.tabs && chrome.tabs.get) {
    try {
      const tab = await chrome.tabs.get(details.tabId);
      const parsed = new URL(tab.url || "");
      if (isPeacockHost(parsed.hostname)) return parsed.origin;
    } catch (_) {}
  }
  return "";
}

function sanitizePeacockRequestUrl(value) {
  try {
    const url = new URL(value);
    const sensitive = /^(?:access_token|auth|authorization|bearer|code|device(?:id)?|email|key|nonce|password|session(?:id)?|sig(?:nature)?|token|user(?:id)?)$/i;
    for (const key of Array.from(url.searchParams.keys())) {
      if (sensitive.test(key)) url.searchParams.set(key, "[REDACTED]");
    }
    url.username = "";
    url.password = "";
    return url.href.slice(0, 4096);
  } catch (_) {
    return String(value || "").slice(0, 4096);
  }
}

function classifyPeacockRequest(details) {
  const url = String(details.url || "").toLowerCase();
  const type = String(details.type || "other").toLowerCase();
  if (type === "media" || /\.(?:m3u8|mpd|mp4|m4s|ts|cmfv|cmfa)(?:$|[?#])/.test(url)
    || /(?:manifest|playlist|segment|\/cmaf\/)/.test(url)) return "media";
  if (/fwmrm|freewheel|doubleclick|\/(?:ad|ads|advert|vast|vmap)(?:\/|[?.])|adserver/.test(url)) return "ad-delivery";
  if (/scorecardresearch|omtrdc|imrworldwide|conviva|newrelic|nr-data|analytics|pixel|\/trk(?:[/?]|$)/.test(url)) return "measurement";
  return "other";
}

async function recordPeacockDiagnostic(details, outcome) {
  if (!peacockDiagnosticsEnabled || !details || details.tabId < 0) return;
  const initiator = await peacockRequestContext(details);
  if (!initiator) return;
  const responseHeaders = {};
  for (const header of details.responseHeaders || []) {
    const name = String(header.name || "").toLowerCase();
    if (name === "content-type" || name === "content-length") {
      responseHeaders[name] = String(header.value || "").slice(0, 256);
    }
  }
  const event = {
    timestamp: new Date(details.timeStamp || Date.now()).toISOString(),
    tabId: details.tabId,
    initiator,
    url: sanitizePeacockRequestUrl(details.url),
    resourceType: details.type || "other",
    classification: classifyPeacockRequest(details),
    outcome,
    statusCode: typeof details.statusCode === "number" ? details.statusCode : null,
    error: details.error || null,
    responseHeaders
  };
  peacockDiagnosticsWriteQueue = peacockDiagnosticsWriteQueue.catch(() => {}).then(async () => {
    const data = await chrome.storage.local.get(["peacockDiagnosticsLog"]);
    const log = Array.isArray(data.peacockDiagnosticsLog) ? data.peacockDiagnosticsLog : [];
    log.push(event);
    if (log.length > PEACOCK_DIAGNOSTICS_MAX_EVENTS) {
      log.splice(0, log.length - PEACOCK_DIAGNOSTICS_MAX_EVENTS);
    }
    await chrome.storage.local.set({ peacockDiagnosticsLog: log });
  }).catch((error) => console.warn("xPrivacyGuard: Peacock diagnostics write failed:", error));
}

function registerPeacockDiagnosticsListeners() {
  if (!chrome.webRequest) return;
  const filter = { urls: ["<all_urls>"] };
  chrome.webRequest.onCompleted.addListener(
    (details) => recordPeacockDiagnostic(details, "completed"),
    filter,
    ["responseHeaders"]
  );
  chrome.webRequest.onErrorOccurred.addListener(
    (details) => recordPeacockDiagnostic(details, "failed"),
    filter
  );
}

registerPeacockDiagnosticsListeners();

function registerWebRequestBlockListener() {
  if (!chrome.webRequest || !chrome.webRequest.onErrorOccurred) return;
  chrome.webRequest.onErrorOccurred.addListener(
    async (details) => {
      if (details.error !== "net::ERR_BLOCKED_BY_CLIENT") return;

      let hostname = "";
      try {
        hostname = new URL(details.initiator || details.url).hostname;
      } catch (err) {
        try {
          hostname = new URL(details.url).hostname;
        } catch (e) {
          hostname = "unknown";
        }
      }

      try {
        const data = await chrome.storage.local.get(["excludedDomains", "filterDisabledDomains"]);
        const disabled = [
          ...normalizeExcludedDomains(data.excludedDomains),
          ...normalizeExcludedDomains(data.filterDisabledDomains)
        ];
        const host = hostname.toLowerCase().replace(/\.$/, "");
        if (disabled.some((domain) => host === domain || host.endsWith(`.${domain}`))) return;
      } catch (err) {
        // Keep best-effort blocked-request accounting available if settings cannot be read.
      }

      if (typeof details.tabId === "number" && details.tabId > 0) {
        recordBlockedRequest(details.tabId, details.requestId);
      }
      const reqTabId = typeof details.tabId === "number" && details.tabId > 0 ? details.tabId : null;
      FilterEngine.addStats("blocked", hostname, `Blocked ${details.url}`, reqTabId)
        .catch((err) => console.error("xPrivacyGuard: Could not record blocked request:", err));
    },
    { urls: ["<all_urls>"] }
  );
}

registerWebRequestBlockListener();

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    initBadgeAppearance,
    formatBadgeCount,
    updateTabBadge,
    recordBlockedRequest,
    resetTabBlockCount,
    clearAllTabBadges,
    isDomainMuted,
    applyTabMute,
    applyAllMutedSites,
    syncGpcState,
    tabBlockCounts,
    seenBlockRequests
  };
}
