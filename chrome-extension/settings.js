if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
  const modeSelect = document.getElementById("mode");
  const gpcCheckbox = document.getElementById("enableGPC");
  const domainListEl = document.getElementById("domain-list");
  const newDomainInput = document.getElementById("new-domain-input");
  const addDomainBtn = document.getElementById("add-domain-btn");
  const savedBanner = document.getElementById("saved-banner");
  const errorBanner = document.getElementById("error-banner");
  const listContainer = document.getElementById("filter-lists");
  const customRulesContainer = document.getElementById("custom-filter-rules");
  const debugLogContainer = document.getElementById("filter-debug-log");
  const mutedSitesListEl = document.getElementById("muted-sites-list");
  const newMutedSiteInput = document.getElementById("new-muted-site-input");
  const addMutedSiteBtn = document.getElementById("add-muted-site-btn");

  let excludedDomains = [];
  let mutedSites = [];
  let filterLists = [];
  let customRules = [];
  let debugLog = [];
  let openTabs = [];
  let isLiveLogEnabled = true;
  const debugCategorySelect = document.getElementById("debug-category");
  const debugTabSelect = document.getElementById("debug-tab");
  const refreshDebugLogBtn = document.getElementById("refresh-filter-debug-log");
  const copyDebugLogBtn = document.getElementById("copy-filter-debug-log");
  const exportDebugLogBtn = document.getElementById("export-filter-debug-log");
  const liveDebugLogCheckbox = document.getElementById("live-debug-log");
  const exportSettingsBtn = document.getElementById("export-settings-btn");
  const copySettingsBtn = document.getElementById("copy-settings-btn");
  const importSettingsBtn = document.getElementById("import-settings-btn");
  const importSettingsFileInput = document.getElementById("import-settings-file");

  function cleanDomain(input) {
    if (!input) return "";
    let str = input.trim().toLowerCase();
    str = str.replace(/^(https?:\/\/)?(www\.)?/, "");
    str = str.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    return str;
  }

  function showSavedBanner(msg = "Settings saved successfully!") {
    errorBanner.style.display = "none";
    savedBanner.textContent = msg;
    savedBanner.style.display = "block";
    setTimeout(() => {
      savedBanner.style.display = "none";
    }, 2000);
  }

  function showError(error) {
    errorBanner.textContent = error instanceof Error ? error.message : String(error);
    errorBanner.style.display = "block";
    savedBanner.style.display = "none";
  }

  async function sendFilterMessage(action, data = {}) {
    const response = await chrome.runtime.sendMessage({ action, ...data });
    if (!response || !response.success) {
      throw new Error(response && response.error ? response.error : "Filter operation failed.");
    }
    return response;
  }

  function formatDate(value) {
    return value ? new Date(value).toLocaleString() : "Never updated";
  }

  function renderFilterLists() {
    listContainer.replaceChildren();
    if (!filterLists.length) {
      const empty = document.createElement("div");
      empty.className = "empty-notice";
      empty.textContent = "No filter lists configured.";
      listContainer.appendChild(empty);
      return;
    }
    for (const list of filterLists) {
      const row = document.createElement("div");
      row.className = "filter-list";
      const enabled = document.createElement("input");
      enabled.type = "checkbox";
      enabled.checked = list.enabled !== false;
      enabled.setAttribute("aria-label", `Enable ${list.name}`);
      enabled.addEventListener("change", () => updateList(list.id, { enabled: enabled.checked }));

      const main = document.createElement("div");
      main.className = "filter-list-main";
      const title = document.createElement("div");
      title.className = "filter-list-name";
      title.textContent = list.name;
      const meta = document.createElement("div");
      meta.className = "filter-list-meta";
      meta.textContent = `${list.ruleCount || 0} rules · Updated ${formatDate(list.lastSuccess)} · ${list.url}`;
      main.append(title, meta);
      if (list.lastError) {
        const error = document.createElement("div");
        error.className = "filter-list-meta";
        error.textContent = `Last update failed: ${list.lastError}`;
        main.appendChild(error);
      }

      const actions = document.createElement("div");
      actions.className = "filter-list-actions";
      const update = document.createElement("button");
      update.type = "button";
      update.textContent = "Update";
      update.addEventListener("click", () => runListAction("refreshFilterList", { id: list.id }));
      actions.appendChild(update);
      if (!list.isDefault) {
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "Remove";
        remove.addEventListener("click", () => runListAction("removeFilterList", { id: list.id }));
        actions.appendChild(remove);
      }
      row.append(enabled, main, actions);
      listContainer.appendChild(row);
    }
  }

  function renderCustomRules() {
    customRulesContainer.replaceChildren();
    if (!customRules.length) {
      const empty = document.createElement("div");
      empty.className = "empty-notice";
      empty.textContent = "No custom rules.";
      customRulesContainer.appendChild(empty);
      return;
    }
    for (const item of customRules) {
      const row = document.createElement("div");
      row.className = "custom-rule";
      const enabled = document.createElement("input");
      enabled.type = "checkbox";
      enabled.checked = item.enabled !== false;
      enabled.setAttribute("aria-label", "Enable custom rule");
      enabled.addEventListener("change", () => saveCustomRule(item.id, { enabled: enabled.checked }));
      const value = document.createElement("input");
      value.type = "text";
      value.value = item.rule;
      value.setAttribute("aria-label", "Custom filter rule");
      value.addEventListener("change", () => saveCustomRule(item.id, { rule: value.value }));
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = "Delete";
      remove.addEventListener("click", () => {
        customRules = customRules.filter((rule) => rule.id !== item.id);
        persistCustomRules();
      });
      row.append(enabled, value, remove);
      customRulesContainer.appendChild(row);
    }
  }

  async function updateOpenTabs() {
    try {
      const response = await chrome.runtime.sendMessage({ action: "getOpenTabs" });
      if (response && response.success && Array.isArray(response.tabs)) {
        openTabs = response.tabs;
      }
    } catch (e) {
      // Best-effort
    }
    populateTabSelect();
  }

  function populateTabSelect() {
    if (!debugTabSelect) return;
    const currentVal = debugTabSelect.value || "all";
    debugTabSelect.replaceChildren();

    const allOption = document.createElement("option");
    allOption.value = "all";
    allOption.textContent = "All tabs & background";
    debugTabSelect.appendChild(allOption);

    // Group tabs by ID
    const knownTabIds = new Set();
    for (const tab of openTabs) {
      if (typeof tab.id === "number") {
        knownTabIds.add(tab.id);
        const opt = document.createElement("option");
        opt.value = String(tab.id);
        let label = tab.title ? tab.title.slice(0, 45) : "";
        if (tab.url) {
          try {
            const host = new URL(tab.url).hostname;
            label = label ? `${label} (${host})` : host;
          } catch (e) {}
        }
        opt.textContent = `[Tab ${tab.id}] ${label || "Untitled"}`;
        debugTabSelect.appendChild(opt);
      }
    }

    // Also include any tabs seen in logs that are no longer active
    const loggedTabIds = new Set(debugLog.map((l) => l.tabId).filter((id) => typeof id === "number"));
    for (const id of loggedTabIds) {
      if (!knownTabIds.has(id)) {
        const opt = document.createElement("option");
        opt.value = String(id);
        opt.textContent = `[Tab ${id}] (Closed / Previous)`;
        debugTabSelect.appendChild(opt);
      }
    }

    if ([...debugTabSelect.options].some((o) => o.value === currentVal)) {
      debugTabSelect.value = currentVal;
    } else {
      debugTabSelect.value = "all";
    }
  }

  function getTabDisplay(tabId) {
    if (typeof tabId !== "number") return "";
    const found = openTabs.find((t) => t.id === tabId);
    if (found && found.title) {
      return `Tab ${tabId} ("${found.title.slice(0, 30)}")`;
    }
    return `Tab ${tabId}`;
  }

  async function refreshDebugLogsOnly() {
    try {
      const response = await sendFilterMessage("getFilterData");
      debugLog = response.debugLog || [];
      if (response.stats) {
        for (const cat of ["blocked", "allowed", "cosmetic", "scriptlet"]) {
          const el = document.getElementById(`stat-${cat}`);
          if (el) el.textContent = String(response.stats[cat] || 0);
        }
      }
      await updateOpenTabs();
      renderDebugLog();
    } catch (e) {
      console.warn("xPrivacyGuard: Could not refresh debug logs:", e);
    }
  }

  function getFilteredLogEntries() {
    const category = debugCategorySelect ? debugCategorySelect.value : "all";
    const tabFilter = debugTabSelect ? debugTabSelect.value : "all";
    return debugLog.filter((entry) => {
      const matchCat = category === "all" || entry.category === category;
      const matchTab = tabFilter === "all" || String(entry.tabId) === tabFilter;
      return matchCat && matchTab;
    });
  }

  function formatLogEntry(entry) {
    const tabInfo = typeof entry.tabId === "number" ? ` · ${getTabDisplay(entry.tabId)}` : "";
    return `${new Date(entry.time).toLocaleString()} · ${entry.category}${tabInfo} · ${entry.hostname || "unknown"} · ${entry.message}`;
  }

  function renderDebugLog() {
    const entries = getFilteredLogEntries();
    debugLogContainer.replaceChildren();
    if (!entries.length) {
      const empty = document.createElement("div");
      empty.className = "empty-notice";
      empty.textContent = "No matching debug events.";
      debugLogContainer.appendChild(empty);
      return;
    }
    for (const entry of entries) {
      const row = document.createElement("div");
      row.className = "debug-entry";
      row.style.userSelect = "text";
      row.textContent = formatLogEntry(entry);
      debugLogContainer.appendChild(row);
    }
  }

  async function updateList(id, update) {
    try {
      const response = await sendFilterMessage("setFilterListEnabled", { id, enabled: update.enabled });
      filterLists = filterLists.map((list) => list.id === id ? response.list : list);
      renderFilterLists();
      showSavedBanner();
    } catch (error) {
      showError(error);
      await loadFilterData();
    }
  }

  async function runListAction(action, data) {
    try {
      await sendFilterMessage(action, data);
      await loadFilterData();
      showSavedBanner();
    } catch (error) {
      showError(error);
      await loadFilterData();
    }
  }

  async function loadFilterData() {
    const response = await sendFilterMessage("getFilterData");
    filterLists = response.lists || [];
    customRules = response.rules || [];
    debugLog = response.debugLog || [];
    document.getElementById("filter-debug").checked = response.debug === true;
    document.getElementById("disable-filter-stats").checked = response.disableStats === true;
    document.getElementById("filter-update-hours").value = String(response.updateHours || 24);
    for (const category of ["blocked", "allowed", "cosmetic", "scriptlet"]) {
      document.getElementById(`stat-${category}`).textContent = String(response.stats[category] || 0);
    }
    renderFilterLists();
    renderCustomRules();
    await updateOpenTabs();
    renderDebugLog();
  }

  async function persistCustomRules() {
    try {
      const response = await sendFilterMessage("saveCustomFilterRules", { rules: customRules });
      customRules = response.rules;
      renderCustomRules();
      showSavedBanner();
    } catch (error) {
      showError(error);
      await loadFilterData();
    }
  }

  async function saveCustomRule(id, update) {
    customRules = customRules.map((item) => item.id === id ? { ...item, ...update } : item);
    await persistCustomRules();
  }

  function renderMutedSites() {
    mutedSitesListEl.innerHTML = "";
    if (mutedSites.length === 0) {
      const emptyDiv = document.createElement("div");
      emptyDiv.className = "empty-notice";
      emptyDiv.textContent = "No sites muted";
      mutedSitesListEl.appendChild(emptyDiv);
      return;
    }

    mutedSites.forEach((site) => {
      const itemDiv = document.createElement("div");
      itemDiv.className = "domain-item";
      const textSpan = document.createElement("span");
      textSpan.textContent = site;
      const removeBtn = document.createElement("button");
      removeBtn.className = "remove-btn";
      removeBtn.textContent = "✕";
      removeBtn.title = `Remove ${site}`;
      removeBtn.setAttribute("aria-label", `Remove ${site}`);
      removeBtn.onclick = () => removeMutedSite(site);
      itemDiv.appendChild(textSpan);
      itemDiv.appendChild(removeBtn);
      mutedSitesListEl.appendChild(itemDiv);
    });
  }

  async function saveMutedSites() {
    try {
      await chrome.storage.local.set({ mutedSites });
      if (chrome.runtime && chrome.runtime.sendMessage) {
        chrome.runtime.sendMessage({ action: "syncMutedSites" }).catch(() => {});
      }
      showSavedBanner();
    } catch (err) {
      console.error("xPrivacyGuard: Could not save muted sites:", err);
      showError(err);
    }
  }

  async function removeMutedSite(siteToRemove) {
    mutedSites = mutedSites.filter((s) => s.toLowerCase() !== siteToRemove.toLowerCase());
    renderMutedSites();
    await saveMutedSites();
  }

  async function addMutedSite() {
    const cleaned = cleanDomain(newMutedSiteInput.value);
    if (!cleaned) return;
    if (!mutedSites.some((s) => s.toLowerCase() === cleaned.toLowerCase())) {
      mutedSites.push(cleaned);
      newMutedSiteInput.value = "";
      renderMutedSites();
      await saveMutedSites();
    }
  }

  function renderDomains() {
    domainListEl.innerHTML = "";
    if (excludedDomains.length === 0) {
      const emptyDiv = document.createElement("div");
      emptyDiv.className = "empty-notice";
      emptyDiv.textContent = "No domains excluded";
      domainListEl.appendChild(emptyDiv);
      return;
    }

    excludedDomains.forEach((domain) => {
      const itemDiv = document.createElement("div");
      itemDiv.className = "domain-item";
      const textSpan = document.createElement("span");
      textSpan.textContent = domain;
      const removeBtn = document.createElement("button");
      removeBtn.className = "remove-btn";
      removeBtn.textContent = "✕";
      removeBtn.title = `Remove ${domain}`;
      removeBtn.onclick = () => removeDomain(domain);
      itemDiv.appendChild(textSpan);
      itemDiv.appendChild(removeBtn);
      domainListEl.appendChild(itemDiv);
    });
  }

  async function loadSettings() {
    const data = await chrome.storage.local.get(["mode", "enableGPC", "excludedDomains", "mutedSites"]);
    const mode = data.mode === "cautious" || data.mode === "extreme"
      ? "cautious"
      : "daily";
    modeSelect.value = mode;
    if (data.mode !== mode) {
      await chrome.storage.local.set({ mode });
    }
    gpcCheckbox.checked = data.enableGPC !== false;
    excludedDomains = data.excludedDomains || [];
    renderDomains();
    mutedSites = data.mutedSites || [];
    renderMutedSites();
    await loadFilterData();
  }

  async function saveSettings(syncExclusions = false, syncScripts = false) {
    await chrome.storage.local.set({
      mode: modeSelect.value,
      enableGPC: gpcCheckbox.checked,
      excludedDomains
    });
    if (syncExclusions || syncScripts) {
      const response = await chrome.runtime.sendMessage({ action: "syncContentScripts" });
      if (!response || !response.success) {
        throw new Error(response && response.error ? response.error : "Could not sync extension settings.");
      }
    }
    showSavedBanner();
  }

  async function removeDomain(domainToRemove) {
    excludedDomains = excludedDomains.filter((d) => d.toLowerCase() !== domainToRemove.toLowerCase());
    renderDomains();
    try {
      await saveSettings(true);
    } catch (err) {
      console.error("xPrivacyGuard: Could not update domain exclusions:", err);
      alert(`Could not update domain exclusions: ${err.message}`);
    }
  }

  async function addDomain() {
    const cleaned = cleanDomain(newDomainInput.value);
    if (!cleaned) return;
    if (!excludedDomains.includes(cleaned)) {
      excludedDomains.push(cleaned);
      newDomainInput.value = "";
      renderDomains();
      try {
        await saveSettings(true);
      } catch (err) {
        console.error("xPrivacyGuard: Could not update domain exclusions:", err);
        alert(`Could not update domain exclusions: ${err.message}`);
      }
    }
  }

  async function addFilterList() {
    try {
      await sendFilterMessage("addFilterList", {
        name: document.getElementById("new-list-name").value,
        url: document.getElementById("new-list-url").value
      });
      document.getElementById("new-list-name").value = "";
      document.getElementById("new-list-url").value = "";
      await loadFilterData();
      showSavedBanner();
    } catch (error) {
      showError(error);
      await loadFilterData();
    }
  }

  async function addCustomRule() {
    const input = document.getElementById("new-filter-rule");
    const rule = input.value.trim();
    if (!rule) return;
    customRules.push({ id: crypto.randomUUID(), rule, enabled: true });
    input.value = "";
    await persistCustomRules();
  }

  modeSelect.addEventListener("change", async () => {
    try {
      await saveSettings(false, true);
    } catch (err) {
      console.error("xPrivacyGuard: Could not update protection mode:", err);
      alert(`Could not update protection mode: ${err.message}`);
    }
  });
  gpcCheckbox.addEventListener("change", () => saveSettings().catch(showError));
  addDomainBtn.addEventListener("click", addDomain);
  if (addMutedSiteBtn) addMutedSiteBtn.addEventListener("click", addMutedSite);
  if (newMutedSiteInput) {
    newMutedSiteInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") addMutedSite();
    });
  }
  newDomainInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") addDomain();
  });
  document.getElementById("add-list-btn").addEventListener("click", addFilterList);
  document.getElementById("add-filter-rule-btn").addEventListener("click", addCustomRule);
  document.getElementById("new-filter-rule").addEventListener("keydown", (event) => {
    if (event.key === "Enter") addCustomRule();
  });
  document.getElementById("filter-update-hours").addEventListener("change", async (event) => {
    try {
      await sendFilterMessage("setFilterUpdateHours", { hours: event.target.value });
      showSavedBanner();
    } catch (error) {
      showError(error);
      await loadFilterData();
    }
  });
  document.getElementById("filter-debug").addEventListener("change", async (event) => {
    const requestedState = event.target.checked;
    try {
      if (requestedState) {
        const granted = await chrome.permissions.request({
          permissions: ["declarativeNetRequestFeedback"]
        });
        if (!granted) {
          throw new Error("DNR match feedback permission was not granted.");
        }
      } else {
        await sendFilterMessage("setFilterDebug", { enabled: false });
      }
      if (requestedState) await sendFilterMessage("setFilterDebug", { enabled: true });
      await loadFilterData();
      showSavedBanner();
    } catch (error) {
      event.target.checked = !requestedState;
      showError(error);
    }
  });
  if (debugCategorySelect) {
    debugCategorySelect.addEventListener("change", () => renderDebugLog());
  }
  if (debugTabSelect) {
    debugTabSelect.addEventListener("change", () => renderDebugLog());
  }
  if (refreshDebugLogBtn) {
    refreshDebugLogBtn.addEventListener("click", async () => {
      await refreshDebugLogsOnly();
      showSavedBanner();
    });
  }

  if (copyDebugLogBtn) {
    copyDebugLogBtn.addEventListener("click", async () => {
      const entries = getFilteredLogEntries();
      if (!entries.length) {
        showError("No logs to copy.");
        return;
      }
      const text = entries.map(formatLogEntry).join("\n");
      try {
        await navigator.clipboard.writeText(text);
        showSavedBanner();
      } catch (e) {
        // Fallback using textarea execCommand
        const ta = document.createElement("textarea");
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
        showSavedBanner();
      }
    });
  }

  if (exportDebugLogBtn) {
    exportDebugLogBtn.addEventListener("click", () => {
      const entries = getFilteredLogEntries();
      if (!entries.length) {
        showError("No logs to export.");
        return;
      }
      const text = entries.map(formatLogEntry).join("\r\n");
      const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const ts = new Date().toISOString().replace(/[:.]/g, "-");
      a.download = `xprivacyguard-logs-${ts}.txt`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    });
  }

  if (liveDebugLogCheckbox) {
    liveDebugLogCheckbox.addEventListener("change", (e) => {
      isLiveLogEnabled = e.target.checked;
    });
  }

  if (chrome.storage && chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener((changes, namespace) => {
      if (namespace === "local" && changes.mutedSites && Array.isArray(changes.mutedSites.newValue)) {
        mutedSites = changes.mutedSites.newValue;
        renderMutedSites();
      }
      if (namespace === "local" && (changes.filterDebugLog || changes.filterStats)) {
        if (isLiveLogEnabled) {
          if (changes.filterDebugLog && Array.isArray(changes.filterDebugLog.newValue)) {
            debugLog = changes.filterDebugLog.newValue;
          }
          if (changes.filterStats && typeof changes.filterStats.newValue === "object") {
            const stats = changes.filterStats.newValue;
            const totals = { blocked: 0, allowed: 0, cosmetic: 0, scriptlet: 0 };
            for (const site of Object.values(stats || {})) {
              for (const cat of Object.keys(totals)) totals[cat] += (site[cat] || 0);
            }
            for (const cat of Object.keys(totals)) {
              const el = document.getElementById(`stat-${cat}`);
              if (el) el.textContent = String(totals[cat]);
            }
          }
          updateOpenTabs().then(() => renderDebugLog()).catch(() => renderDebugLog());
        }
      }
    });
  }

  document.getElementById("clear-filter-debug-log").addEventListener("click", async () => {
    try {
      await sendFilterMessage("clearFilterDebugLog");
      debugLog = [];
      populateTabSelect();
      renderDebugLog();
      showSavedBanner();
    } catch (error) {
      showError(error);
    }
  });
  document.getElementById("reset-filter-stats").addEventListener("click", async () => {
    try {
      await sendFilterMessage("resetFilterStats");
      for (const category of ["blocked", "allowed", "cosmetic", "scriptlet"]) {
        const el = document.getElementById(`stat-${category}`);
        if (el) el.textContent = "0";
      }
      showSavedBanner();
    } catch (error) {
      showError(error);
    }
  });
  document.getElementById("disable-filter-stats").addEventListener("change", async (event) => {
    const disabled = event.target.checked;
    try {
      await sendFilterMessage("setDisableFilterStats", { disabled });
      showSavedBanner();
    } catch (error) {
      event.target.checked = !disabled;
      showError(error);
    }
  });

  async function exportSettings() {
    try {
      const data = await chrome.storage.local.get([
        "mode",
        "enableGPC",
        "excludedDomains",
        "mutedSites",
        "filterDisabledDomains",
        "filterLists",
        "customFilterRules",
        "filterUpdateHours",
        "disableFilterStats",
        "filterDebug"
      ]);
      const payload = buildSettingsExportPayload(data, {
        mode: modeSelect ? modeSelect.value : "daily",
        enableGPC: gpcCheckbox ? gpcCheckbox.checked : true,
        excludedDomains,
        mutedSites,
        filterLists,
        customFilterRules: customRules,
        filterUpdateHours: document.getElementById("filter-update-hours")?.value
      });
      const jsonStr = JSON.stringify(payload, null, 2);
      const blob = new Blob([jsonStr], { type: "application/json;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const ts = new Date().toISOString().replace(/[:.]/g, "-");
      a.download = `xprivacyguard-settings.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showSavedBanner("Settings exported successfully!");
    } catch (err) {
      console.error("xPrivacyGuard: Could not export settings:", err);
      showError(err);
    }
  }

  async function copySettings() {
    try {
      const data = await chrome.storage.local.get([
        "mode",
        "enableGPC",
        "excludedDomains",
        "mutedSites",
        "filterDisabledDomains",
        "filterLists",
        "customFilterRules",
        "filterUpdateHours",
        "disableFilterStats",
        "filterDebug"
      ]);
      const payload = buildSettingsExportPayload(data, {
        mode: modeSelect ? modeSelect.value : "daily",
        enableGPC: gpcCheckbox ? gpcCheckbox.checked : true,
        excludedDomains,
        mutedSites,
        filterLists,
        customFilterRules: customRules,
        filterUpdateHours: document.getElementById("filter-update-hours")?.value
      });
      const jsonStr = JSON.stringify(payload, null, 2);
      try {
        await navigator.clipboard.writeText(jsonStr);
      } catch (e) {
        const ta = document.createElement("textarea");
        ta.value = jsonStr;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
      }
      showSavedBanner("Settings copied to clipboard!");
    } catch (err) {
      console.error("xPrivacyGuard: Could not copy settings:", err);
      showError(err);
    }
  }

  async function importSettings(file) {
    if (!file) return;
    try {
      const text = await file.text();
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch (e) {
        throw new Error("Invalid JSON file. Please select a valid settings export.");
      }
      const toSet = parseAndValidateImportSettings(parsed);
      await chrome.storage.local.set(toSet);
      await loadSettings();
      await chrome.runtime.sendMessage({ action: "syncContentScripts" });
      await chrome.runtime.sendMessage({ action: "syncMutedSites" });
      showSavedBanner("Settings imported successfully!");
    } catch (err) {
      console.error("xPrivacyGuard: Could not import settings:", err);
      showError(err);
    } finally {
      if (importSettingsFileInput) {
        importSettingsFileInput.value = "";
      }
    }
  }

  if (exportSettingsBtn) exportSettingsBtn.addEventListener("click", exportSettings);
  if (copySettingsBtn) copySettingsBtn.addEventListener("click", copySettings);
  if (importSettingsBtn && importSettingsFileInput) {
    importSettingsBtn.addEventListener("click", () => importSettingsFileInput.click());
    importSettingsFileInput.addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) importSettings(file);
    });
  }

  loadSettings().catch((error) => {
    console.error("xPrivacyGuard: Could not load settings:", error);
    showError(error);
  });
});
}


function cleanDomain(input) {
  if (!input) return "";
  let str = input.trim().toLowerCase();
  str = str.replace(/^(https?:\/\/)?(www\.)?/, "");
  str = str.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
  return str;
}

function buildSettingsExportPayload(data, fallback = {}) {
  return {
    version: 1,
    exportedAt: new Date().toISOString(),
    settings: {
      mode: data.mode === "cautious" || data.mode === "extreme" ? "cautious" : (data.mode || fallback.mode || "daily"),
      enableGPC: data.enableGPC !== undefined ? data.enableGPC !== false : (fallback.enableGPC !== false),
      excludedDomains: Array.isArray(data.excludedDomains) ? data.excludedDomains : (fallback.excludedDomains || []),
      mutedSites: Array.isArray(data.mutedSites) ? data.mutedSites : (fallback.mutedSites || []),
      filterDisabledDomains: Array.isArray(data.filterDisabledDomains) ? data.filterDisabledDomains : (fallback.filterDisabledDomains || []),
      filterLists: Array.isArray(data.filterLists) ? data.filterLists : (fallback.filterLists || []),
      customFilterRules: Array.isArray(data.customFilterRules) ? data.customFilterRules : (fallback.customFilterRules || []),
      filterUpdateHours: Number(data.filterUpdateHours) || Number(fallback.filterUpdateHours) || 24,
      disableFilterStats: data.disableFilterStats === true || fallback.disableFilterStats === true,
      filterDebug: data.filterDebug === true || fallback.filterDebug === true
    }
  };
}

function parseAndValidateImportSettings(parsed) {
  if (!parsed || typeof parsed !== "object") {
    throw new Error("Invalid settings file format.");
  }
  const settings = (parsed.settings && typeof parsed.settings === "object") ? parsed.settings : parsed;
  const toSet = {};
  if (settings.mode === "daily" || settings.mode === "cautious" || settings.mode === "extreme") {
    toSet.mode = settings.mode === "extreme" ? "cautious" : settings.mode;
  }
  if (typeof settings.enableGPC === "boolean") {
    toSet.enableGPC = settings.enableGPC;
  }
  if (Array.isArray(settings.excludedDomains)) {
    toSet.excludedDomains = Array.from(new Set(settings.excludedDomains.map(cleanDomain).filter(Boolean)));
  }
  if (Array.isArray(settings.mutedSites)) {
    toSet.mutedSites = Array.from(new Set(settings.mutedSites.map(cleanDomain).filter(Boolean)));
  }
  if (Array.isArray(settings.filterDisabledDomains)) {
    toSet.filterDisabledDomains = Array.from(new Set(settings.filterDisabledDomains.map(cleanDomain).filter(Boolean)));
  }
  if (Array.isArray(settings.filterLists)) {
    toSet.filterLists = settings.filterLists.filter((l) => l && typeof l === "object" && l.name && l.url);
  }
  if (Array.isArray(settings.customFilterRules)) {
    toSet.customFilterRules = settings.customFilterRules.filter((r) => r && typeof r === "object" && typeof r.rule === "string");
  }
  if (settings.filterUpdateHours !== undefined) {
    const h = Number(settings.filterUpdateHours);
    if ([12, 24, 48, 168].includes(h)) {
      toSet.filterUpdateHours = h;
    }
  }
  if (typeof settings.disableFilterStats === "boolean") {
    toSet.disableFilterStats = settings.disableFilterStats;
  }
  if (typeof settings.filterDebug === "boolean") {
    toSet.filterDebug = settings.filterDebug;
  }
  if (Object.keys(toSet).length === 0) {
    throw new Error("No recognizable settings found in the imported file.");
  }
  return toSet;
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    cleanDomain,
    buildSettingsExportPayload,
    parseAndValidateImportSettings
  };
}