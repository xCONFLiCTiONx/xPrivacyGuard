document.addEventListener("DOMContentLoaded", () => {
  const modeSelect = document.getElementById("mode");
  const domainListEl = document.getElementById("domain-list");
  const newDomainInput = document.getElementById("new-domain-input");
  const addDomainBtn = document.getElementById("add-domain-btn");
  const savedBanner = document.getElementById("saved-banner");

  let excludedDomains = [];

  function cleanDomain(input) {
    if (!input) return "";
    let str = input.trim().toLowerCase();
    str = str.replace(/^(https?:\/\/)?(www\.)?/, "");
    str = str.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
    return str;
  }

  function showSavedBanner() {
    savedBanner.style.display = "block";
    setTimeout(() => {
      savedBanner.style.display = "none";
    }, 2000);
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
    const data = await chrome.storage.local.get(["mode", "excludedDomains"]);
    if (data.mode) {
      modeSelect.value = data.mode;
    }
    excludedDomains = data.excludedDomains || [];
    renderDomains();
  }

  async function saveSettings() {
    await chrome.storage.local.set({
      mode: modeSelect.value,
      excludedDomains
    });
    showSavedBanner();
  }

  async function removeDomain(domainToRemove) {
    excludedDomains = excludedDomains.filter((d) => d.toLowerCase() !== domainToRemove.toLowerCase());
    renderDomains();
    await saveSettings();
  }

  async function addDomain() {
    const cleaned = cleanDomain(newDomainInput.value);
    if (!cleaned) return;
    if (!excludedDomains.includes(cleaned)) {
      excludedDomains.push(cleaned);
      newDomainInput.value = "";
      renderDomains();
      await saveSettings();
    }
  }

  modeSelect.addEventListener("change", saveSettings);
  addDomainBtn.addEventListener("click", addDomain);
  newDomainInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") addDomain();
  });

  loadSettings();
});
