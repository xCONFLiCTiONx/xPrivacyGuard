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

  let activeTab = null;
  let activeDomain = "";
  let isWebPage = false;
  let excludedDomains = [];

  function cleanDomain(input) {
    if (!input) return "";
    let str = input.trim().toLowerCase();
    str = str.replace(/^(https?:\/\/)?(www\.)?/, "");
    str = str.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    return str;
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
    // Get active tab
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

    // Get stored data
    const data = await chrome.storage.local.get(["mode", "excludedDomains"]);
    if (data.mode) {
      modeSelect.value = data.mode;
    }
    excludedDomains = data.excludedDomains || [];

    updateUI();
  }

  function updateUI() {
    // Update active tab card
    if (!isWebPage || !activeDomain) {
      currentDomainEl.textContent = "Non-web page";
      statusBadgeEl.textContent = "Disabled";
      statusBadgeEl.className = "badge badge-disabled";
      toggleBtn.disabled = true;
      toggleBtn.textContent = "Exclusion unavailable";
      toggleBtn.className = "btn-secondary";
    } else {
      currentDomainEl.textContent = activeDomain;
      const excluded = isDomainExcluded(activeDomain, excludedDomains);
      if (excluded) {
        statusBadgeEl.textContent = "Excluded";
        statusBadgeEl.className = "badge badge-excluded";
        toggleBtn.disabled = false;
        toggleBtn.textContent = "Re-enable Protection";
        toggleBtn.className = "btn-secondary";
      } else {
        statusBadgeEl.textContent = "Protected";
        statusBadgeEl.className = "badge badge-protected";
        toggleBtn.disabled = false;
        toggleBtn.textContent = `Exclude ${activeDomain}`;
        toggleBtn.className = "btn-warning";
      }
    }

    // Update excluded list
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

        const removeBtn = document.createElement("button");
        removeBtn.className = "remove-btn";
        removeBtn.textContent = "✕";
        removeBtn.title = `Remove ${domain}`;
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
    updateUI();
  }

  async function toggleActiveDomainExclusion() {
    if (!isWebPage || !activeDomain) return;

    const currentlyExcluded = isDomainExcluded(activeDomain, excludedDomains);
    let updated = [];

    if (currentlyExcluded) {
      // Remove matches
      updated = excludedDomains.filter((d) => {
        const target = activeDomain.toLowerCase();
        const item = d.toLowerCase();
        return !(target === item || target.endsWith("." + item));
      });
    } else {
      // Add activeDomain
      updated = Array.from(new Set([...excludedDomains, activeDomain]));
    }

    await saveExcludedDomains(updated);

    // Refresh active tab automatically
    if (activeTab && activeTab.id) {
      chrome.tabs.reload(activeTab.id);
    }
  }

  async function removeDomain(domainToRemove) {
    const updated = excludedDomains.filter((d) => d.toLowerCase() !== domainToRemove.toLowerCase());
    await saveExcludedDomains(updated);

    // If active tab was excluded by this domain, reload it
    if (isWebPage && activeTab && activeTab.id) {
      const wasExcluded = isDomainExcluded(activeDomain, [domainToRemove]);
      if (wasExcluded) {
        chrome.tabs.reload(activeTab.id);
      }
    }
  }

  async function addDomainFromInput() {
    const raw = newDomainInput.value;
    const cleaned = cleanDomain(raw);
    if (!cleaned) return;

    if (!excludedDomains.includes(cleaned)) {
      const updated = [...excludedDomains, cleaned];
      newDomainInput.value = "";
      await saveExcludedDomains(updated);

      // Reload active tab if it matches
      if (isWebPage && activeTab && activeTab.id && isDomainExcluded(activeDomain, [cleaned])) {
        chrome.tabs.reload(activeTab.id);
      }
    }
  }

  // Event Listeners
  toggleBtn.addEventListener("click", toggleActiveDomainExclusion);

  addDomainBtn.addEventListener("click", addDomainFromInput);
  newDomainInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") addDomainFromInput();
  });

  modeSelect.addEventListener("change", () => {
    chrome.storage.local.set({ mode: modeSelect.value });
  });

  openOptionsLink.addEventListener("click", (e) => {
    e.preventDefault();
    chrome.runtime.openOptionsPage();
  });

  loadState();
});
