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

  let activeTab = null;
  let activeDomain = "";
  let isWebPage = false;
  let excludedDomains = [];
  let filterDisabledDomains = [];
  let mutedSites = [];

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

    const data = await chrome.storage.local.get(["mode", "excludedDomains", "filterDisabledDomains", "mutedSites"]);
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
      if (namespace === "local" && changes.mutedSites) {
        mutedSites = changes.mutedSites.newValue || [];
        updateUI();
      }
    });
  }

  loadState().catch((error) => {
    console.error("xPrivacyGuard: Could not load popup state:", error);
    alert(`Could not load Privacy Guard settings: ${error.message}`);
  });
});
