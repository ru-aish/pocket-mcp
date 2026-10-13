# Release validation

## Pocket MCP 0.1.0

**30/30 checks passed** against the exact published universal APK, on the user's local Fedora Android 15/API 35 x86_64 emulator (`emulator-5580`). APKs were built and signed only in GitHub Actions. The connected USB phone was never selected.

The [machine-readable report](validation/fedora-api35.json) records every check, the APK and fixture hashes, and source/build provenance.

| Area | Verified |
| --- | --- |
| Transport | Missing/wrong bearer rejection, browser Origin rejection, method/content/protocol validation, initialize/ping, all 16 schemas |
| Screen observation | Actual resized JPEG with original coordinate dimensions, bounded tree and truncation, image omitted by default |
| Input | Exact whitespace, clearing hinted fields, semantic click/long-click, gesture long press/tap/swipe, focused input, actual IME Enter, native scrolling |
| Context guards | Changed window rejected before dispatch; screen-off input rejected |
| Direct providers | Permission-gated contacts query; calendar insertion/readback; retry returns the same event URI |
| App actions | Synthetic notification reply received by its owning app; changed reply capability rejected; media play/pause with native state readback; unadvertised seek rejected |
| Navigation | Launcher discovery, app launch with observed foreground package, Home/Recents/Back |
| Reliability | Duplicate click does not repeat; reordered argument keys deduplicate; conflicting arguments rejected; encrypted bearer and ledger survive process death |
| Lifecycle | Normal UI configuration/start, real ngrok JNI initialization, Stop closes the listener |

## Remaining validation

- Public ngrok account/HTTPS testing was explicitly left to the owner. Native library initialization passed.
- Calls and SMS were tested for permission rejection only. No carrier call, SMS submission or delivery was tested.
- Alarm/timer intent handlers and actual clock completion were not exercised.
- Notification reply testing used the disposable synthetic fixture. It does not establish third-party app or WhatsApp delivery.
- Runtime testing used the universal APK on x86_64/API 35. ARM64 APK signing/native contents were checked in CI; physical ARM phones, other Android versions and OEM background limits require device validation.

These results define the scope of this initial release; they are not a claim of comprehensive stability.

## Exact artifacts

| Artifact | SHA-256 |
| --- | --- |
| Published ARM64 APK | `a0f0c4354156857ba9f0654f796909f5617aec2c50aae56d16c07aa4fc21d456` |
| Published/tested universal APK | `c06b629989690be26a88bab7c929cad9a02eef1eb923715e7ee9bcbd640a2c2e` |
| Test fixture APK | `9fbbf860dd50ab67f8a0373b3405557080358003842d1ef722370c82006b6bf0` |

App build: [Actions run 38468387302](https://github.com/ru-aish/pocket-mcp/actions/runs/38468387302), source `451cb6c4c9b4c8dfd24b42b7e5a1fdc24549ccbb`.

Fixture build: [Actions run 38474472026](https://github.com/ru-aish/pocket-mcp/actions/runs/38474472026), source `5c9a93ceb3b3bfaa6862dced9b79ed664bf9659d`. Later fixture/test-runner fixes resolved hidden controls, keyboard handling and setup navigation. Production app code and published APK bytes did not change.

## Reproduce locally on Fedora

Download the `pocket-mcp-release` artifact from each build above into its corresponding `artifacts/build-<run-id>` directory, then run the current test runner:

```sh
python3 scripts/start_fedora_emulator.py
python3 scripts/emulator_test.py \
  --apk artifacts/build-38468387302/Pocket-MCP-universal.apk \
  --fixture artifacts/build-38474472026/Pocket-MCP-test-fixture.apk \
  --output artifacts/fedora-validation-complete
```

The launcher reuses the existing `senp_api35` AVD read-only on its own display. The test runner refuses physical-device serials and uses synthetic contacts, calendar and notification/media data. After testing, stop only that emulator/display and remove temporary forwarding/connection credentials.
