# Windows architecture and implementation gates

## Required data path

The desktop UI must not pretend that a tunnel exists. A Windows service will own privileged networking and communicate with the UI over a restricted local IPC channel. The service is responsible for the Aether Cloudflare Zero Trust/MASQUE session, packet transport, route policy enforcement, and recovery after service or network interruption.

## Per-application policy

The policy is include-based. An executable is selected explicitly for WARP; unselected applications bypass WARP. A separate full-traffic switch may route all eligible traffic while retaining the saved include list for returning to application mode.

The Windows packet path must identify originating processes and enforce policy before packets can leave the host. Wintun alone does not provide per-process routing. The implementation must evaluate Windows Filtering Platform (WFP) enforcement and the appropriate packet interception/redirect strategy, including TCP, UDP, IPv4, IPv6, DNS, loopback, LAN exclusions, and tunnel-socket bypass.

## Fail-closed requirement

When WARP is selected for an application, tunnel loss must not silently fall back to the physical interface. The service must install and verify blocking policy before enabling selected-app routing, preserve that policy during reconnect, and remove it only after an explicit user action or orderly shutdown policy.

## Aether integration gate

The Android implementation and Aether source must be inspected at the exact dependency revision before selecting the Windows FFI boundary. The desktop SOCKS listener is not an acceptable substitute for transparent Windows application routing. Any raw packet interface required by the Windows datapath must be implemented and tested as part of the native integration.

## Current foundation milestone

The current source provides a WPF Cyber Dark shell, live process inventory, durable local storage for selected executable paths, system-tray lifecycle, and optional startup-at-login. Managed Aether identity and Zero Trust email-code bindings exist but are not yet connected to UI flows. The application does not yet route traffic, establish a MASQUE packet tunnel, install a networking service, or enforce a kill switch. These functions must not be represented as operational until implemented and tested on Windows.
