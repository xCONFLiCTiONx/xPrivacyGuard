# Windows networking research

## Findings

Windows Filtering Platform (WFP) exposes application identity at its ALE layers. In particular, `FWPM_CONDITION_ALE_APP_ID` is available at the authorization and connect-redirection layers. The ALE authorization-connect layer covers TCP connection requests and the first outbound UDP packet for a destination tuple; ALE flow state then tracks the connection.

WFP connect redirection is a redirection-to-proxy mechanism: the application connects to a local proxy service, which maintains the redirected original connection and a separate outbound connection. It is not a direct "send this process through a TUN interface" switch.

Wintun provides a Layer-3 virtual adapter with userspace packet read/write. It is a packet transport interface, not an application-identity policy engine. It cannot independently implement include-based per-process routing.

## Engineering consequence

The Windows implementation needs two coordinated parts:

1. An application-aware enforcement layer using WFP and stable executable identity.
2. A packet/flow data path that forwards only included applications into Aether's raw-IP MASQUE tunnel, preserving the original application semantics for TCP and UDP.

The implementation must not claim per-app support based only on installing Wintun or changing the system default route. A local proxy may be used internally only if it is completely transparent to applications and correctly handles both TCP and UDP; it must not become a user-configured proxy mode.

## References

- [Microsoft: Application Layer Enforcement (ALE)](https://learn.microsoft.com/en-us/windows/win32/fwp/application-layer-enforcement--ale-)
- [Microsoft: ALE Layers](https://learn.microsoft.com/en-us/windows/win32/fwp/ale-layers)
- [Microsoft: Using Bind or Connect Redirection](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/using-bind-or-connect-redirection)
- [Microsoft: ALE Stateful Filtering](https://learn.microsoft.com/en-us/windows/win32/fwp/ale-stateful-filtering)
- [Wintun: Layer 3 TUN Driver for Windows](https://www.wintun.net/)
