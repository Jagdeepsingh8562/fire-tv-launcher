# AirPlay receiver with video casting (Fire TV)

Mirror an iPhone, iPad or Mac to a Fire TV Stick on Fire OS 5 (Android 5.1, API 22), with sound, **and cast
videos to it** with the cast button in apps such as YouTube. It appears in the AirPlay list as **Android AirPlay**.

This is [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server), a free (GPL-3.0) AirPlay
receiver for Android and Android TV built on [UxPlay](https://github.com/FDH2/UxPlay), plus a small patch of ours.
The upstream project needs Android 6; the patch makes it run on the stick. `build.sh` clones upstream at the commit
the patch was made against, applies the patch and builds the APK.

```bash
airplay-receiver-uxplay/build.sh                                       # about 6 minutes the first time
adb install -r airplay-receiver-uxplay/build/airplay-receiver-uxplay.apk
adb shell am start -n io.github.jqssun.airplay/.MainActivity           # "Android AirPlay" in the app list
```

You need git, JDK 17 or newer, perl and the Android SDK with `ndk;27.0.12077973`, `cmake;3.22.1` and
`platforms;android-37.0`. The clone and the build live in `~/.cache/airplay-receiver-uxplay` (set
`AIRPLAY_UXPLAY_WORK` to move them; the path must not contain a space, the project's CMake file does not cope with
one). The first build compiles FFmpeg and OpenSSL, later ones reuse them.

Open the app on the TV once: it starts a background service that keeps listening. On the phone, choose
**Android AirPlay** in Screen Mirroring, or press the cast button inside a video app.

## What it does

- **Screen mirroring** with H.264 (and H.265 if the sender offers it) on the stick's hardware decoder, with audio.
- **AirPlay video casting.** The stick plays the video itself from the link the phone hands over (HLS through
  ExoPlayer), so the picture is the original stream and not a live re-encode of the phone's screen. In the test it
  played 1080p at 60 frames a second.
- The picture is drawn through an OpenGL surface, which is what should let a session survive leaving the app
  (the older receiver in [`../airplay-receiver/`](../airplay-receiver/) cannot do that on Android 5). Not tested yet.
- A settings screen for resolution, frame rate, H.265, audio delay, a PIN and the receiver's name.

## What the patch changes

- **Runs on Android 5.1.** `minSdk` goes from 23 to 22. Compose is pinned to the 2025.08 release and Media3 to 1.8.1,
  the last versions that still support Android 5. The six player widgets from `media3-ui-compose-material3`
  (which needs Android 6) are replaced by plain Material 3 ones in `PlayerWidgets.kt`. Ten calls that exist only
  from Android 6 (the display size, the system-service lookup by class, the audio output devices and the decoder
  frame-rate query) are guarded or replaced; they were found with `./gradlew :app:lintRelease -Pandroid.lint.checkOnly=NewApi`.
- **OpenSSL is linked in statically.** Android 5 has its own `libcrypto.so` loaded in every app and it wins over a
  bundled one of the same name, so the app failed to load (`cannot locate symbol "EVP_CIPHER_CTX_reset"`).
- **Native threads detach from Java when they end.** Android 5 aborts the whole app on a thread that does not
  (`Native thread exited without calling DetachCurrentThread`), which killed the receiver whenever a session ended.
- **Builds only the 32-bit ARM library** (the stick's MT8127 is 32-bit) and signs the release APK with the debug key,
  so it installs without a keystore.

## Tested

On one device, a Fire TV Stick (2nd gen, Fire OS 5.2.9.5, MediaTek MT8127), with an iPhone and a Mac on the same
Wi-Fi: iPhone mirroring (498x1080), a video cast (about 47 seconds of 1080p at 60 frames a second, 3 frames dropped),
Mac mirroring at 1920x1080 with audio for about 108 seconds, and each of them ended without a crash.

Not tested: leaving the app and coming back during a session, the PIN, starting after a reboot, H.265, long
sessions, lip-sync, and how the picture and sound look and feel. Mirroring quality is still the sender's choice
(the Mac chose between about 20 kbps and 2.3 Mbps depending on what was on screen); casting avoids that.

## Limits

- Apps that block mirroring or casting (Netflix, the Apple TV app and the like) show black: DRM content is not
  supported.
- The default name is "Android AirPlay". Upstream's README gives the way to change it from a computer
  (`SettingsReceiver`, key `server_name`); it has not been tried here.
- If both receivers are installed, the stick shows up twice in the phone's list (the older one is called
  "Fire Stick"). Use one or remove the other.

## Licence

Upstream is GPL-3.0 and the patch is a modification of it, so the patch is GPL-3.0 as well (not MIT like the rest of
this repo). The APK it builds carries the licences of what it bundles: UxPlay (GPL-3.0), FFmpeg, OpenSSL and others.
"AirPlay" is Apple's trademark; this is not affiliated with or endorsed by Apple. See [`../CREDITS.md`](../CREDITS.md).
