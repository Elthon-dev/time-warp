# ⏩ TimeWarp

Move your phone's clock **without root**, using [Shizuku](https://shizuku.rikka.app/).
One tap = tomorrow. Another tap = the day after. Reset anytime. Set any timestamp or full date.

> ⚠️ This changes the **real system clock** — every app on the phone sees the new
> time (that's the point). Automatic network time is switched off while you're
> warped so nothing snaps back.

## Features

| Button | What it does |
|---|---|
| ⏩ **Tomorrow** | System clock + 1 calendar day. Spam-proof: tap once for tomorrow, again for +2 days… |
| ⏮ **Reset** | Back to the actual real-world time (fetched live from the network) |
| 🕐 **Jump to timestamp** | Accepts epoch ms (`1767225600000`), epoch s (`1767225600`) or `2026-12-25 08:00` |
| 📅 **Set date** | Year / month / day / hour / minute fields → clock jumps there |
| 🫧 **Floating bubble** | Draggable bubble over any app: tap → quick panel (+1 day / reset / hide), long-press → hide |
| 🔑 **Permission card** | First-launch floating setup window with live ✅/❌ status for every permission |

Every shell command is shown in the on-screen **command log** with its exit code,
so you can see exactly what your device allowed.

## How it works (no root)

Everything runs through Shizuku (ADB/shell identity). The chain, in order:

1. `settings put global auto_time 0` — stops NTP from snapping the clock back
2. `cmd alarm set-time <epoch_ms>` — AOSP shell command; shell UID holds `SET_TIME`
3. `cmd time_detector suggest_network_time --unix_epoch_time …` — time-detector fallback
4. `date MMDDhhmmCCYY.ss` — toybox fallback (blocked on most ROMs)
5. Root paths — only if Shizuku itself was started with `su`

Real time is recovered for Reset via the HTTP `Date` header (plain HTTP on
purpose — TLS would fail while the clock is in the future), with a stored
`fake − real` offset as offline fallback. Reboots are detected via
`elapsedRealtime` drift, so a stale offset can never corrupt the reset.

## Install

1. Grab the **TimeWarp-APK** artifact from the
   [Actions tab](../../actions) of this repo (login required) → download → install
   (enable "install from unknown sources" if asked)
   - first install of a *new version*: since the very first builds used a
     throwaway CI signing key, **uninstall an older TimeWarp APK before
     installing a newer one** (builds ≥8 share one stable key and update in place)
2. Install **Shizuku** and start it:
   - **Android 11+**: Developer options → *Wireless debugging* → pair with the
     in-app pairing code, then press *Start* in Shizuku
   - or via PC: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
3. Open TimeWarp → the floating **first-time setup** card walks you through:
   - Shizuku running → Shizuku permission → bubble access (granted instantly via `appops`, no Settings digging)
   - optional: battery exemption + notifications
4. Tap **⏩ Tomorrow** and watch the log + status bar.

## Important notes

- **Shizuku stops on reboot** (wireless-debugging mode). If the clock is still
  warped when Shizuku needs re-pairing: tap **⏮ Reset**, restart Shizuku, then warp again.
- Some apps cache "today" internally — force-close and reopen them after a jump.
- Apps that use `elapsedRealtime()` (timers, stopwatch) are unaffected by design.
- Reboot detection: if the OS resets the clock, TimeWarp recalibrates from the
  network on the next jump so **Reset always lands on the real time**.

## Build it yourself

```bash
gradle :app:assembleRelease
# apk: app/build/outputs/apk/release/app-release.apk
```
CI does the same on every push and uploads the APK as an artifact.

## Permissions

`INTERNET` (real-time recovery), `SYSTEM_ALERT_WINDOW` (bubble),
`FOREGROUND_SERVICE_SPECIAL_USE` (bubble stays alive),
`POST_NOTIFICATIONS` (optional), battery-optimization exemption (optional).
Shizuku itself is granted separately through its own dialog.
