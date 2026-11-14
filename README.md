# V2Shizzi VPN — Modernized Fork

Android VPN client with hotspot VPN sharing through Root or Shizuku.

## Architecture

- Android `VpnService` owns the TUN interface.
- Xray (`libv2ray`) runs the supported proxy outbound.
- HEV SOCKS5 tunnel bridges Android TUN traffic to the local Xray SOCKS inbound.
- Hotspot forwarding is managed separately through Root or Shizuku shell operations.
- Existing package identity is preserved: `com.fastnode.app`.

## Supported link formats

- VLESS
- VMess
- Trojan
- Shadowsocks

Transport availability depends on the bundled Xray core and the link parameters. A parsed link is not proof that its server or transport is reachable.

## Build requirements

- JDK 17
- Android SDK Platform 34 and Build Tools 34.0.0
- Android NDK 26.1.10909125
- Gradle 8.7
- Pinned Xray AAR and HEV native library (downloaded/built by CI).

Build a debug APK:

```bash
gradle assembleDebug
```

A signed Release build requires all of the following environment variables:

- `V2SHIZZI_KEYSTORE` — path to the existing private keystore file
- `V2SHIZZI_KEYSTORE_PASSWORD`
- `V2SHIZZI_KEY_ALIAS`
- `V2SHIZZI_KEY_PASSWORD`

Never commit the keystore, passwords, or update-provider credentials. Do not substitute a debug key for a public Release key: Android will not install it over a build signed with the original private certificate.

## VPN sharing

1. Import and select a server.
2. Start VPN and approve Android's VPN permission dialog.
3. Enable Wi-Fi hotspot or USB tethering in Android system settings.
4. Select Root or start Shizuku and grant shell permission.
5. Start hotspot sharing.

Sharing depends on the device firmware, Android release, kernel firewall support and available privileges. Shizuku is not equivalent to root; some devices reject forwarding or firewall commands.

## Security notes

- VLESS with `security=none` is not protected by TLS/REALITY. Prefer TLS or REALITY when supported by the server.
- Keep the same private Release keystore for all updates.
- The updater validates SHA-256 and restricts update downloads to the configured HTTPS update host.
- Do not expose server links, UUIDs, passwords, private keys or Shizuku pairing credentials in public logs.

## Validation status

CI builds a Debug APK and creates Release APKs only when signing secrets are supplied. Device-level validation is still required for VPN packet flow, proxy transports, hotspot forwarding, Shizuku compatibility and OEM-specific network behavior.
