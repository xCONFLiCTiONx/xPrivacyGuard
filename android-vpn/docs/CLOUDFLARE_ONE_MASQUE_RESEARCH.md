# Cloudflare One MASQUE implementation research

Status: protocol groundwork committed; Cloudflare enrollment integration blocked on a supported third-party contract.

## Verified Cloudflare One client architecture

Cloudflare documents three separate connections:

1. WARP tunnel: WireGuard or MASQUE over UDP, carrying IP packets to Gateway.
2. Gateway DNS-over-HTTPS: carried inside the WARP tunnel.
3. Device orchestration over HTTPS: user registration, device posture, and device-profile settings. This connection remains outside split-tunnel routing.

Cloudflare documents MASQUE as HTTP/3 and the default tunnel protocol. The documented MASQUE ingress uses UDP 443 (with documented fallback ports). This is not an ordinary HTTPS proxy and not just a generic QUIC socket.

References:
- Client architecture: https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/route-traffic/client-architecture/
- Tunnel protocol settings: https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/settings/
- Firewall destinations and ports: https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/deployment/firewall/
- Android manual enrollment: https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/deployment/manual-deployment/

## Verified Android enrollment behavior

Cloudflare's documented Android process is performed by the Cloudflare One Agent app:

1. Install and open Cloudflare One Agent.
2. Accept its privacy policy.
3. Enter the Zero Trust team name.
4. Complete the organization's configured authentication.
5. Select Install VPN Profile and approve Android's VPN permission.
6. Enable the connection.

Cloudflare also documents the `cf1app://oneapp.cloudflare.com/team?name=<team>` enrollment link. This is a deep link into the official Cloudflare One Agent, not a third-party OAuth callback or a credential-issuing API for xPrivacyGuard.

## What this means for xPrivacyGuard

- Android `VpnService` remains the OS TUN interface. It does not require WireGuard as the transport.
- A browser login or Cloudflare Access login is not sufficient to enroll a WARP device.
- A compatible client needs the device-orchestration/enrollment protocol, registered device identity, authentication/session credentials, profile settings, Gateway DNS behavior, and the MASQUE CONNECT-IP tunnel.
- Cloudflare's public docs describe the official client behavior and manual Android enrollment, but do not publish a supported third-party Android SDK or complete third-party device enrollment API/contract.
- The official Android app cannot simply be used as an embedded tunnel engine. Android permits only one active VPN service per user/profile, so running its VPN and xPrivacyGuard's packet-filtering VPN concurrently is not a viable design.

## Code now in this branch

- `TunnelTransport.kt`: a boundary between the existing Android TUN/filter pipeline and a future transport implementation.
- `MasqueCapsuleCodec.kt`: QUIC varint, RFC 9297 capsule framing, and CONNECT-IP HTTP Datagram context-ID-zero packet framing.
- `MasqueCapsuleCodecTest.kt`: unit tests for varint widths, capsule framing, IP datagram framing, and malformed inputs.

The codec is protocol framing only. It is not a QUIC/HTTP/3 stack, does not perform TLS, does not negotiate CONNECT-IP with Cloudflare, and does not authenticate or enroll a device.

## Hard implementation gate

Do not ship a fake "Cloudflare authenticated" state, scrape private API endpoints, reuse official-app cookies, or treat a Cloudflare dashboard/API token as a WARP device credential.

A generic MASQUE CONNECT-IP implementation can be built and tested against a compatible MASQUE endpoint. That alone will not make it connect to Cloudflare One: the Cloudflare-specific device orchestration and authorization contract is also required. We need a documented third-party integration contract or an explicit Cloudflare-supported enrollment mechanism before we can safely implement that part.

Until that contract exists, keep the working WireGuard path intact and do not label an independent MASQUE tunnel as a Cloudflare One connection.

## Next milestones

1. Add a real HTTP/3 + QUIC CONNECT-IP client implementation and test it against a controlled MASQUE endpoint.
2. Implement CONNECT-IP address/route capsule handling and packet forwarding.
3. Identify a supported Cloudflare third-party enrollment and authorization interface.
4. Integrate Cloudflare device orchestration and Gateway DNS only if that interface is documented and supported.
5. Keep WireGuard as a selectable fallback until Cloudflare end-to-end tests pass.
