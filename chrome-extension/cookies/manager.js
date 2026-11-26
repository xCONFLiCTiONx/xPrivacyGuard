(function (root) {
  "use strict";

  const COOKIE_KEYS = {
    SETUP: "cookieManagerSetup",
    DELETE_TAB: "cookieDeleteOnTabClose",
    DELETE_CHROME: "cookieDeleteOnChromeClose",
    WHITELIST: "cookieWhitelist",
    VISITED_ORIGINS: "cookieVisitedOrigins"
  };

  let cleanupTimer = null;

  function normalizeCookieDomain(inputDomain) {
    if (typeof inputDomain !== "string") return null;
    let domain = inputDomain.trim().toLowerCase();
    if (!domain) return null;
    domain = domain.replace(/^https?:\/\//, "");
    domain = domain.replace(/^\*\./, "");
    domain = domain.replace(/^\./, "");
    domain = domain.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    domain = domain.replace(/^www\./, "");
    domain = domain.replace(/\.$/, "");
    if (!domain || !/^[a-z0-9.-]+$/.test(domain)) return null;
    return "*." + domain;
  }

  function cleanAndOptimizeWhitelist(list) {
    if (!Array.isArray(list)) return [];
    const normalized = list.map(normalizeCookieDomain).filter(Boolean);
    const unique = Array.from(new Set(normalized));
    unique.sort((a, b) => a.length - b.length);

    const optimized = [];
    for (const item of unique) {
      const base = item.slice(2);
      const isRedundant = optimized.some((existing) => {
        const existingBase = existing.slice(2);
        return base === existingBase || base.endsWith("." + existingBase);
      });
      if (!isRedundant) {
        optimized.push(item);
      }
    }
    return optimized.sort();
  }

  function createWhitelistMatchers(whitelist) {
    const exact = new Set();
    const wildcards = [];
    for (const site of Array.isArray(whitelist) ? whitelist : []) {
      if (typeof site !== "string") continue;
      const normalized = site.trim().replace(/^\./, "").toLowerCase();
      if (!normalized) continue;
      if (normalized.startsWith("*.")) {
        wildcards.push(normalized.slice(2));
      } else {
        exact.add(normalized);
      }
    }
    return { exact, wildcards };
  }

  function isDomainWhitelisted(domain, whitelistOrMatchers) {
    if (!domain) return false;
    const matchers = Array.isArray(whitelistOrMatchers)
      ? createWhitelistMatchers(whitelistOrMatchers)
      : whitelistOrMatchers;
    if (!matchers) return false;
    const d = String(domain).replace(/^\./, "").toLowerCase();
    if (matchers.exact.has(d)) return true;
    return matchers.wildcards.some((base) => d === base || d.endsWith("." + base));
  }

  function getOriginFromUrl(url) {
    try {
      const parsed = new URL(url);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
      return `${parsed.protocol}//${parsed.host}`;
    } catch (e) {
      return null;
    }
  }

  function normalizeHost(hostname) {
    return String(hostname || "").replace(/^\./, "").toLowerCase();
  }

  function originMatchesCookie(origin, cookieDomain) {
    if (!origin || !cookieDomain) return false;
    try {
      const originHost = new URL(origin).hostname.toLowerCase();
      const cookieHost = normalizeHost(cookieDomain);
      return (
        originHost === cookieHost
        || originHost.endsWith("." + cookieHost)
        || cookieHost.endsWith("." + originHost)
      );
    } catch (e) {
      return false;
    }
  }

  async function ensureOobeDefaults() {
    if (!chrome.storage || !chrome.storage.local) return;
    const current = await chrome.storage.local.get([
      COOKIE_KEYS.SETUP,
      COOKIE_KEYS.DELETE_TAB,
      COOKIE_KEYS.DELETE_CHROME,
      COOKIE_KEYS.WHITELIST,
      COOKIE_KEYS.VISITED_ORIGINS
    ]);
    const updates = {};
    if (current[COOKIE_KEYS.SETUP] === undefined) updates[COOKIE_KEYS.SETUP] = false;
    if (current[COOKIE_KEYS.DELETE_TAB] === undefined) updates[COOKIE_KEYS.DELETE_TAB] = false;
    if (current[COOKIE_KEYS.DELETE_CHROME] === undefined) updates[COOKIE_KEYS.DELETE_CHROME] = false;
    if (!Array.isArray(current[COOKIE_KEYS.WHITELIST])) updates[COOKIE_KEYS.WHITELIST] = [];
    if (!Array.isArray(current[COOKIE_KEYS.VISITED_ORIGINS])) updates[COOKIE_KEYS.VISITED_ORIGINS] = [];
    if (Object.keys(updates).length > 0) {
      await chrome.storage.local.set(updates);
    }
  }

  async function trackOrigin(url) {
    if (!url || !url.startsWith("http")) return;
    const origin = getOriginFromUrl(url);
    if (!origin || !chrome.storage || !chrome.storage.local) return;
    try {
      const result = await chrome.storage.local.get([COOKIE_KEYS.VISITED_ORIGINS]);
      const origins = new Set(Array.isArray(result[COOKIE_KEYS.VISITED_ORIGINS]) ? result[COOKIE_KEYS.VISITED_ORIGINS] : []);
      if (!origins.has(origin)) {
        origins.add(origin);
        await chrome.storage.local.set({ [COOKIE_KEYS.VISITED_ORIGINS]: Array.from(origins) });
      }
    } catch (err) {
      console.warn("xPrivacyGuard: Could not track visited origin:", err);
    }
  }

  async function getOpenOrigins() {
    const origins = new Set();
    if (!chrome.tabs || !chrome.tabs.query) return origins;
    try {
      const tabs = await chrome.tabs.query({});
      for (const tab of tabs || []) {
        if (!tab.url) continue;
        const origin = getOriginFromUrl(tab.url);
        if (origin) origins.add(origin);
      }
    } catch (err) {
      console.warn("xPrivacyGuard: Could not query open tabs for cookie protection:", err);
    }
    return origins;
  }

  async function cleanAllUnwhitelistedData() {
    if (!chrome.storage || !chrome.storage.local || !chrome.cookies || !chrome.browsingData) {
      return { skipped: true, reason: "Cookie APIs unavailable", removedCookies: 0, cleanedOrigins: 0 };
    }
    const result = await chrome.storage.local.get([
      COOKIE_KEYS.WHITELIST,
      COOKIE_KEYS.SETUP,
      COOKIE_KEYS.VISITED_ORIGINS
    ]);
    // Strict OOBE guard: never delete cookies until the user has explicitly configured Cookie Manager.
    if (result[COOKIE_KEYS.SETUP] !== true) {
      return { skipped: true, reason: "Cookie Manager is not set up yet.", removedCookies: 0, cleanedOrigins: 0 };
    }

    const whitelist = Array.isArray(result[COOKIE_KEYS.WHITELIST]) ? result[COOKIE_KEYS.WHITELIST] : [];
    const matchers = createWhitelistMatchers(whitelist);
    const visitedOrigins = Array.isArray(result[COOKIE_KEYS.VISITED_ORIGINS]) ? result[COOKIE_KEYS.VISITED_ORIGINS] : [];

    const openOrigins = await getOpenOrigins();
    const cookies = await chrome.cookies.getAll({});
    const originsToRemove = new Set();
    const cookieRemovalPromises = [];

    for (const cookie of cookies || []) {
      const rawDomain = cookie.domain || "";
      const cookieDomain = rawDomain.replace(/^\./, "").toLowerCase();
      if (!cookieDomain) continue;
      if (isDomainWhitelisted(cookieDomain, matchers)) continue;

      let isProtected = false;
      for (const origin of openOrigins) {
        if (originMatchesCookie(origin, cookieDomain)) {
          isProtected = true;
          break;
        }
      }
      if (isProtected) continue;

      const cleanDomain = rawDomain.startsWith(".") ? rawDomain.slice(1) : rawDomain;
      const protocol = cookie.secure ? "https://" : "http://";
      const cookieOrigin = `${protocol}${cleanDomain}`;
      originsToRemove.add(cookieOrigin);

      cookieRemovalPromises.push(
        chrome.cookies.remove({
          url: `${cookieOrigin}${cookie.path}`,
          name: cookie.name,
          storeId: cookie.storeId
        }).catch(() => null)
      );
    }

    for (const origin of visitedOrigins) {
      try {
        const hostname = new URL(origin).hostname;
        const domain = hostname.replace(/^\./, "").toLowerCase();
        if (isDomainWhitelisted(domain, matchers)) continue;
        if (openOrigins.has(origin)) continue;
        originsToRemove.add(origin);
      } catch (e) {}
    }

    await Promise.all(cookieRemovalPromises);

    if (originsToRemove.size > 0) {
      const originsArray = Array.from(originsToRemove);
      try {
        await chrome.browsingData.remove(
          {
            origins: originsArray,
            originTypes: { unprotectedWeb: true, protectedWeb: true }
          },
          {
            cache: true,
            cacheStorage: true,
            cookies: true,
            fileSystems: true,
            indexedDB: true,
            localStorage: true,
            serviceWorkers: true,
            webSQL: true
          }
        );
      } catch (err) {
        console.warn("xPrivacyGuard: browsingData.remove partial error:", err);
      }

      const res = await chrome.storage.local.get([COOKIE_KEYS.VISITED_ORIGINS]);
      const currentVisited = new Set(Array.isArray(res[COOKIE_KEYS.VISITED_ORIGINS]) ? res[COOKIE_KEYS.VISITED_ORIGINS] : []);
      let changed = false;
      for (const o of originsArray) {
        if (currentVisited.delete(o)) changed = true;
      }
      if (changed) {
        await chrome.storage.local.set({ [COOKIE_KEYS.VISITED_ORIGINS]: Array.from(currentVisited) });
      }
    }

    return {
      skipped: false,
      removedCookies: cookieRemovalPromises.length,
      cleanedOrigins: originsToRemove.size
    };
  }

  async function scheduleCleanup(source) {
    if (!chrome.storage || !chrome.storage.local) return;
    try {
      const settings = await chrome.storage.local.get([
        COOKIE_KEYS.SETUP,
        COOKIE_KEYS.DELETE_CHROME,
        COOKIE_KEYS.DELETE_TAB
      ]);
      if (settings[COOKIE_KEYS.SETUP] !== true) return;
      if (source === "tab" && settings[COOKIE_KEYS.DELETE_TAB] !== true) return;
      if (source === "browser" && settings[COOKIE_KEYS.DELETE_CHROME] !== true) return;

      if (cleanupTimer) {
        clearTimeout(cleanupTimer);
      }
      cleanupTimer = setTimeout(() => {
        cleanupTimer = null;
        cleanAllUnwhitelistedData().catch((err) => {
          console.error("xPrivacyGuard: Scheduled cookie cleanup failed:", err);
        });
      }, 500);
    } catch (err) {
      console.warn("xPrivacyGuard: Could not schedule cookie cleanup:", err);
    }
  }

  async function getState() {
    const data = await chrome.storage.local.get([
      COOKIE_KEYS.SETUP,
      COOKIE_KEYS.DELETE_TAB,
      COOKIE_KEYS.DELETE_CHROME,
      COOKIE_KEYS.WHITELIST
    ]);
    return {
      setup: data[COOKIE_KEYS.SETUP] === true,
      deleteOnTabClose: data[COOKIE_KEYS.DELETE_TAB] === true,
      deleteOnChromeClose: data[COOKIE_KEYS.DELETE_CHROME] === true,
      whitelist: Array.isArray(data[COOKIE_KEYS.WHITELIST]) ? data[COOKIE_KEYS.WHITELIST] : []
    };
  }

  async function handleMessage(message) {
    if (!message || typeof message.action !== "string") return undefined;
    switch (message.action) {
      case "getCookieManagerState": {
        const state = await getState();
        return { success: true, ...state };
      }
      case "addCookieWhitelistDomains": {
        const current = await getState();
        const incoming = Array.isArray(message.domains) ? message.domains : [];
        const optimized = cleanAndOptimizeWhitelist([...current.whitelist, ...incoming]);
        await chrome.storage.local.set({
          [COOKIE_KEYS.WHITELIST]: optimized
        });
        return {
          success: true,
          whitelist: optimized,
          setup: current.setup
        };
      }
      case "saveCookieManagerSettings": {
        const current = await getState();
        const updates = {
          [COOKIE_KEYS.SETUP]: message.setup !== undefined ? message.setup === true : true
        };
        if (Array.isArray(message.whitelist)) {
          updates[COOKIE_KEYS.WHITELIST] = cleanAndOptimizeWhitelist(message.whitelist);
        }
        if (typeof message.deleteOnTabClose === "boolean") {
          updates[COOKIE_KEYS.DELETE_TAB] = message.deleteOnTabClose;
        }
        if (typeof message.deleteOnChromeClose === "boolean") {
          updates[COOKIE_KEYS.DELETE_CHROME] = message.deleteOnChromeClose;
        }
        await chrome.storage.local.set(updates);
        const nextState = await getState();
        return { success: true, ...nextState };
      }
      case "triggerCookieCleanup": {
        const result = await cleanAllUnwhitelistedData();
        if (result.skipped) {
          return { success: false, error: result.reason, ...result };
        }
        return { success: true, ...result };
      }
      default:
        return undefined;
    }
  }

  const api = {
    COOKIE_KEYS,
    normalizeCookieDomain,
    cleanAndOptimizeWhitelist,
    createWhitelistMatchers,
    isDomainWhitelisted,
    getOriginFromUrl,
    originMatchesCookie,
    ensureOobeDefaults,
    trackOrigin,
    scheduleCleanup,
    cleanAllUnwhitelistedData,
    getState,
    handleMessage
  };

  root.CookieManager = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(globalThis);
