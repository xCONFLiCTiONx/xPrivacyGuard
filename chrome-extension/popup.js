document.addEventListener("DOMContentLoaded", async () => {
  const currentDomainEl = document.getElementById("current-domain");
  const statusBadgeEl = document.getElementById("status-badge");
  const toggleBtn = document.getElementById("toggle-exclude-btn");
  const modeSelect = document.getElementById("mode-select");
  const excludedCountEl = document.getElementById("excluded-count");
  const excludedListEl = document.getElementById("excluded-list");
  const newDomainInput = document.getElementById("new-domain-input");
  const addDomainBtn = document.getElementById("add-domain-btn");
  const openOptionsLink = document.getElementById("open-options-link");
  const openDiagnosticsLink = document.getElementById("open-diagnostics-link");
  const filterStatusEl = document.getElementById("filter-status");
  const toggleFilterBtn = document.getElementById("toggle-filter-site");
  const siteMuteBtn = document.getElementById("site-mute-btn");
  const muteIconEl = document.getElementById("mute-icon");
  const muteLabelEl = document.getElementById("mute-label");
  const cookieStatusBadgeEl = document.getElementById("cookie-status-badge");
  const cookieWhitelistBtn = document.getElementById("cookie-whitelist-btn");
  const cookieCleanBtn = document.getElementById("cookie-clean-btn");
  const cookieHintEl = document.getElementById("cookie-hint");

  let activeTab = null;
  let activeDomain = "";
  let isWebPage = false;
  let excludedDomains = [];
  let filterDisabledDomains = [];
  let mutedSites = [];
  let cookieState = {
    setup: false,
    deleteOnTabClose: false,
    deleteOnChromeClose: false,
    whitelist: []
  };

  const UNMUTED_ICON_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path><path d="M19.07 4.93a10 10 0 0 1 0 14.14"></path></svg>';
  const MUTED_ICON_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>';

  function cleanDomain(input) {
    if (!input) return "";
    let str = input.trim().toLowerCase();
    str = str.replace(/^(https?:\/\/)?(www\.)?/, "");
    str = str.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    return str;
  }

  function isDomainMuted(domain, list) {
    if (!domain || !list || !list.length) return false;
    const target = domain.toLowerCase();
    return list.some((item) => {
      const d = item.toLowerCase();
      return target === d || target.endsWith("." + d);
    });
  }

  function isDomainExcluded(domain, list) {
    if (!domain || !list || !list.length) return false;
    const target = domain.toLowerCase();
    return list.some((item) => {
      const d = item.toLowerCase();
      return target === d || target.endsWith("." + d);
    });
  }

  function isCookieDomainWhitelisted(domain, whitelist) {
    if (!domain || !Array.isArray(whitelist) || !whitelist.length) return false;
    const target = domain.replace(/^\./, "").toLowerCase();
    return whitelist.some((site) => {
      const s = String(site || "").trim().replace(/^\./, "").toLowerCase();
      if (!s) return false;
      const base = s.startsWith("*.") ? s.slice(2) : s;
      return target === base || target.endsWith("." + base);
    });
  }

  async function loadState() {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs && tabs.length > 0) {
      activeTab = tabs[0];
      if (activeTab.url) {
        try {
          const parsed = new URL(activeTab.url);
          if (parsed.protocol === "http:" || parsed.protocol === "https:") {
            activeDomain = parsed.hostname.toLowerCase();
            isWebPage = true;
          }
        } catch (e) {}
      }
    }

    const data = await chrome.storage.local.get([
      "mode",
      "excludedDomains",
      "filterDisabledDomains",
      "mutedSites",
      "cookieManagerSetup",
      "cookieDeleteOnTabClose",
      "cookieDeleteOnChromeClose",
      "cookieWhitelist"
    ]);
    const mode = data.mode === "cautious" || data.mode === "extreme"
      ? "cautious"
      : "daily";
    modeSelect.value = mode;
    if (data.mode !== mode) {
      await chrome.storage.local.set({ mode });
    }
    excludedDomains = data.excludedDomains || [];
    filterDisabledDomains = data.filterDisabledDomains || [];
    mutedSites = data.mutedSites || [];
    cookieState = {
      setup: data.cookieManagerSetup === true,
      deleteOnTabClose: data.cookieDeleteOnTabClose === true,
      deleteOnChromeClose: data.cookieDeleteOnChromeClose === true,
      whitelist: Array.isArray(data.cookieWhitelist) ? data.cookieWhitelist : []
    };
    updateUI();

    if (isWebPage) {
      const filterData = await chrome.runtime.sendMessage({
        action: "getFilterData",
        hostname: activeDomain
      });
      if (filterData && filterData.success) {
        document.getElementById("filter-blocked").textContent = String(filterData.stats.blocked || 0);
        document.getElementById("filter-cosmetic").textContent = String(filterData.stats.cosmetic || 0);
        document.getElementById("filter-list-count").textContent =
          String((filterData.lists || []).filter((list) => list.enabled !== false).length);
      }
    }
  }

  function updateUI() {
    if (!isWebPage || !activeDomain) {
      currentDomainEl.textContent = "Non-web page";
      currentDomainEl.title = "";
      statusBadgeEl.textContent = "Disabled";
      statusBadgeEl.className = "badge badge-disabled";
      toggleBtn.disabled = true;
      toggleBtn.textContent = "Exclusion unavailable";
      toggleBtn.className = "btn-secondary btn-flex";
    } else {
      currentDomainEl.textContent = activeDomain;
      currentDomainEl.title = activeDomain;
      const excluded = isDomainExcluded(activeDomain, excludedDomains);
      if (excluded) {
        statusBadgeEl.textContent = "Excluded";
        statusBadgeEl.className = "badge badge-excluded";
        toggleBtn.disabled = false;
        toggleBtn.textContent = "Re-enable protection";
        toggleBtn.className = "btn-primary btn-flex";
      } else {
        statusBadgeEl.textContent = "Protected";
        statusBadgeEl.className = "badge badge-protected";
        toggleBtn.disabled = false;
        toggleBtn.textContent = `Exclude ${activeDomain}`;
        toggleBtn.title = `Exclude ${activeDomain}`;
        toggleBtn.className = "btn-amber-soft btn-flex";
      }
    }

    if (siteMuteBtn) {
      if (!isWebPage || !activeDomain) {
        siteMuteBtn.disabled = true;
        siteMuteBtn.className = "mute-btn";
        siteMuteBtn.title = "Muting unavailable on this page";
        siteMuteBtn.setAttribute("aria-label", "Muting unavailable on this page");
        if (muteIconEl) muteIconEl.innerHTML = UNMUTED_ICON_SVG;
        if (muteLabelEl) muteLabelEl.textContent = "Mute";
      } else {
        siteMuteBtn.disabled = false;
        const isMuted = isDomainMuted(activeDomain, mutedSites);
        if (isMuted) {
          siteMuteBtn.className = "mute-btn is-muted";
          siteMuteBtn.title = `Unmute ${activeDomain} (remove from settings)`;
          siteMuteBtn.setAttribute("aria-label", `Unmute ${activeDomain}`);
          if (muteIconEl) muteIconEl.innerHTML = MUTED_ICON_SVG;
          if (muteLabelEl) muteLabelEl.textContent = "Muted";
        } else {
          siteMuteBtn.className = "mute-btn";
          siteMuteBtn.title = `Mute ${activeDomain} (remember in settings)`;
          siteMuteBtn.setAttribute("aria-label", `Mute ${activeDomain}`);
          if (muteIconEl) muteIconEl.innerHTML = UNMUTED_ICON_SVG;
          if (muteLabelEl) muteLabelEl.textContent = "Mute";
        }
      }
    }

    const filterDisabled = isDomainExcluded(activeDomain, filterDisabledDomains);
    if (!isWebPage || !activeDomain) {
      filterStatusEl.textContent = "Unavailable on this page.";
      toggleFilterBtn.disabled = true;
      toggleFilterBtn.textContent = "Unavailable";
      toggleFilterBtn.className = "btn-secondary btn-inline";
    } else {
      filterStatusEl.textContent = filterDisabled
        ? "Filtering is off on this site · Reload applies changes."
        : "Filtering active · Network counts use optional DNR feedback.";
      toggleFilterBtn.disabled = false;
      toggleFilterBtn.textContent = filterDisabled ? "Enable" : "Disable";
      toggleFilterBtn.className = filterDisabled ? "btn-primary btn-inline" : "btn-amber-soft btn-inline";
    }

    if (cookieStatusBadgeEl && cookieWhitelistBtn && cookieCleanBtn && cookieHintEl) {
      const whitelisted = isWebPage && activeDomain && isCookieDomainWhitelisted(activeDomain, cookieState.whitelist);
      if (!cookieState.setup) {
        cookieStatusBadgeEl.textContent = "Off (Setup)";
        cookieStatusBadgeEl.className = "badge badge-disabled";
        cookieCleanBtn.disabled = true;
        cookieCleanBtn.title = "Configure Cookie Manager in Settings first so cookies aren't deleted unexpectedly";
        cookieHintEl.innerHTML = 'Off by default · <a href="#" id="open-cookies-setup-link">Set up in Settings</a>';
        const setupLink = document.getElementById("open-cookies-setup-link");
        if (setupLink) {
          setupLink.addEventListener("click", (e) => {
            e.preventDefault();
            chrome.tabs.create({ url: chrome.runtime.getURL("settings.html#cookies") });
          });
        }
      } else {
        const autoParts = [];
        if (cookieState.deleteOnTabClose) autoParts.push("tab close");
        if (cookieState.deleteOnChromeClose) autoParts.push("browser close");
        cookieStatusBadgeEl.textContent = whitelisted ? "Whitelisted" : (autoParts.length ? "Auto-Clean" : "Manual");
        cookieStatusBadgeEl.className = whitelisted
          ? "badge badge-protected"
          : (autoParts.length ? "badge badge-excluded" : "badge badge-disabled");
        cookieCleanBtn.disabled = false;
        cookieCleanBtn.title = "Delete all unwhitelisted closed-tab cookies & site data";
        cookieHintEl.textContent = autoParts.length
          ? `Auto-clean on ${autoParts.join(" & ")} · ${cookieState.whitelist.length} whitelisted.`
          : `Manual mode · ${cookieState.whitelist.length} whitelisted.`;
      }

      if (!isWebPage || !activeDomain) {
        cookieWhitelistBtn.disabled = true;
        cookieWhitelistBtn.textContent = "Unavailable";
        cookieWhitelistBtn.className = "btn-secondary btn-flex";
      } else if (whitelisted) {
        cookieWhitelistBtn.disabled = true;
        cookieWhitelistBtn.textContent = "Whitelisted";
        cookieWhitelistBtn.className = "btn-secondary btn-flex";
      } else {
        cookieWhitelistBtn.disabled = false;
        cookieWhitelistBtn.textContent = "Keep Cookies";
        cookieWhitelistBtn.title = `Whitelist ${activeDomain} and its cookie domains`;
        cookieWhitelistBtn.className = "btn-green-soft btn-flex";
      }
    }

    excludedCountEl.textContent = excludedDomains.length;
    excludedListEl.innerHTML = "";

    if (excludedDomains.length === 0) {
      const emptyDiv = document.createElement("div");
      emptyDiv.className = "empty-state";
      emptyDiv.textContent = "No excluded sites yet";
      excludedListEl.appendChild(emptyDiv);
    } else {
      excludedDomains.forEach((domain) => {
        const itemDiv = document.createElement("div");
        itemDiv.className = "domain-item";
        const textSpan = document.createElement("span");
        textSpan.textContent = domain;
        textSpan.title = domain;
        const removeBtn = document.createElement("button");
        removeBtn.type = "button";
        removeBtn.className = "remove-btn";
        removeBtn.textContent = "✕";
        removeBtn.title = `Remove ${domain}`;
        removeBtn.setAttribute("aria-label", `Remove ${domain}`);
        removeBtn.onclick = () => removeDomain(domain);
        itemDiv.appendChild(textSpan);
        itemDiv.appendChild(removeBtn);
        excludedListEl.appendChild(itemDiv);
      });
    }
  }

  async function saveExcludedDomains(newList) {
    excludedDomains = newList;
    await chrome.storage.local.set({ excludedDomains });
    const response = await chrome.runtime.sendMessage({ action: "syncContentScripts" });
    if (!response || !response.success) {
      throw new Error(response && response.error ? response.error : "Could not update site exclusions.");
    }
    updateUI();
  }

  async function toggleActiveDomainExclusion() {
    if (!isWebPage || !activeDomain) return;
    const currentlyExcluded = isDomainExcluded(activeDomain, excludedDomains);
    let updated = [];
    if (currentlyExcluded) {
      updated = excludedDomains.filter((d) => {
        const target = activeDomain.toLowerCase();
        const item = d.toLowerCase();
        return !(target === item || target.endsWith("." + item));
      });
    } else {
      updated = Array.from(new Set([...excludedDomains, activeDomain]));
    }
    try {
      await saveExcludedDomains(updated);
      if (activeTab && activeTab.id) {
        await chrome.tabs.reload(activeTab.id);
      }
    } catch (err) {
      console.error("xPrivacyGuard: Could not apply site exclusion:", err);
      alert(`Could not apply site exclusion: ${err.message}`);
    }
  }

  async function removeDomain(domainToRemove) {
    const updated = excludedDomains.filter((d) => d.toLowerCase() !== domainToRemove.toLowerCase());
    try {
      await saveExcludedDomains(updated);
      if (isWebPage && activeTab && activeTab.id) {
        if (isDomainExcluded(activeDomain, [domainToRemove])) {
          await chrome.tabs.reload(activeTab.id);
        }
      }
    } catch (err) {
      console.error("xPrivacyGuard: Could not update site exclusions:", err);
      alert(`Could not update site exclusions: ${err.message}`);
    }
  }

  async function addDomainFromInput() {
    const raw = newDomainInput.value;
    const cleaned = cleanDomain(raw);
    if (!cleaned) return;
    if (!excludedDomains.includes(cleaned)) {
      const updated = [...excludedDomains, cleaned];
      newDomainInput.value = "";
      try {
        await saveExcludedDomains(updated);
        if (isWebPage && activeTab && activeTab.id && isDomainExcluded(activeDomain, [cleaned])) {
          await chrome.tabs.reload(activeTab.id);
        }
      } catch (err) {
        console.error("xPrivacyGuard: Could not apply site exclusion:", err);
        alert(`Could not apply site exclusion: ${err.message}`);
      }
    }
  }

  async function toggleFilterSite() {
    if (!isWebPage || !activeDomain) return;
    const isDisabled = isDomainExcluded(activeDomain, filterDisabledDomains);
    try {
      const response = await chrome.runtime.sendMessage({
        action: "toggleFilterSite",
        hostname: activeDomain,
        enabled: isDisabled
      });
      if (!response || !response.success) {
        throw new Error(response && response.error ? response.error : "Could not update site filtering.");
      }
      filterDisabledDomains = response.disabledDomains;
      const syncResponse = await chrome.runtime.sendMessage({ action: "syncContentScripts" });
      if (!syncResponse || !syncResponse.success) {
        throw new Error(syncResponse && syncResponse.error ? syncResponse.error : "Could not sync site filtering.");
      }
      updateUI();
      if (activeTab && activeTab.id) await chrome.tabs.reload(activeTab.id);
    } catch (error) {
      console.error("xPrivacyGuard: Could not update site filtering:", error);
      alert(`Could not update site filtering: ${error.message}`);
    }
  }

  async function toggleSiteMute() {
    if (!isWebPage || !activeDomain) return;
    const currentlyMuted = isDomainMuted(activeDomain, mutedSites);
    let updated = [];
    if (currentlyMuted) {
      updated = mutedSites.filter((d) => {
        const target = activeDomain.toLowerCase();
        const item = d.toLowerCase();
        return !(target === item || target.endsWith("." + item));
      });
    } else {
      updated = Array.from(new Set([...mutedSites, activeDomain]));
    }
    try {
      mutedSites = updated;
      await chrome.storage.local.set({ mutedSites });
      if (activeTab && typeof activeTab.id === "number" && chrome.tabs && chrome.tabs.update) {
        try {
          await chrome.tabs.update(activeTab.id, { muted: !currentlyMuted });
        } catch (e) {
          console.warn("xPrivacyGuard: Could not update tab mute status directly:", e);
        }
      }
      if (chrome.runtime && chrome.runtime.sendMessage) {
        chrome.runtime.sendMessage({ action: "syncMutedSites" }).catch(() => {});
      }
      updateUI();
    } catch (err) {
      console.error("xPrivacyGuard: Could not toggle site mute:", err);
      alert(`Could not toggle site mute: ${err.message}`);
    }
  }

  if (siteMuteBtn) siteMuteBtn.addEventListener("click", toggleSiteMute);

  if (cookieWhitelistBtn) {
    cookieWhitelistBtn.addEventListener("click", async () => {
      if (!isWebPage || !activeDomain || !activeTab || !activeTab.url) return;
      try {
        cookieWhitelistBtn.disabled = true;
        const baseDomain = activeDomain.replace(/^www\./, "");
        const domains = new Set([baseDomain]);
        if (chrome.cookies && chrome.cookies.getAll) {
          const cookies = await chrome.cookies.getAll({ url: activeTab.url });
          for (const c of cookies || []) {
            if (c && c.domain) domains.add(c.domain.replace(/^\./, ""));
          }
        }
        const res = await chrome.runtime.sendMessage({
          action: "addCookieWhitelistDomains",
          domains: Array.from(domains)
        });
        if (!res || !res.success) {
          throw new Error((res && res.error) || "Could not whitelist site cookies.");
        }
        cookieState.whitelist = res.whitelist || cookieState.whitelist;
        cookieWhitelistBtn.textContent = "Added All!";
        setTimeout(() => updateUI(), 700);
      } catch (err) {
        console.error("xPrivacyGuard: Could not whitelist cookies:", err);
        alert(`Could not whitelist cookies: ${err.message}`);
        updateUI();
      }
    });
  }

  if (cookieCleanBtn) {
    cookieCleanBtn.addEventListener("click", async () => {
      if (!cookieState.setup) return;
      try {
        cookieCleanBtn.disabled = true;
        cookieCleanBtn.textContent = "Cleaning…";
        const res = await chrome.runtime.sendMessage({ action: "triggerCookieCleanup" });
        if (!res || !res.success) {
          throw new Error((res && res.error) || "Cookie cleanup failed.");
        }
        cookieCleanBtn.textContent = "Done!";
        setTimeout(() => {
          cookieCleanBtn.textContent = "Clean All";
          updateUI();
        }, 900);
      } catch (err) {
        console.error("xPrivacyGuard: Cookie cleanup failed:", err);
        alert(`Cookie cleanup failed: ${err.message}`);
        cookieCleanBtn.textContent = "Clean All";
        updateUI();
      }
    });
  }

  toggleBtn.addEventListener("click", toggleActiveDomainExclusion);
  toggleFilterBtn.addEventListener("click", toggleFilterSite);
  addDomainBtn.addEventListener("click", addDomainFromInput);
  newDomainInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") addDomainFromInput();
  });
  modeSelect.addEventListener("change", async () => {
    try {
      await chrome.storage.local.set({ mode: modeSelect.value });
      const response = await chrome.runtime.sendMessage({ action: "syncContentScripts" });
      if (!response || !response.success) {
        throw new Error(response && response.error ? response.error : "Could not update protection mode.");
      }
    } catch (err) {
      console.error("xPrivacyGuard: Could not update protection mode:", err);
      alert(`Could not update protection mode: ${err.message}`);
    }
  });
  openOptionsLink.addEventListener("click", (e) => {
    e.preventDefault();
    chrome.runtime.openOptionsPage();
  });
  if (openDiagnosticsLink) {
    openDiagnosticsLink.addEventListener("click", (e) => {
      e.preventDefault();
      chrome.tabs.create({ url: chrome.runtime.getURL("settings.html#diagnostics") });
    });
  }

  if (chrome.storage && chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener((changes, namespace) => {
      if (namespace !== "local") return;
      let dirty = false;
      if (changes.mutedSites) {
        mutedSites = changes.mutedSites.newValue || [];
        dirty = true;
      }
      if (changes.cookieManagerSetup !== undefined) {
        cookieState.setup = changes.cookieManagerSetup.newValue === true;
        dirty = true;
      }
      if (changes.cookieDeleteOnTabClose !== undefined) {
        cookieState.deleteOnTabClose = changes.cookieDeleteOnTabClose.newValue === true;
        dirty = true;
      }
      if (changes.cookieDeleteOnChromeClose !== undefined) {
        cookieState.deleteOnChromeClose = changes.cookieDeleteOnChromeClose.newValue === true;
        dirty = true;
      }
      if (changes.cookieWhitelist) {
        cookieState.whitelist = Array.isArray(changes.cookieWhitelist.newValue) ? changes.cookieWhitelist.newValue : [];
        dirty = true;
      }
      if (dirty) updateUI();
    });
  }

  loadState().catch((error) => {
    console.error("xPrivacyGuard: Could not load popup state:", error);
    alert(`Could not load Privacy Guard settings: ${error.message}`);
  });
});
