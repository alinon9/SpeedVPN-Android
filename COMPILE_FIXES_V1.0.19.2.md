# v1.0.19.2 Compile Fix

Fixes the remaining Kotlin compilation error in `Socks5Server.kt`.

## Fix
- Keep RFC 1928 UDP source-IP validation.
- Track the latest valid client `InetSocketAddress` (IP + source port) separately so UDP replies can be sent back to the correct port.
- Use the `DatagramPacket` constructor with an explicit offset/length and `SocketAddress`.

No intentional changes were made to VPN lifecycle, speed limiting, routing, or UI design in this patch.
