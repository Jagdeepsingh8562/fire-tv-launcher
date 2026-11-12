#!/usr/bin/env bash
# Builds the UxPlay-based AirPlay receiver (jqssun/android-airplay-server) for the Fire TV Stick (Fire OS 5,
# Android 5.1): clones the upstream project at the commit our patch was made against, applies the patch and builds
# a 32-bit ARM APK with the project's own Gradle wrapper.
#
#   airplay-receiver-uxplay/build.sh      # APK ends up in airplay-receiver-uxplay/build/airplay-receiver-uxplay.apk
#   adb install -r airplay-receiver-uxplay/build/airplay-receiver-uxplay.apk
#
# Needs: git, JDK 17+ (21 is found by itself on Homebrew), perl, and the Android SDK with
#   sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1" "platforms;android-37.0"
# The first build compiles FFmpeg and OpenSSL and takes about 6 minutes; later ones reuse them.
#
# The clone and the build live outside this repo, in $AIRPLAY_UXPLAY_WORK (default ~/.cache/airplay-receiver-uxplay).
# The path must not contain a space: the project's CMake file passes the source path to the compiler unquoted.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="${AIRPLAY_UXPLAY_WORK:-$HOME/.cache/airplay-receiver-uxplay}"
upstream="https://github.com/jqssun/android-airplay-server.git"
commit="a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea"

if [[ "$work" == *" "* ]]; then
  echo "AIRPLAY_UXPLAY_WORK ($work) contains a space; set it to a path without one." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then export ANDROID_HOME="$HOME/Library/Android/sdk"; fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
for part in ndk/27.0.12077973 cmake/3.22.1 platforms/android-37.0; do
  [[ -d "$ANDROID_HOME/$part" ]] || { echo "missing $ANDROID_HOME/$part: see the sdkmanager line at the top of this script" >&2; exit 1; }
done

mkdir -p "$work"
if [[ ! -d "$work/src/.git" ]]; then
  git clone --quiet "$upstream" "$work/src"
fi
git -C "$work/src" fetch --quiet origin "$commit" 2>/dev/null || true
# Start from the untouched upstream commit every time, so the patch always applies cleanly. Untracked build
# output (app/build, app/.cxx) is ignored by git and stays, which keeps later builds fast.
git -C "$work/src" checkout --quiet --force "$commit"
git -C "$work/src" clean --quiet -fd
git -C "$work/src" submodule update --init --recursive --depth 1 --force --quiet
git -C "$work/src" apply "$here/airplay-receiver-uxplay.patch"

# release build, debug-signed (no keystore needed); ARM 32-bit only, which is all the stick runs
sh "$work/src/gradlew" -p "$work/src" :app:assembleRelease

mkdir -p "$here/build"
cp "$work/src/app/build/outputs/apk/release/app-release.apk" "$here/build/airplay-receiver-uxplay.apk"
echo
echo "Built: $here/build/airplay-receiver-uxplay.apk"
echo "Install: adb install -r \"$here/build/airplay-receiver-uxplay.apk\""
