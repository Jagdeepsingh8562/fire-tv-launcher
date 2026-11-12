# fire-tv-cleanup

**Your TV, minus the ads.** Debloat an Android TV, Google TV or Fire TV over ADB and give it a
clean launcher with a macOS-style dock. Hand the prompt to Claude Code or any coding assistant,
answer a few questions about which apps you use, and it does the rest on the real TV.

Forked from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack), which covers Android TV
and Google TV. This fork adds Fire TV support and the launcher features listed below.

![Fire TV home screen with ads (top) and the launcher (bottom)](docs/firetv-home-before-after.jpg)

Everything is reversible: packages are disabled, never uninstalled, and a restore script puts the
stock launcher back.

## Quick start

1. On the TV: Settings → Device Preferences → About → press OK on **Build** seven times → back →
   Developer options → turn on **USB debugging** (and Network/ADB debugging if your TV shows it).
   Keep the TV on and on the same Wi-Fi as your computer.
2. Open Claude Code (or your assistant of choice) on your computer and paste the prompt from
   [`PROMPT.md`](PROMPT.md), filling in your TV model.
3. Accept the "Allow USB debugging?" prompt on the TV when told to, then answer the checklist
   questions (which apps you use, which four go in the dock).

That's it. The assistant backs up, debloats, builds and installs the launcher, and verifies each
step with screenshots from the TV. If you'd rather do it by hand, the pieces are below.

## What's in the repo

| Path | What it is |
|---|---|
| [`PROMPT.md`](PROMPT.md) | The prompt. Brand-neutral; the assistant discovers your TV's bloat. |
| [`launcher/`](launcher/) | The tvOS-style launcher. Edit `app/src/main/res/values/config.xml` for your dock, hidden apps and Live TV package, then run `build-and-install.sh`. |
| [`tools/tv.py`](tools/tv.py) | Pure-Python ADB client (shell, batch, push, pull). Works where the stock `adb` is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files (APKs) as base64 chunks with an md5 check. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. |
| [`airplay-receiver/`](airplay-receiver/) | AirPlay screen mirroring to a Fire TV Stick on Fire OS 5: a patch for an old open-source receiver, and `build.sh` to make the APK. |
| [`airplay-receiver-uxplay/`](airplay-receiver-uxplay/) | AirPlay mirroring **and video casting** to a Fire TV Stick on Fire OS 5: a patch that makes a current open-source receiver run on Android 5, and `build.sh` to make the APK. |
| [`examples/`](examples/) | Per-TV debloat lists. Add yours, see [CONTRIBUTING.md](CONTRIBUTING.md). |

## The launcher

A small native Android TV home screen (`com.gathin.tvhome`), built for sets with 1 GB of RAM:

- Ambient-glass look (ported from CleanYourTV's `ambient-glass` theme, since its launcher needs Android 8):
  a frosted-glass dock band of five wide app banners, the focused dock app's name in large light type
  above it, and a glass highlight that glides between tiles. Press Down for the rest of your apps in
  the same five-column grid.
- Pictures from [couch-tv](https://github.com/Seagey/couch-tv): its five scenes (Night, Golden hour,
  Waterline, Fog, Rainforest dawn) plus eight landscape photos, a new one every 5 minutes (changeable).
  **Big clock** (on by default): the date and a huge thin clock behind the scenery, so peaks and hills
  overlap its bottom edge, as in couch-tv's Night look. **Small clock** is the older top-left one.
- **Keep watching** (after couch-tv): a row of glass cards above the dock for the apps you opened from
  this home, newest first. Fire OS 5 keeps no watch history, so it is apps only, not shows. Up from the
  dock enters it. It is **off by default**; Settings, Cards, Keep watching turns it on.
- **Now playing** card, first in that row, while music plays in another app: cover, song, artist and
  previous / play-pause / next. It needs notification access, which `build-and-install.sh` switches on
  (`adb shell settings put secure enabled_notification_listeners com.gathin.tvhome/com.gathin.tvhome.MediaWatcherService`).
  When an app sends only a YouTube video id (SmartTube does, for a video opened from a link), the title,
  channel and thumbnail are looked up once from YouTube's public oEmbed; with no details at all the card shows
  the app's name and Playing or Paused.
- **Home settings** on the TV: the Settings button opens **Home settings** or **TV settings**. Look (clock
  style and 12 or 24 hour), Pictures (each one on or off, how often they change), Weather (on or off, °C or
  °F, search for a town), Cards, Apps (hide apps) and About (Reset). Changes apply when you go back home.
- Pin, unpin and reorder dock apps on the TV: hold OK or press Menu on an app.
- Round buttons top right: **Wi-Fi** (signal bars; highlighted, it opens into the network name and
  the TV's IP address), **Earphones** (connects or disconnects a paired Bluetooth headset,
  `bt_device_name` in config.xml) and **Settings**. Greeting, clock and date top left, set in
  [Outfit](https://fonts.google.com/specimen/Outfit).
- Weather beside the date (Open-Meteo, free, no key; place in `config.xml`, `weather_latitude` and
  `weather_longitude`).
- **Phone remote and "put on X"** with Gemini or Claude, run from a computer at home: [phone-remote/](phone-remote/).
- A power button at the right end of the top buttons: **Sleep** (needs the one-time
  `adb shell dpm set-active-admin com.gathin.tvhome/.AdminReceiver`, which `build-and-install.sh` runs),
  **Sleep in 30, 60 or 90 min** (a sleep timer, cancelled from the same menu) and **Restart…**, which
  opens Fire TV's My Fire TV screen.

- **Title page** (`TitleActivity`): when the phone remote's Ask picks a title, the TV shows a page first, in the
  same glass style, with the poster, a short description and a **Play on** button for each of Netflix, Prime
  Video and JioHotstar that has it, rather than the app's own heavy home screen. See [phone-remote/](phone-remote/).
- **Frees memory for the app you open.** The stick has ~900 MB and about half of it is Amazon's own services,
  which cannot be removed without root. So opening an app from the launcher first stops the other apps left in
  the background (Prime Video's player alone holds ~96 MB). Only background processes are stopped; anything
  playing stays. For a **video app** (YouTube, Netflix, JioHotstar, Prime Video, Apple TV, JioCinema) it goes
  further 15 s after the tap, once the app has loaded: it also stops Amazon's background apps that nothing needs
  while you watch (the Appstore, photos, sync, Alexa and Whisper helpers, the screensaver process and Amazon's own
  home screen). Measured here that frees about 120 MB when the TV is idle; with JioHotstar open the difference was
  small (Hotstar itself takes 120 to 180 MB) and not something you could feel, which is why it waits until the app
  has loaded rather than delaying it. The small Whisper and Alexa services restart by themselves. The one cost:
  Amazon's home is stopped too, so the first Home key press afterwards takes a few seconds to bring it back. The
  list is `AGGRESSIVE` in [`AppAdapter.java`](launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java).
  `build-and-install.sh` also halves the system animation speed so the TV feels quicker; to undo
  it, `adb shell settings put global window_animation_scale 1.0` (and `transition_animation_scale`,
  `animator_duration_scale`).

Configure it in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
the starting dock, hidden apps, Live TV package and columns. To put your name in the greeting, add
`tvhome.userName=Alex` to `launcher/local.properties`. The YouTube, Netflix, Prime Video and AirPlay
dock icons are trademarked logos, so they are not in the repo. Draw them locally with
[`tools/make_dock_icons.py`](tools/make_dock_icons.py). Apps without one show their own icon.
Build and install with

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:
`brew install openjdk@17 android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`.

## Screensaver (Fire TV)

A Fire TV's own screensaver is off unless you turn it on: its start time is the system setting
`screen_off_timeout`, and it ships as "never" (`2147460000`). For something to show, this uses
[3D Pipes: XP Screensaver](https://github.com/TheWombRaider/pipes-tv) (MIT): the classic pipes growing through a 3D
lattice. It asks for no permissions and took about 2% CPU and 33 MB on a Fire TV Stick (2nd gen, Fire OS 5.2.9.5).
It has no prebuilt APK and says Android 8+ (`minSdk 26`), but the code only uses APIs that Android 5 has, so build
it with `minSdk 22` (same JDK and SDK as the launcher):

```bash
git clone --depth 1 https://github.com/TheWombRaider/pipes-tv.git && cd pipes-tv
sed -i.bak 's/minSdk 26/minSdk 22/' app/build.gradle
git apply /path/to/this/repo/tools/pipes-tv-clear-fix.patch     # see "The clear" below
sh ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell settings put secure screensaver_components io.github.thewombraider.pipestv/.PipesDream
adb shell settings put secure screensaver_enabled 1
adb shell settings put system screen_off_timeout 300000     # start after 5 minutes idle
```

**The clear.** When a scene fills up, the screensaver eats it in small black squares in random order. On this
stick's GPU (a Mali-450, whose pixel shaders only do 16-bit maths) the random order its shader computed with
`sin()` collapses: nearly every square goes black at once and a few stripes of them hang about, as dashed
fragments of pipe along the bottom of the screen, until the clear ends and the next scene starts. The patch,
[`tools/pipes-tv-clear-fix.patch`](tools/pipes-tv-clear-fix.patch), takes the order from a shuffled 256 x 256
texture instead, which works at any precision; the whole picture then dissolves evenly in random squares. To watch
a clear slowly: `adb shell am start -n io.github.thewombraider.pipestv/.PipesActivity --ef timescale 2 --ef dissolve 60`
(a scene at twice the speed and a clear 30 s long).

To see it without waiting, set the timeout to `30000` and leave the TV alone; it can take a few seconds more than
the timeout to appear. Any key press ends it. Changing the screensaver in Fire's own Settings may overwrite the
`screensaver_components` line: run it again. Setting `screen_off_timeout` back to `2147460000` turns the idle
start off again.

## AirPlay receiver (Fire TV)

A Fire TV Stick has no AirPlay of its own, and the current open-source receivers need Android 6 or newer: their
screens are built with a toolkit that calls an Android 6 function the moment they start, so they crash on Fire OS 5.
This repo has two receivers for the stick on Android 5.1, each a patch (so no upstream code is copied here) and a
`build.sh` that clones upstream at a pinned commit, applies the patch and builds the APK:

- [`airplay-receiver-uxplay/`](airplay-receiver-uxplay/) is
  [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0, built on UxPlay) with a
  patch that makes it run on Android 5. It mirrors an iPhone, iPad or Mac **and plays what an app's cast button
  sends** (YouTube's, say) in the original quality, in the AirPlay list as **Android AirPlay**. This is the one to
  use.
- [`airplay-receiver/`](airplay-receiver/) is the earlier one: [KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer),
  a 2019 project with no licence, plus a patch of ours. It mirrors only, as **Fire Stick**, and only while its app is
  open.

```bash
adb install -r airplay-receiver-uxplay/airplay-receiver-uxplay.apk        # the prebuilt APK, 4.7 MB
airplay-receiver-uxplay/build.sh        # or build it: about 6 minutes the first time, outside the repo (path without spaces)
```

Open the app on the TV once, then pick **Android AirPlay** in Screen Mirroring, or press the cast button in a video
app. Details, what was tested and what was not, and what the patch changes are in
[`airplay-receiver-uxplay/README.md`](airplay-receiver-uxplay/README.md) and
[`airplay-receiver/README.md`](airplay-receiver/README.md). Installing both makes the stick show up twice.

## Talking to the TV by hand

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP
tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it, restore stock launcher
```

### Gotchas we hit

- **macOS: `adb connect` says "No route to host"** while the port is clearly open. Recent macOS
  treats the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs (Xiaomi among them). USB debugging alone opens
  network ADB on port 5555.
- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does this.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **Third-party launchers** often charge for basics like a custom wallpaper. Hence `launcher/`.
- **No on-screen keyboard** after switching launchers (seen on Xiaomi): the default input method
  pointed at a Baidu IME that isn't installed. Fix with
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.

## Fire TV

Fire OS is Android underneath, but it guards its home screen. Tested on a Hisense Fire TV Edition
(Fire OS 8, Android 11, model AFTOLCBC82).

To set it up:

1. On the TV, open **Settings → Device & Software → About**, select the device name seven times,
   then go back to **Developer Options** and turn on **ADB debugging**.
2. Run `TV_HOST=<tv ip> launcher/build-and-install.sh`. On Fire TV it installs the launcher and
   turns on its Home redirect. It leaves Amazon's launcher alone.
3. Optional: disable the ad and telemetry packages with
   `tools/disable.sh examples/hisense-fire-tv-AFTOLCBC82/debloat.txt`.

How it differs from Android TV:

- **Amazon's launcher stays.** Fire OS ignores the default-home setting, and Amazon's launcher also
  hosts Settings. Disabling it locks you out of Settings. The launcher's `HomeRedirectService`
  opens the launcher whenever Amazon's home screen comes up, so Amazon's screen shows for a moment
  before yours.
- **Settings keeps working.** Amazon's home screen also flashes up on the way into Settings, right
  after a window from Amazon's settings app. The redirect leaves that flash alone.
- **To turn the redirect off on the TV,** press Menu on the launcher's Settings button and choose
  **Use Amazon Home Screen**.
- **ADB turns off when the TV restarts.** Turn **ADB debugging** back on in Developer Options
  before you connect again.
- **Some packages are protected.** Fire OS refuses to disable the four packages listed in the
  example debloat list. Undo the rest with `tools/restore.sh` and the same list.
- **Old Fire OS 5 sticks (e.g. AFTT, Android 5.1)** block `pm disable`, `pm hide`, `pm uninstall` and the
  accessibility Home redirect, so no debloat and Home keeps opening Amazon's screen. The launcher still
  runs (minSdk 22) and opens itself after boot (BootReceiver) and is listed in Apps: open Apps & Games, highlight it, press Menu, choose Move to front.
- **AirPlay has no screen of its own.** The AirPlay tile opens nothing, and casting from an iPhone
  works anyway.

## Supported TVs

Android TV and Google TV: Xiaomi/Redmi, TCL, Hisense, Sony, OnePlus, Realme, Nvidia Shield and most
others. Fire TV: see [Fire TV](#fire-tv). Not Samsung Tizen or LG webOS. Tested so far on the models
in [`examples/`](examples/).

## Contributing

Add your TV's debloat list, improve the launcher, or sharpen the prompt. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT, see [`LICENSE`](LICENSE), except the patch in `airplay-receiver-uxplay/`, which modifies GPL-3.0 code and is
GPL-3.0. The Outfit and Open Sans typefaces are under the SIL Open Font
License, see `launcher/FONT-LICENSE-OFL.txt`. Credits are in [`CREDITS.md`](CREDITS.md).
