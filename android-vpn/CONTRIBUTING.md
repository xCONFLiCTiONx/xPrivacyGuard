# Contributing to xPrivacyGuard

Thanks for helping improve xPrivacyGuard. Contributions should preserve the project's privacy-first design and avoid exposing users' VPN or DNS configuration.

## Before opening a pull request

1. Check existing issues and pull requests for related work.
2. Keep changes focused and explain the user-visible problem they solve.
3. Never include WireGuard private keys, Zero Trust credentials, signing material, personal filter subscriptions, or device-specific data.
4. Prefer small changes that can be reviewed independently.

## Android changes

- Keep the app compatible with the minimum supported Android API level.
- Preserve the separation between packet inspection, DNS resolution, filtering, and VPN transport.
- Avoid introducing network requests that bypass the configured VPN/DNS protections.
- Add or update unit tests for parsing, filtering, packet handling, and configuration behavior where applicable.

## Validation

Run the relevant unit tests before submitting changes:

```powershell
.\\gradlew.bat testDebugUnitTest
```

For build or deployment changes, verify the Release build as well. Do not commit generated APKs, local keystores, or machine-specific SDK paths.

## Pull request description

Include the problem, the approach, test results, and any behavior or compatibility changes. If a change affects VPN routing, DNS privacy, or filter matching, describe the security implications explicitly.
