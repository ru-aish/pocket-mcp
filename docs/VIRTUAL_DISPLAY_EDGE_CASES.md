# Pocket MCP virtual-display edge cases and acceptance criteria

Target: 3 concurrent agents, at most 3 virtual Android displays, standalone on-device, authenticated MCP; Shizuku-assisted shell service. Display 0 belongs to the human, never to an agent's virtual UI lease.

## Non-negotiable invariants

1. Agent UI cannot ever fall back to physical display 0.
2. The backend atomically allocates and releases screens; agents never choose physical display IDs.
3. Every UI action uses a validated opaque session token and leased display, with a snapshot bound to session, target window and display.
4. Missing Shizuku, dead binder, expired lease and unsupported app must fail closed.
5. Three UI slots maximum; zero displays when no UI agents; direct Android API tasks allocate none.
6. Shared apps and cookies do not mean isolated account data; separately manage simultaneous edits.
7. No keyguard bypass; screen-panel-off, locked and deep-sleep are different cases.
8. Screenshots on demand only; release graphics buffers, processes and displays on task termination.
9. Existing operation_id retry safety is preserved and dispatch never implies external completion.

## Edge-case checklist

| Case | Expected outcome |
|---|---|
| Two agents acquire simultaneously | Distinct displays atomically, no race |
| Fourth concurrent UI agent | Capacity result or bounded queue; no stealing |
| Same agent acquires twice | Same active lease (idempotent) |
| Agent disconnect/crash | TTL recovery, orphan cleanup |
| Late action after release | Explicit rejection |
| Forged, missing, expired session | Explicit rejection |
| Duplicate mutation operation ID | No replay |
| Shizuku absent, revoked, dies, reboots | Explicit degraded capability; no fallback |
| App launch goes to physical screen | Detect and abort without further taps |
| Chrome two-window reuse | Detect move/reuse; mark incompatible for simultaneous UI |
| App lacks multi-display support | Explicit unsupported result |
| Input keyboard/IME shifts to physical screen | Abort action; never type blindly |
| Global HOME/BACK command | Do not execute on physical display |
| Another agent edits same cloud resource | Application-level coordination and write conflict warning |
| Secure FLAG_SECURE content | Respect capture/interaction restrictions |
| Screen geometry changes | Invalidate snapshots |
| Accessibility stale root/missing window | Reject rather than operate on wrong screen |
| Screenshot buffer stalls / OOM | Bounded buffers, timeout, cleanup |
| Phone panel off, unlocked | Verify virtual task progresses |
| Secure phone lock or Doze | Do not promise availability; test and report independently |
| Human physically interacts | No accidental foreground takeover |
| 3 agent heavy loads | Benchmark memory, CPU, latency, leaked displays |
| Phone reboot | Invalidate leases; Shizuku setup needed in ADB mode |

## System design

- Android-native Shizuku UserService, executed with the shell identity following the explicit Shizuku authorization; create trusted virtual displays on demand using patterns documented by scrcpy (Apache-2.0).
- No desktop-client streaming loop and no local LLM required. Shared privileged process owns virtual-display lifecycle, input dispatch and IME policy.
- Central MCP session/lease manager with opaque unguessable IDs. A UI tool is scoped to its lease; native tools require no display.
- Accessibility window enumeration is display-specific, not the default active window.
- Protect screenshot privacy and do not persist screenshots, cookies or text in lease metadata.

## Verification matrix

Emulator test the signed GitHub APK on Fedora Android 15. Exercise 0/1/2/3 UI sessions, 3 native-only sessions, acquire races, lease expiry, app launch, per-display observation/input, screenshot freshness, missing Shizuku, dead service, main-display isolation and physical screen-off. Capture benchmark JSON (CPU, RSS/PSS, display-creation latency, screenshot latency, memory and leaked-display count) including baseline and 3-agent load. Distinguish emulator results from phone results.

Physical device sign-off is **required** before claiming guarantees for locked/deep-sleep or OEM background execution and input-focus isolation. Android device lock must not be bypassed.
