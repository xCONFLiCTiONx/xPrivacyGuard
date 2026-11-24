# Android raw-IP tunnel FFI reference patch

This is the exact Android-side patch copied from xCONFLiCTiONx/xPrivacyGuard `main` as a reference for the Windows port.

It is **not claimed to apply** to the Windows Aether dependency revision. Its diff header expects the `aether/src/ffi.rs` blob to begin with `dac5666`, while the inspected Aether pin `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2` has a different FFI source blob. Rebase and compile against a verified commit before using the exports in a release.

The C ABI declarations are in `native/aether/include/aether.h`. The managed P/Invoke wrapper is in `src/xPrivacyGuard.Windows/Native/AetherRawTunnel.cs`.
