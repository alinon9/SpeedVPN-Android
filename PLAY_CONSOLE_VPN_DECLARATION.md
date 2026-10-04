# Google Play VpnService Declaration — Release Checklist

## Product classification

SpeedVPN's current core VPN use is local traffic control/speed shaping and per-app network control. It is not currently documented as an anonymity/privacy VPN.

Before Play submission, the developer must select the exact VpnService category offered by the production build in Play Console and ensure that the store listing describes the same functionality.

## Required disclosures

- Explain that Android VpnService is used to create the local TUN interface.
- Explain what traffic is routed through the VPN interface.
- Explain app-usage statistics and quota enforcement.
- Explain whether any traffic is sent to a remote VPN endpoint. The current local-tunnel configuration must not be described as a remote VPN service.
- Disclose all personal/sensitive data collected by the final production configuration.
- Complete the Play Data Safety form from the actual production backend and enabled SDKs.
- Provide a public privacy-policy URL before public release.

## Important

This file does not complete the Play Console declaration. Play Console submission and policy review are external gates and must be completed by the app owner.
