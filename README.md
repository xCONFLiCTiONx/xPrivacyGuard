# xPrivacyGuard

> **Advanced Browser Fingerprint & Tracking Surface Protection**

**xPrivacyGuard** is a lightweight, high-performance Chrome Extension (Manifest V3) designed to prevent invasive browser fingerprinting and client-side tracking vectors while providing effortless domain management for interactive web applications.

---

## 🌟 Key Features

* **Instant Site Exclusion**: Easily exclude trusted domains (such as interactive image editors, web canvas applications, or portfolio tools) directly from the extension popup.
* **Automatic Tab Refresh**: Automatically reloads active tabs when toggling site exclusion so pages resume full functionality immediately.
* **Adaptive Contrast Icon**: Dynamically detects system/browser theme (`prefers-color-scheme`) to render high-contrast toolbar icons (white icon on dark themes, black icon on light themes).
* **Dual Protection Profiles**: Switch seamlessly between **Normal** and **Strict** protection levels.
* **Modern Dark Interface**: Sleek, accessible dark-themed user interface for both the extension popup and options page.

---

## 🛡️ Protection Profiles

### **Normal Mode** (Recommended)
Balanced protection against common tracking scripts while maintaining site compatibility:
* **Canvas Protection**: Overrides `toDataURL`, `toBlob`, and `getImageData` fingerprinting attempts.
* **WebGL Masking**: Normalizes graphics renderer and vendor telemetry parameters.
* **Audio Noise**: Prevents background audio spectrum analysis fingerprinting.
* **Navigator Normalization**: Masks client platform details.

### **Strict Mode**
Maximum hardening for high-privacy sessions:
* Includes all **Normal Mode** protections.
* **AudioContext Hardening**: Blocks `OfflineAudioContext` enumeration.
* **WebRTC Protection**: Minimizes local IP leak vectors.
* **Sensor & Hardware API Guard**: Blocks Battery API, Gamepad API, and Sensor API tracking surfaces.

---

## ⚡ Domain Exclusion Workflow

When visiting complex web applications that rely heavily on Canvas or AudioContext APIs (such as image tools or audio editors):

1. Click the **xPrivacyGuard** icon in your browser toolbar.
2. Click **Exclude [domain]** (e.g. `portfolio.com`).
3. The active tab will automatically reload with protection safely bypassed for that domain.
4. Re-enable protection at any time with a single click from the popup or Options page.

---

## 🚀 Installation

1. Clone or download this repository to your local machine.
2. Open Chrome and navigate to `chrome://extensions`.
3. Enable **Developer mode** using the toggle in the top-right corner.
4. Click **Load unpacked** and select the `xPrivacyGuard` folder.
5. Pin **xPrivacyGuard** to your browser toolbar for easy access.

---

## ℹ️ Technical Notes & Scope

* **Client-Side Scope**: xPrivacyGuard specifically targets JavaScript-based browser fingerprinting and telemetry surface APIs.
* **Network Privacy**: To hide IP addresses, TCP/TLS fingerprints, or location telemetry, pair xPrivacyGuard with a trusted VPN or proxy service.

---

## 📁 Repository Structure

```
xPrivacyGuard/
├── manifest.json      # Extension Manifest V3 configuration
├── background.js      # Dynamic scripting manager & icon renderer
├── inject.js          # Main-world fingerprint protection script
├── renderIcon.js      # System theme detection script
├── popup.html         # Extension toolbar popup interface
├── popup.js           # Popup controller & tab detection
├── settings.html      # Extension options page
├── settings.js        # Options page settings manager
├── icon.svg           # Scalable vector extension icon
└── README.md          # Documentation
```
