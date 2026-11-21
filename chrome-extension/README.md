# Desktop filter engine

xPrivacyGuard Desktop can download filter lists from HTTP(S) URLs, retain the last successful copy when an update fails, and refresh enabled lists on a configurable schedule. List data and compiled records are stored in IndexedDB; settings and metadata use local extension storage.

Default subscriptions are configured in `filters/defaults.js` (none pre-configured by default). Users can add, enable, disable, refresh, or remove custom lists in Settings. Custom rules can also be managed there, and the popup provides a per-site filtering switch independent of the global protection mode.

The supported network syntax includes ABP URL patterns, exceptions, resource-type modifiers, `$important`, `$third-party`/`$~third-party`, `$domain=`, and `$denyallow=` when supported by the browser's DNR API. `$fetch` is mapped to DNR's `xmlhttprequest` type and cannot distinguish Fetch API from XMLHttpRequest. Compatible rules are compiled to MV3 `declarativeNetRequest` dynamic rules, with a 4,500-rule cap. Redirect rules—including `redirect=noopmp4`—are rejected because replacing media with placeholder content can cause video playback to skip. More complex or unknown modifiers are ignored, not interpreted as if supported.

Cosmetic filters and cosmetic exceptions support ordinary CSS selectors and hostname scopes. Procedural filtering is limited to CSS selectors understood by the browser. Filter-list scriptlets are limited to the local `remove-class` and `remove-node-text` implementations. The extension does not inject playback-control scriptlets or modify video playback. Remote filter lists are never executed as JavaScript. Redirect, regular-expression, and other unimplemented filter syntax is ignored. This is a practical subset, not full uBlock Origin compatibility, and it should not be presented as a drop-in equivalent.

Filter lists are downloaded as untrusted data. URLs are restricted to HTTP(S), list size is capped, selectors and scriptlet arguments are constrained, and remote scriptlet names must match local implementations. Network match counters require the optional DNR feedback permission; ordinary filtering does not. Debug logging is off by default and stored locally.

On Peacock (`peacocktv.com`), the desktop extension additionally blocks FreeWheel requests made by that site. Google DoubleClick requests are covered by the configured network rules. The extension deliberately does not mute, accelerate, pause, or otherwise manipulate video elements: broad ad-marker detection can mistake ordinary player UI and episode transitions for an ad break. Blocking an ad request may still disrupt playback, so ad-only endpoints should be distinguished from shared media-delivery infrastructure. MV3 request rules cannot rewrite VMAP/VAST response bodies; the extension does not spoof empty manifests or patch page `fetch`.

Run the unit checks from the extension source directory with:

```powershell
node .\filters\tests\parser.test.js
node .\filters\tests\engine.test.js
node .\filters\tests\scriptlets.test.js
node .\filters\tests\lists.test.js
node .\filters\tests\badge.test.js
```
