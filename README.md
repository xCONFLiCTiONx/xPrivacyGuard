xPrivacyGuard v2  
================

Browser fingerprint protection.

Features:

- Domain Exclusion: Easily disable protection for trusted sites (e.g., interactive canvas tools, portfolio image tools) directly from the toolbar extension popup.
- Automatic Tab Refresh: Toggling exclusion automatically reloads the active tab so the page works immediately.
- Protection Modes: Normal and Strict modes.

Modes:

NORMAL

- Canvas protection
- WebGL masking
- Audio noise
- Navigator normalization

STRICT

Everything above plus:

- AudioContext blocking
- WebRTC blocking
- Battery API blocking
- Gamepad blocking
- Sensor blocking

Domain Exclusion:

1. Click the xPrivacyGuard icon in the browser toolbar when visiting a website.
2. Click "Exclude [domain]" to disable protection for that site.
3. The page will reload automatically and run without xPrivacyGuard blocking canvas/audio/WebGL.
4. Re-enable protection at any time from the popup or Options page.

Install:

1. Open:

chrome://extensions

2. Enable Developer Mode

3. Load unpacked

4. Select xPrivacyGuard folder

Notes:

This protects JavaScript fingerprinting.

It does not change:

- TLS fingerprint
- TCP fingerprint
- IP address
- Browser engine fingerprint
