# SpeedVPN Privacy Policy

**Last updated: 2026-10-04**

SpeedVPN is a local Android VPN-based traffic-control application. It creates an Android VPN/TUN interface on the user's device to apply speed limits, per-app controls and usage limits. It is not represented as a remote anonymity VPN unless a separate product configuration explicitly provides a remote VPN endpoint.

## Data we process

### Account data
If the user signs in, SpeedVPN processes the email address and authentication tokens required to authenticate the account. Authentication tokens are encrypted locally using Android Keystore-backed AES-GCM storage.

### Device and service data
The application creates a random local device identifier used to associate the Android installation with the user's dashboard session. The application may send VPN state, app version, configured speed limits, session traffic counters and diagnostics to the configured service endpoint when the dashboard/agent feature is enabled.

### Application usage data
When the user enables application statistics and grants Android Usage Access, SpeedVPN reads Android-provided per-UID network usage statistics. These statistics are used for app usage display and quota enforcement. They are not claimed to be packet-exact VPN traffic accounting.

### Diagnostics
The application records local diagnostic events needed to troubleshoot VPN lifecycle, quota and native-tunnel failures. If an optional crash-reporting service is configured by the product operator, crash reports may be transmitted according to that service's configuration.

## VPN data

SpeedVPN uses Android VpnService to route traffic through a local TUN interface and the bundled native tunnel. The application does not claim that local traffic shaping by itself provides anonymity or hides the user's traffic from the underlying network provider.

When a remote VPN endpoint is introduced into a product configuration, the endpoint, encryption and data-handling disclosures must be updated before distribution.

## Security

Control-plane and authentication endpoints must use HTTPS. Cleartext HTTP is disabled by the application network security policy. Authentication tokens are protected with Android Keystore-backed encryption.

## Data retention and deletion

Local authentication and application-control data can be removed by signing out and uninstalling the application. Server-side account data is subject to the configured backend's retention and deletion procedures. Users should use the account/provider deletion mechanism supplied by the service operator for server-side deletion.

## Third parties

SpeedVPN may communicate with its configured authentication/API service and, when explicitly enabled by the product operator, an optional crash-reporting service. The final Google Play Data Safety declaration must match the exact production configuration and enabled third-party services.

## Contact

The production distribution must replace this placeholder with the operator's current privacy contact before publication:
**privacy@example.com**

This document is a product disclosure template and must be reviewed against the final backend, Play Console configuration and applicable law before public release.
