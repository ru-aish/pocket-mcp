# Pocket MCP

A standalone Android MCP app: direct Android APIs first, Android accessibility for screen control, and an authenticated public HTTPS tunnel. The external agent runs in Codex or another MCP client. No model, desktop runtime, ClosePaw application engine, browser or screenshot history is bundled.

## Install and connect

[Download ARM64 APK](https://github.com/ru-aish/pocket-mcp/releases/download/v0.1.0/Pocket-MCP-arm64.apk) · [All release assets](https://github.com/ru-aish/pocket-mcp/releases/tag/v0.1.0) · [Validation coverage](VALIDATION.md)

1. Install the signed **Pocket-MCP-arm64.apk** from the release. Android 12+ is required. The universal APK also supports the x86_64 emulator.
2. Open Pocket MCP. Enter your **ngrok authtoken**, optionally a reserved domain, and save the connection settings. A separate MCP bearer token is generated automatically; replace it only while the connection is stopped.
3. Enable only the permissions needed. Contacts/calls, calendar and SMS have separate controls. Enable accessibility for screen observation/input. Enable notification access for notification replies and media sessions. Android may require **App info → Allow restricted settings** before enabling a sideloaded accessibility service.
4. Press **Start public connection**. The app shows its public `/mcp` URL and the result of its own authenticated HTTPS check. On phones with aggressive background limits, enable the appropriate battery/autostart setting for Pocket MCP.
5. Press **Copy MCP connection details**. Configure your MCP client with that URL and `Authorization: Bearer <your-token>`. Credentials remain encrypted in Android Keystore-backed storage; no credential is built into the APK.

### ChatGPT / OpenAI OAuth connection

Start the phone's public HTTPS connection, then create a custom MCP app in ChatGPT with the displayed public `/mcp` URL and **OAuth** authentication. The phone supports OAuth authorization-server and protected-resource discovery, dynamic client registration, authorization-code + PKCE (S256), and refresh-token rotation. During authorization, enter the **MCP bearer token** from **Copy MCP connection details** as the access password. This is distinct from the ngrok authtoken. OAuth tokens are scoped to this public MCP URL and held only in memory: stopping/restarting the foreground service revokes them. If the ngrok hostname changes, reconnect the client. Existing MCP clients can still use the direct `Authorization: Bearer <token>` header.

OAuth metadata and registration routes are accessible only after the HTTPS tunnel is established. They share the existing localhost-only listener and never use an embedded client secret. The registration endpoint accepts HTTPS callback URIs (or localhost for local development). The browser displays a warning that this grants high-impact access to phone tools. Do not give the MCP token to untrusted clients, and keep the foreground service stopped when remote access is not needed.

The connection uses a visible foreground service with a Stop action. The local server binds to `127.0.0.1:8765`; the phone's ngrok SDK forwards public HTTPS to it. **Start local connection** is available for emulator/USB-forward testing without ngrok. It never publishes the phone on the LAN.

## Tools

| Layer | Tools |
| --- | --- |
| Discovery/retrieval | `phone_capabilities`, `phone_apps`, `phone_contacts`, `phone_calendars`, `phone_notifications` |
| Direct Android/app-provided actions | `phone_call`, `phone_calendar_create`, `phone_sms`, `phone_alarm`, `phone_timer`, `phone_reply`, `phone_media` |
| App launch and screen fallback | `phone_open_app`, `phone_observe`, `phone_act` |
| Reliable retry/outcome retrieval | `phone_operation` |

Inspect `phone_capabilities` first. Its permission flags and live service state determine what is usable. Calls use Telecom; calendar creation writes Calendar Provider and reads back its URI; SMS uses SmsManager with per-part receipts. A matching handler or permission is not proof of task completion.

WhatsApp and other consumer apps may expose a current notification reply action; the app does not claim a universal arbitrary-recipient messaging API. Otherwise the external agent uses Android screen fallback. A launch request is **requested**, not a verified foreground transition.

## Three-agent virtual displays (experimental)

On Android 12+, Pocket MCP can allocate up to **three separate headless virtual displays** for independent UI agents without root. Native Android actions (contacts, calendar, SMS, etc.) use **zero** display leases; creating a session also allocates no display. Agent UI displays are created only when `phone_agent_acquire_ui` is called and destroyed on release, timeout, session close or server stop. The physical display (`display_id=0`) is never allocated to a virtual agent.

1. Install and start [Shizuku](https://shizuku.rikka.app/) using its supported wireless debugging method or ADB on the Android device. Shizuku must be running; in ADB mode it generally needs restarting after a device reboot. No PC is required during normal use after initial Shizuku startup.
2. In Pocket MCP, tap **Enable Shizuku virtual displays**, then explicitly allow Pocket MCP access in the Shizuku authorization screen. Enable Pocket MCP accessibility separately.
3. Connect an MCP client and call `phone_agent_create` (with an operation ID) to obtain a private `session_id`. When the task needs a screen, call `phone_agent_acquire_ui`; use that session ID with `phone_open_app`, `phone_observe` and `phone_act`. The client must keep each agent's session ID private.
4. Call `phone_agent_release_ui` when visual work is finished (native tools can continue), then `phone_agent_close`. `phone_agent_list` and `phone_displays` report available capacity and Shizuku readiness without allocating a screen.

The fourth simultaneous UI agent is rejected rather than redirected to the physical screen. Ordinary legacy tools without session IDs continue to use the physical screen only when **no agent sessions exist**, for backwards compatibility. Agent sessions expire if inactive (with a limit of 128 active session records), and UI displays have a shorter idle timeout. The virtual display service binds lazily and unbinds when the last UI lease closes, reducing idle overhead.

**Limitations:** Headless display success depends on Android version, OEM policy, and each app's support for multi-display activities. Different displays share Android user accounts, cookies, app data, and cloud resources; they do not isolate data writes or user identities. Virtual keyboard focus, deep Doze, locked-keyguard behavior and restricted apps may vary. This does not unlock a secure phone or bypass FLAG_SECURE; a phone with a secure lock can reject virtual UI actions. A screen-panel-off emulator pass does **not** guarantee long-lived operation on Redmi/HyperOS or under deep sleep. Test those separately. Screen captures are on-demand and Android may rate-limit rapid screenshot requests.

See [complete edge-case and acceptance checklist](docs/VIRTUAL_DISPLAY_EDGE_CASES.md) and the emulator validation script `scripts/virtual_display_emulator_test.py`.

## Screen control

`phone_observe` returns the active package/window, full device dimensions, a bounded accessibility tree and an optional resized JPEG. It captures no image for tree-only requests. `phone_act` supports tap, native click/long-click, gesture long press/swipe, native scroll/text and Home/Back/Recents/real IME Enter.

- Observe before each screen action and pass `snapshot_id`.
- Coordinates refer to the full display, not the resized screenshot.
- Prefer an exact unique `resource_id`, text or description selector.
- Sleeping/locked devices and stale package/window/display geometry are rejected.
- Same-window changes can still invalidate coordinates; use fresh observations and semantic targets.
- A completed gesture is **dispatched**; it does not prove a message was delivered or a call connected.

## Mutations, retries and storage

Every mutation requires an operation ID:

```text
<phone_capabilities.operation_prefix>_<unique_nonce>
```

Use an 8–48 character nonce containing letters, digits, dashes or underscores. Save the exact ID and arguments on the client. After a lost response, retry with those identical values or inspect `phone_operation`. Conflicting payloads are rejected. An operation interrupted by process death is never automatically dispatched again.

IDs are tied to this installation and the current/previous UTC-day retry window. Expired IDs cannot execute again, so old records can be pruned safely. The ledger caps active records at 20,000 and stores fingerprints/results/receipts, not screenshots or full message bodies. Its persisted epoch never decreases if the clock moves backward. Missing/expired history is not proof that an old action never occurred; inspect the owning app/provider before starting a new operation. Uninstalling or clearing app data starts a new installation.

## Builds and validation

The published 0.1.0 universal APK passed **30/30 checks** on the local Fedora Android 15 emulator. See [exact hashes, build provenance, coverage and remaining gaps](VALIDATION.md). The fixture and test-runner fixes did not change the published app APKs.

**APKs compile only on GitHub Actions.** The workflow assembles minified release APKs, runs lint, verifies signatures/ABI contents, and uploads an isolated fixture APK. A persistent signing key in Actions secrets lets future versions update this installation.

Fedora tests download the actual signed Actions artifact and run it on the existing `senp_api35` AVD in read-only mode, on explicit serial `emulator-5580`. The USB phone is never selected. ADB provisions the emulator; observations, gestures, text and provider actions execute through the real MCP endpoint. Tests check observed postconditions and never silently retry mutations.

```sh
python3 scripts/bootstrap_fedora_emulator.py
python3 scripts/start_fedora_emulator.py
python3 scripts/emulator_test.py \
  --apk artifacts/Pocket-MCP-universal.apk \
  --fixture artifacts/Pocket-MCP-test-fixture.apk
```

The live ngrok account/HTTPS connection test is left to the user by request. Native tunnel loading is tested locally. Actual carrier call/SMS delivery and physical-phone OEM behavior require device validation; emulator results do not certify those outcomes.

See [native dependency provenance](NGROK_NATIVE_SOURCE.md) and [third party notices](THIRD_PARTY_NOTICES.md).
