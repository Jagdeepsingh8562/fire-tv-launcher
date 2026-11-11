# AirPlay receiver (Fire TV)

Mirror an iPhone, iPad or Mac to a Fire TV Stick on Fire OS 5 (Android 5.1, API 22), with sound. It shows up in
Control Center's **Screen Mirroring** list as **Fire Stick**.

It is not a new receiver: it is [KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer), a small 2019
Android AirPlay mirroring server (RAOP, FairPlay and pairing in C, `MediaCodec` for the picture), plus a patch of
ours. The upstream project has no licence file, so none of its code is in this repo: `build.sh` clones it at the
commit the patch was made against and applies the patch.

```bash
airplay-receiver/build.sh                                      # needs the NDK, see the top of the script
adb install -r airplay-receiver/build/airplay-receiver.apk
adb shell am start -n com.fang.myapplication/.MainActivity     # "AirPlay Receiver" in the app list
```

Open the app on the TV and leave it open: it starts listening by itself. Then on the iPhone: Control Center,
Screen Mirroring, **Fire Stick**. Play something with sound.

## What it does and does not do

- Screen mirroring at 25 to 30 frames per second on a Fire TV Stick (2nd gen), the picture sized to the phone's own
  shape (a portrait phone gets bars at the sides), with the sound in step.
- Audio from the phone while mirroring. Pausing the sound no longer ends the mirror.
- **Only while its app is open on the TV.** The picture is drawn into the app's window, so with another app in
  front there is nothing to draw on. Come back to the app and the picture returns within a few seconds.
- **No AirPlay video casting.** The cast button inside apps such as YouTube hands the TV a video link to play
  itself; this receiver cannot do that and says so (it no longer advertises it). Use Screen Mirroring from
  Control Center and play the video on the phone.
- **No password.** Anyone on your Wi-Fi can mirror to it, like the TV's own remote. Put guests on a guest network.
- Apps that block mirroring (Netflix, Apple TV and the like) show black.
- The name "Fire Stick" is fixed in `DNSNotify.java`.

If "Fire Stick" stops appearing in the iPhone's list after the TV has been idle for a long time, close the app and
open it again: its network registration can silently drop.

## What the patch changes

The 2019 code worked once, for the first phone that connected, and then not again. The patch:

- **Builds today.** New Gradle files for the Android Gradle Plugin 8.5.2, the NDK 27 toolchain (the C code needs
  `string.h` and `stdlib.h` pulled in), no proxy settings, a `release` build. Do not use the debug build: its
  native code is unoptimised and far too slow for video.
- **Handles more than one connection.** The SETUP steps were counted in one process-wide variable, so only the
  first connection after the app started got through. They are now told apart by what each request carries, and
  the keys are kept per connection so a stopped audio stream can start again.
- **Ends only the stream the phone names.** Every `TEARDOWN` used to end everything.
- **Is not choppy.** The video loop slept 50 ms after every frame and the native code re-attached to Java for every
  frame; the decoder is now drained at once and the Java calls are looked up once.
- **Keeps the sound with the picture.** The phone sends its first second of audio ahead of time; the old player
  queued all of it, so the sound trailed the picture by nearly a second. The queue is now capped at about 65 ms.
- **Answers what it cannot do.** Requests without a `CSeq` header (AirPlay video casting) were dropped without a
  reply, so the YouTube app waited for ever. They now get "501 Not Implemented" and a log line.
- **Serves both services from one port.** The original advertised a different port for `_airplay._tcp`: a dummy
  socket that accepted one connection and never answered, so anything a phone sent there hung. Both services now
  point at the real server.
- **Survives a bad disconnect.** A phone that dropped without saying goodbye could leave the receiver stuck on the
  last picture, spinning at full CPU or full of dead connections. The video reader now checks every read and every
  length it is given, dead connections are noticed within about 15 seconds (TCP keepalive), a new session replaces
  a stale one, and the decoder restarts itself after an error, a lost surface or falling behind. When a session
  ends the TV goes back to a "Ready" screen instead of keeping the last frame, and closing the app releases the
  decoder so it can be opened again.
- Asks for only the permissions it uses, has a TV launcher entry, keeps the screen on, and starts listening on
  its own.

`adb logcat -s raop VideoPlayer AudioPlayer` shows each request and, once a second, the frames and audio packets
received and shown.

## Credits

[KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer) is the receiver; it builds on RPiPlay-style
AirPlay code, Apple's mDNSResponder, Fraunhofer's FDK AAC and others, under their own licences. See
[`../CREDITS.md`](../CREDITS.md). Not affiliated with or endorsed by Apple; "AirPlay" is Apple's trademark.
