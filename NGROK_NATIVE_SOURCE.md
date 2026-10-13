# Android ngrok JNI provenance

The old Maven Central ngrok-java-native 1.1.1 Android shared library shipped
with 4 KiB ELF segments and had Android JNI compatibility issues.

GitHub Actions now extracts Android-compatible binaries from the publicly
released Android Remote Control MCP v1.12.0 FOSS APK:
https://github.com/danielealbano/android-remote-control-mcp/releases/tag/v1.12.0

The project builds from this ngrok-java fork, commit f7f662ae3c9fddda282118a287bb94ad877d61e6:
https://github.com/danielealbano/ngrok-java
The fork includes JNI_OnLoad 1.6 and 16 KiB ELF alignment fixes and
has been tested on physical ARM64 Android hardware.

Source APK name: android-remote-control-mcp-v1.12.0-foss-release.apk
Source APK SHA-256: dd7f1d8997547f1ed04e91a0f32ef97a6e00ad26fb43eb7e5b1cb035e22316f3
Included Android ABIs: arm64-v8a, x86_64.
Script: scripts/prepare_ngrok_native.sh.

The script verifies the entire upstream APK checksum, then extracts only
libngrok_java.so for those two ABIs and checks their ELF segment alignment.
An Android emulator instrumentation test also loads the new x86_64 JNI library.

The ngrok-java Agent SDK is dual-licensed under Apache-2.0 and MIT.
Credit to ngrok and the Android Remote Control MCP maintainers.
This project is not endorsed by either upstream author.

No ngrok authtoken is included in source, artifacts or test output. Users
enter their own token on their phone. The build check alone does not prove
the ngrok tunnel works on a physical phone.
