# Credits

## Wallpapers

The eight photos in `launcher/app/src/main/assets/wallpapers/` come from CleanYourTV's `ambient-glass` theme and
are CC0 1.0 / Unsplash License: Vladimir Nordmann (Swiss Alps), Marcus Dall Col (Atacama), Dawid Zawiła
(Blue Horizons), Linford Miles (Emerald Lake), Jan Phoenix (Taüll), Ales Krivec (Passo Falzarego),
David Preston (Mount Baker) and Joel Holland (Shrine Pass). Links in the original theme's CREDITS.md.

## Scenes

The five scenes in `launcher/app/src/main/assets/scenes/` (Night, Golden hour, Waterline, Fog, Rainforest dawn,
and the cut-out layers) come from [couch-tv](https://github.com/Seagey/couch-tv) (MIT). Its credits say they were
generated for that project from text only, are not real places, and are free to use.

## Fonts

[Outfit](https://fonts.google.com/specimen/Outfit), [Open Sans](https://fonts.google.com/specimen/Open+Sans) and
[Antonio](https://fonts.google.com/specimen/Antonio) (the big clock, as in couch-tv),
under the SIL Open Font License.

## Screensaver

[3D Pipes: XP Screensaver](https://github.com/TheWombRaider/pipes-tv) by TheWombRaider (MIT) is not in this
repo: the README shows how to build it from its source, changing `minSdk` from 26 to 22 and applying a small patch of
ours to its clear (`tools/pipes-tv-clear-fix.patch`, for GPUs with 16-bit pixel shaders). It is not affiliated
with or endorsed by Microsoft, and its README says it contains no Microsoft code, images or sounds.

## AirPlay receiver

[KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer) (2019) is the receiver behind `airplay-receiver/`.
It has no licence file, so it is not in this repo: `airplay-receiver/build.sh` clones it at the commit our patch
(`airplay-receiver/airplay-receiver.patch`) was made against. It carries code from other projects (RPiPlay-style
AirPlay handling, Apple's mDNSResponder, Fraunhofer's FDK AAC and others) under their own licences. The patch is
ours, a derivative of that code, and is shared only as a patch. Not affiliated with or endorsed by Apple; "AirPlay"
is Apple's trademark.

[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0) is the receiver behind
`airplay-receiver-uxplay/`, built on [UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0) with FFmpeg, OpenSSL and
others under their own licences. `airplay-receiver-uxplay/build.sh` clones it at the commit our patch was made
against; the patch is a modification of GPL-3.0 code and is GPL-3.0 as well.

## Logos

The dock icons for YouTube, Netflix, Prime Video and AirPlay are trademarks of their owners and are not in
this repo. `tools/make_dock_icons.py` draws them on your machine. The Prime Video wordmark comes from
[Simple Icons](https://simpleicons.org) (CC0).
