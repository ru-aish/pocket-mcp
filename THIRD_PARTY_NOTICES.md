# Third party components

- ngrok Java Agent SDK: MIT / Apache-2.0. Native Android binaries use the pinned Android-compatible fork described in NGROK_NATIVE_SOURCE.md.
- NanoHTTPD 2.3.1: BSD 3-Clause; project https://github.com/NanoHttpd/nanohttpd. Its distribution license is preserved in the dependency and source notices.
- Emulator fixture and native-library preparation script: adapted from the user-owned ru-aish/closepaw-mcp repository, reviewed commit 81ef1ecd84b96bcdf6a59be9b51a2fc991b2d376, Apache-2.0. Test fixture changes add provider seed data, click counts and IME verification. No ClosePaw application or agent engine is included in the APK.
- Android APIs: provided by the device, not a bundled desktop runtime.

The separate community desktop computer-use package retains its own MIT license/provenance and is not an APK dependency.
