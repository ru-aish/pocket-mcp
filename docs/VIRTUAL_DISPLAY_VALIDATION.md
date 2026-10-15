# Virtual-display implementation: Fedora emulator validation

Date: 2026-10-15. Target: three concurrent UI agents on Android without root, with the main physical screen reserved for the human.

## Signed build provenance

- GitHub Actions signed release run: https://github.com/ru-aish/pocket-mcp/actions/runs/38767724560
- Commit: `9bb4a45` (application code). Fedora-only test changes after that commit do not change the signed APK.
- Universal APK SHA-256: `a3ed6e5c8af8ecef85ce2fa6ff6f2515e29ab7912a792c981f93e65b5d399ea1`
- Test device: **Fedora emulator-5580**, Android API 35, x86_64. Connected physical USB device was not targeted.
- Shizuku emulator provider: **v13.6.0**, running as shell UID following in-emulator authorization. No rooting of the emulator or physical phone was needed.
- CI passed Java tests (including `AgentSessionsTest`), release compilation, R8, lint, APK signatures, and structure checks.

## Real signed-APK results (emulator)

| Suite | Result | Evidence stored locally on Fedora |
| --- | --- | --- |
| Pre-existing MCP/Android regressions | **30/30 passed** | `artifacts/fedora-regression-38767724560/results.json` |
| Multi-agent virtual display integration | **32/32 passed** | `artifacts/final-multi-agent-38767724560.json` |
| Chromium WebView Shell three-window probe | **passed** | `artifacts/browser-virtual-display-probe.json` |
| Shizuku shell-server death/recovery | **7/7 passed** | `artifacts/shizuku-death-recovery.json` |

These local reports are intentionally not committed because they can include ephemeral session IDs and screen/window metadata.

The end-to-end test verified:
- Zero displays allocated for native-only agents; 3 separate UI sessions allocated 3 distinct non-zero Android display IDs; a fourth UI request was rejected.
- Three different windows of the same fixture app launched through Shizuku; all appeared in display-specific accessibility observations with distinct window IDs.
- Three concurrent agents independently typed and verified distinct text fields. One agent's gesture changed only its intended fixture window; its action snapshot could not be reused by another agent.
- Three virtual screenshots returned different image hashes. Captures are on-demand and respect Android's screenshot rate limit.
- Display 0 remained on Android Settings during virtual activity/gesture tests. Display-targeted Back did not alter display 0's foreground app.
- With **physical panel state OFF**, three virtual windows remained observable, and a virtual text action was performed and read back successfully. Power manager still reported `mWakefulness=Awake`: this demonstrates *panel-off operation*, **not** deep Doze or secured-keyguard operation.
- Releasing one UI agent did not release other displays. Another agent could acquire the freed slot. After final close, Android's display list contained only display 0.
- Shizuku user service was **not resident at baseline** and was **not resident after cleanup**. When Shizuku server was killed, UI lease ownership was invalidated without deleting native-only agent sessions; native queries continued, UI actions failed closed, and UI leasing recovered after the Shizuku server restarted.
- Three distinct `org.chromium.webview_shell` windows (Chromium WebView Browser Tester 124) launched, and three agents concurrently edited distinct URL fields without cross-window changes. This does **not** prove full Chrome/Firefox support or navigation to external sites.

## Resource and latency snapshot (not universal device specifications)

`dumpsys meminfo` reports **PSS** (kilobytes); these are instantaneous process snapshots, **not additive GPU frame-buffer capacity measurements**:

| Process | No UI leases PSS | Three active fixture windows PSS |
| --- | ---: | ---: |
| Pocket MCP app | 21,573 KB | 22,121 KB |
| Shizuku shell server | 52,462 KB | 50,761 KB |
| Lazily created display UserService | **not running** | 51,490 KB |
| Fixture app (shared process) | 37,726 KB | 40,515 KB |

Cold first UI lease with Shizuku binding: **471 ms**. Next two warm leases: **49 ms** and **55 ms**. Three on-demand virtual screenshot calls: **50 ms**, **46 ms**, **55 ms**, after spacing them to honor Android's screenshot throttling. The idle display-service process exited and all virtual display IDs disappeared after cleanup.

The separate Shizuku server remains installed/running until Shizuku is stopped, with its own baseline cost; Pocket MCP does not bundle it or start it automatically. The current Pocket MCP foreground service also holds its existing partial wakelock while connected. No whole-device energy or continuous CPU load measurement is claimed.

## Remaining hardware/compatibility gates

The emulator evidence **does not** establish behavior on a Redmi/HyperOS physical phone, secure PIN/password keyguard, long deep sleep/Doze, OEM background-kill policy, full Chrome or other third-party apps, protected FLAG_SECURE content, or long-running three-heavy-app memory pressure. No secure lock can be automatically bypassed. Production claims for these cases require physical-device tests. Virtual displays separate window/input state, not app data, browser cookies, remote account writes or logins within the same Android user.

## Reproduce

1. Run the GitHub Actions signed APK build. Download signed `Pocket-MCP-universal.apk` and the fixture APK.
2. Use **only** Fedora `emulator-5580` for the automated suite; install Shizuku and authorize the app in its Android UI.
3. Execute `python3 scripts/emulator_test.py --apk <universal-apk> --fixture <fixture-apk>`, then start local MCP in the app UI.
4. Execute `python3 scripts/virtual_display_emulator_test.py`, `python3 scripts/browser_virtual_display_probe.py` and `python3 scripts/shizuku_recovery_emulator_test.py`.
5. Preserve the generated JSON reports and check `adb -s emulator-5580 shell cmd display get-displays -i` reports only display 0 after cleanup.

All scripts hard-code or validate an `emulator-` serial and never target the physical USB device.


## USB-connected Redmi K50i physical-device probe (2026-10-15)

The follow-up hardware test **does not pass**. This is separate from the signed-emulator results above.

- Device: Redmi K50i (model `22041216I`), Android 14 / API 34, MediaTek `mt6895`, USB-debugged on Fedora. Tested with the new signed APK from GitHub Actions run [38782875747](https://github.com/ru-aish/pocket-mcp/actions/runs/38782875747) containing commit `4e6c47d`.
- Installed Shizuku manager: `13.6.0.r1086.2650830c`. Shizuku shell server is running, Pocket MCP's `API_V23` permission was granted, accessibility works, and authenticated HTTPS MCP requests return HTTP 200.
- A direct MCP probe from Fedora created three native-only sessions and attempted to acquire a UI lease. Every UI acquisition returned `NOT_READY` (`binding`); session cleanup succeeded and `phone_displays` contained **only physical display 0** afterward.
- Pocket MCP log showed `Shizuku UserService bind requested` but never `Shizuku UserService connected`. Shizuku shell-server logs showed `New service record dev.pocket.mcp:pocket-agent-displays` followed by `Starting process`, then `Service record ... is not started in 30000 ms`. No user service process or virtual display became available.
- This strongly resembles a **known Shizuku UserService startup issue on some MediaTek/Xiaomi devices**, documented in [RikkaApps/Shizuku-API PR #299](https://github.com/RikkaApps/Shizuku-API/pull/299). That PR describes a failure before client service instantiation when the Shizuku server constructs an Application context. The exact internal exception on this device has **not** been captured, so the upstream bug is a likely explanation, not a proven stack-trace match.
- The client-side retry fix prevents permanently caching the stuck binding. The subsequent adjustment avoids repeatedly spawning new Shizuku services during a single 30-second provider startup attempt; it returns a specific error after the provider deadline. This does **not** repair the vendor-side service startup bug.
- No other apps, user accounts, private content, or settings were modified for the probe. Installing the updated Pocket MCP APK was done in-place with `adb install -r`. Physical screen activity was not used for virtual-agent interactions. Any change to the privileged Shizuku manager/fork requires separate user approval.

The emulator results must not be described as physical-device support; the physical-device Shizuku UserService compatibility blocker remains open.
