#!/usr/bin/env bash
set -euo pipefail

# Android-compatible JNI library from the upstream project's physically tested
# ngrok-java fork; see https://github.com/danielealbano/android-remote-control-mcp/pull/112 .
# Pin the entire release APK by its GitHub-published SHA-256 digest to prevent
# unreviewed third-party binary changes in CI.
readonly SOURCE_URL="https://github.com/danielealbano/android-remote-control-mcp/releases/download/v1.12.0/android-remote-control-mcp-v1.12.0-foss-release.apk"
readonly SOURCE_SHA="dd7f1d8997547f1ed04e91a0f32ef97a6e00ad26fb43eb7e5b1cb035e22316f3"
readonly TEMP_APK="${RUNNER_TEMP:-/tmp}/pocket-ngrok-v1.12.0.apk"

curl --fail --silent --show-error --location --retry 4 --retry-delay 3 \
  "$SOURCE_URL" --output "$TEMP_APK"
printf '%s  %s\n' "$SOURCE_SHA" "$TEMP_APK" | sha256sum --check --status

for abi in arm64-v8a x86_64; do
  dest="app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  unzip -p "$TEMP_APK" "lib/$abi/libngrok_java.so" > "$dest/libngrok_java.so"
  test -s "$dest/libngrok_java.so"
  # Android 15+ devices may use 16KiB pages. The old Maven artifact had 4KiB
  # segments (0x1000) and cannot reliably load on such devices.
  count=0
  while read -r align; do
    (( count += 1 ))
    if (( align < 16384 )); then
      echo "ERROR: $abi JNI ELF has page alignment $align (requires at least 0x4000)" >&2
      exit 1
    fi
  done < <(readelf -lW "$dest/libngrok_java.so" | awk '$1 == "LOAD" {print $NF}')
  test "$count" -gt 0
  echo "Validated 16KiB JNI segments for $abi"
  sha256sum "$dest/libngrok_java.so"
done
