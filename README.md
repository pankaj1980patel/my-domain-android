# my-domain · Android

Native Android client for the **my-domain** LAN messenger.

This is a placeholder submodule. The Android app will implement the same LAN feature
set as the [desktop](https://github.com/pankaj1980patel/my-domain-desktop) app.

- **Discovery** — UDP multicast to the shared group `239.255.42.98:45678`
  (`MulticastSocket` + `WifiManager.MulticastLock`), or NSD (`NsdManager`) for Bonjour.
- **Messaging** — send/receive over **UDP** (`DatagramSocket`) and **TCP**
  (`Socket` / `ServerSocket`).

## Wire compatibility with the desktop app

- **Beacon** (UDP multicast `239.255.42.98:45678`, every ~2s):
  `{"node_id": "...", "name": "...", "tcp_port": <int>, "udp_port": <int>}`
- **Message** (TCP body, or single UDP datagram to the peer's advertised port):
  `{"from": "...", "text": "..."}`

## Getting started

```bash
# Create the Android Studio project here (Empty Compose Activity).
```

> Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
> `CHANGE_WIFI_MULTICAST_STATE`. Acquire a `MulticastLock` before joining the group.
