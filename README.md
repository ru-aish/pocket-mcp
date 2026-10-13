# Pocket MCP

A standalone Android MCP app: direct Android APIs first, Android accessibility for screen control, and an authenticated public HTTPS tunnel. The external agent runs in Codex or another MCP client. No model, desktop runtime, ClosePaw application engine, browser or screenshot history is bundled.

## Install and connect

1. Install the signed **Pocket-MCP-arm64.apk** from the release. Android 12+ is required. The universal APK also supports the x86_64 emulator.
2. Open Pocket MCP. Enter your **ngrok authtoken**, optionally a reserved domain, and save the connection settings. A separate MCP bearer token is generated automatically; replace it only while the connection is stopped.
3. Enable only the permissions needed. Contacts/calls, calendar and SMS have separate controls. Enable accessibility for screen observation/input. Enable notification access for notification replies and media sessions. Android may require **App info → Allow restricted settings** before enabling a sideloaded accessibility service.
4. Press **Start public connection**. The app shows its public `/mcp` URL and the result of its own authenticated HTTPS check. On phones with aggressive background limits, enable the appropriate battery/autostart setting for Pocket MCP.
5. Press **Copy MCP connection details**. Configure your MCP client with that URL and `Authorization: Bearer <your-token>`. Credentials remain encrypted in Android Keystore-backed storage; no credential is built into the APK.

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
