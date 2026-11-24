# PrivacyFilters

Site-specific and general network/privacy filter lists for **xPrivacy Guard**, in Adblock-style syntax (`||domain^`, `$important`, `!` comments). Each list targets ads, ad injection and telemetry while avoiding breakage of the CDNs and player dependencies the site needs to work.

## Lists

| List | Path | Focus |
| --- | --- | --- |
| Aggressive Privacy & Ad Filters | [xCONFLiCTiONx/filters.txt](xCONFLiCTiONx/filters.txt) | General-purpose aggressive ad, exchange and tracker blocking |
| Peacock | [Peacock/filters.txt](Peacock/filters.txt) | FreeWheel ad injection, ad-shard wildcards, telemetry |
| Pluto TV | [PlutoTV/filters.txt](PlutoTV/filters.txt) | FreeWheel ad injection, Ketch, VideoAmp, Sparrow, tracking |
| Tubi | [Tubi/filters.txt](Tubi/filters.txt) | Ad injection, video ads, tracking pixels, telemetry |
| Pornhub | [Pornhub/filters.txt](Pornhub/filters.txt) | TrafficJunky ad networks, tracking, telemetry |
| XVideos | [XVideos/filters.txt](XVideos/filters.txt) | TrafficFactory ad servers, tracking, telemetry |

## Usage

These lists are self-hosted on xServer (served by the PrivacyFilters service behind Caddy). Add a list's URL to your blocker's custom filter list setting, e.g.:

```
https://filters.home.xconflictionx.cc/Tubi/filters.txt
```

The URL resolves via local DNS (AdGuard Home), so it is only reachable on the home network. You can also copy a file's contents into a local/custom ruleset.

## Compatibility notes

- Streaming sites that use SSAI (server-side ad insertion), such as Pluto TV and Tubi, stitch ads into the main video stream. Hard-blocking those segments crashes the player (e.g. MSE buffer error 3005), so they are intentionally left unblocked.
- Core CDNs (`phncdn.com`, `phprcdn.com`, `xvideos-cdn.com`, `xv-cdn.com`) are intentionally **not** blocked, since they serve scripts, CSS and video.
- Many rules use `$important` so they can't be overridden by other allow rules. Parent-domain rules already cover subdomains.
- Lists contain filters only, with no scriptlets or extension-code dependencies.

## Rules-Exporter.ps1

[Rules-Exporter.ps1](Rules-Exporter.ps1) is a PowerShell helper that converts a `filters.txt` into a Chrome declarativeNetRequest ruleset. It:

1. Reads `rules.json` and `filters.txt` from the current directory.
2. Strips Adblock syntax (`||`, `^`, paths) and skips comments and invalid lines.
3. Merges new, de-duplicated domains into `requestDomains` of **Rule ID 3**.
4. Writes the result to `rules-updated.json`.

```powershell
.\Rules-Exporter.ps1
```

## Contributing

Edit the lists directly in this repository on xServer. When adding a filter, keep it grouped under the matching section header and verify that playback and site functionality still work.

## License

Released under the [MIT License](LICENSE).
