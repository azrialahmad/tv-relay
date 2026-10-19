# Relay: faster TorBox streaming on the TCL 43Q6

The TV's firmware caps every TCP connection's receive buffer at 2 MB, so a player's single connection to the TorBox CDN
is slow. Relay is a small app on the TV (`com.gathin.relay`, port 7000) that sits between Wuplay/Stremio and your
add-on. Players still open one connection, to the relay; the relay downloads the file over several parallel Range
connections and hands the bytes over in order. Nothing in Wuplay or Stremio is modified.

```
Wuplay / Stremio ──1 conn──▶ relay on the TV (127.0.0.1:7000) ──4 conns──▶ resolver ─▶ TorBox CDN
```

## Measured (2026-10-19, same 95 GB 4K remux on nexus.hare.tb-cdn.earth, 25 s each, back to back)

| | avg Mbps |
|---|---|
| Direct, 1 connection (what the players do) | 15–21 |
| **Through the relay** (default: 4 conns, 16 MB chunks) | **38–50** |
| Direct, 4 connections (ceiling) | 54–57 |

That evening one connection to TorBox HARE only got 10–20 Mbps (earlier it was 85–90). The relay gave 2–4× the
single-connection speed and 70–90 % of the 4-connection ceiling. Wuplay's normal playback through Duck Streams was
measured at ~98 Mbps on wlan0 when the route was healthy.

## What's installed

| Where | Add-on URL |
|---|---|
| Stremio (any device, syncs) | `http://127.0.0.1:7000/a/duckstre/manifest.json` → shows as **Duck Streams (AIOStreams) ⚡** |
| Wuplay (config.wuplay.app, tick "hosted on my local network") | `http://192.168.88.17:7000/a/duckstre/manifest.json` |
| Test add-on (sample video) | same, with `test` instead of `duckstre`. Its streams are **off** by default (it shows nothing); switch them on in /setup when testing. You can uninstall it from both apps. |

Keep the original Duck Streams installed as a fallback. The ⚡ add-on only provides streams (no catalogs), with the same
names, labels and order, so autoplay/next-episode (bingeGroup) behave the same. Debridio is already inside Duck
Streams, so it needs no separate entry.

Wuplay's site refuses `127.0.0.1`, hence the LAN IP. If the router ever gives the TV another address, the Wuplay
entry breaks. A DHCP reservation for the TV in the router prevents that (or re-add it with the new IP).

## Settings page: http://192.168.88.17:7000/setup

Open it from a phone or PC on the home Wi-Fi.

- **Add or change an upstream add-on**: paste its full manifest URL (ending in `manifest.json`, the one that
  contains your config/key), give it a short id, press Add. Re-adding with the same id replaces the URL. The page then
  lists the two URLs to add in Stremio and Wuplay. URLs with `user:password@` also work.
- **Remove** one with its Remove button (then remove the ⚡ add-on in the apps too).
- **Tuning**: parallel connections (default 4), chunk size (16 MB), memory budget (96 MB). 4 × 16 MB was the
  fastest tested; 6 × 8 MB was equal. With a 4K player running the TV still had ~325 MB free. If the TV gets
  sluggish, lower the budget to 64 MB. More than 4–6 connections isn't needed and keeps use clearly "normal" for
  TorBox (their terms set no per-file connection limit, only fair use; download managers do the same).
- **Recent streams** shows the CDN host, connections, MB, average speed and retries per stream. A warning
  appears if streams go through MediaFlow/StremThru *proxy* hosts (then only the hop to the proxy is sped up).
  "StremThru Torz" in stream labels is just a search source, not a proxy.

Security: the API keys live only in the app's private storage on the TV. The setup page never shows them again,
nothing logs them, and from the network the relay answers only `/setup` and the add-on manifests; stream lists and
`/play` are answered only to the TV itself.

## How it stays running

TCL's firmware has an app-boot policy and a process freezer that block normal background services. The relay starts
from an invisible activity on boot (needs `SYSTEM_ALERT_WINDOW`) and runs as a foreground service. These were
granted over ADB and must be re-applied after a reinstall (the build script does it):

```sh
adb shell dumpsys deviceidle whitelist +com.gathin.relay
adb shell appops set com.gathin.relay APP_AUTO_START allow
adb shell appops set com.gathin.relay SYSTEM_ALERT_WINDOW allow
adb shell appops set com.gathin.relay APP_ASSOC_START allow
adb shell cmd appops set com.gathin.relay RUN_IN_BACKGROUND allow
adb shell cmd appops set com.gathin.relay RUN_ANY_IN_BACKGROUND allow
```

Reboot test (2026-10-19): the relay came back by itself ~75 s after boot (TCL delivers boot broadcasts late), as a
foreground service, with the home screen left untouched.

Cost to the TV: idle it uses ~0 % CPU and ~11 MB of RAM (PSS). While streaming it holds up to the memory budget
(96 MB) of read-ahead buffers, which it frees a minute after playback stops.

If streams through ⚡ ever fail after a reboot, open the **Relay** app once from the app list; it starts the service
and shows the setup address.

## Rebuild / reinstall

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0; set `sdk.dir` in `local.properties`). From the
repo root in Git Bash:

```sh
ADB=/path/to/adb.exe TV=192.168.88.17:5555 scripts/build-install.sh
```

It builds the APK, installs it over ADB, re-applies the permissions above, starts the service, and forwards the PC's
port 7000 to the TV (so `http://127.0.0.1:7000/...` works from the PC for testing). Installing an update keeps the
stored add-ons and settings.

Code in `app/src/main/java/com/gathin/relay/`:
`Relay.java` (multi-connection relay), `Addons.java` (add-on wrapper), `Setup.java` (settings page),
`HttpServer.java` (tiny HTTP server), `RelayService.java` / `BootReceiver.java` / `BootActivity.java` (keeping it
alive). `scripts/rxrate.sh` samples the TV's Wi-Fi throughput; `bench/` has the speed test used above.

## Remove it all

1. Remove the ⚡ add-ons: in Stremio (Add-ons → Duck Streams ⚡ / Relay test → Uninstall) and in config.wuplay.app.
2. Uninstall the app (this also deletes the stored add-on URLs):
   ```sh
   adb shell pm uninstall com.gathin.relay
   adb shell dumpsys deviceidle whitelist -com.gathin.relay
   ```
No TCL or system package was changed for this.
