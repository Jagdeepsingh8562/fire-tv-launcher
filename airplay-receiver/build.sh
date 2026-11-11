#!/usr/bin/env bash
# Builds the AirPlay receiver for the Fire TV Stick: clones the upstream project at the commit our patch was
# made against, applies the patch, and builds an APK with the launcher's own Gradle (same JDK and SDK).
#
#   airplay-receiver/build.sh            # APK ends up in airplay-receiver/build/airplay-receiver.apk
#   adb install -r airplay-receiver/build/airplay-receiver.apk
#
# Needs: git, JDK 17+, the Android SDK with `ndk;27.0.12077973` and `cmake;3.22.1`
#   sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1" "platforms;android-34"
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="$here/build"
upstream="https://github.com/KqSMea8/AirplayServer.git"
commit="5216b4de0f9da6faf74886b2c7a686f04af1701e"

if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then export ANDROID_HOME="$HOME/Library/Android/sdk"; fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

mkdir -p "$work"
if [[ ! -d "$work/src/.git" ]]; then
  git clone --quiet "$upstream" "$work/src"
fi
# start from the untouched upstream commit every time, so the patch always applies cleanly
git -C "$work/src" checkout --quiet --force "$commit"
git -C "$work/src" clean --quiet -fdx
git -C "$work/src" apply "$here/airplay-receiver.patch"

# `sh`, so it works without the execute bit; the project's own (Gradle 4.6) wrapper is not used
sh "$here/../launcher/gradlew" -p "$work/src" :app:assembleRelease

cp "$work/src/app/build/outputs/apk/release/app-release.apk" "$work/airplay-receiver.apk"
echo
echo "Built: $work/airplay-receiver.apk"
echo "Install: adb install -r \"$work/airplay-receiver.apk\""
