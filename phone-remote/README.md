# Phone remote

A remote for the Fire TV on your phone, served from a computer at home. It needs no app on the TV: keys,
typing and opening apps go over adb, and **Ask** ("put on that show about the fake lawyer") goes to Gemini
(or Claude Code), which picks an installed app and what to search for in it.

```sh
cp .env.example .env      # once, then put your TV's address and GEMINI_API_KEY in .env
python3 phone-remote/server.py
```

Settings come from `.env` at the repo root (git ignores it) or the environment, which wins. See
[.env.example](../.env.example): `TV_HOST`, `PORT`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_PROVIDER`.

Open `http://<this computer's address>:5400` on a phone on the same Wi-Fi and add it to the home screen.

- **Needs:** Python 3.9+, `adb` (on macOS `brew install --cask android-platform-tools`), ADB debugging on
  the TV, and for Ask either a free Gemini key from [AI Studio](https://aistudio.google.com/apikey) or
  [Claude Code](https://claude.com/claude-code) signed in to your own plan (`ANTHROPIC_API_KEY` is removed
  from its environment, so it never bills an API key). With `GEMINI_API_KEY` set, Gemini answers; without
  it, Claude does; with neither, only Ask is off.
- **The computer must stay on and awake** while you use it.
- **Ask:** the model gets web search only (Google Search grounding for Gemini; WebSearch for Claude) to check
  which app streams a title in India, no files or shell. It tries `gemini-flash-lite-latest`, then
  `gemini-flash-latest` if that one is busy or out of quota (the free tier is Flash-only); `GEMINI_MODEL` pins one. If the free plan or its search quota refuses grounded requests, it asks once
  more without search; if the daily limit is gone, the phone says so. The key is sent in a header and never
  logged. Whoever answers, the reply is checked: it can only choose an app from `APPS` in `server.py` and a plain search phrase.
  YouTube (SmartTube): "play X" finds YouTube's top result for X and plays it; anything vaguer opens the
  search results. Netflix and JioHotstar restart (they drop links at
  "Who's watching?"). They then wait until the app has finished loading (the server watches its CPU, since
  Netflix shows nothing on the stick while it starts; about 45 s cold). Netflix has `profile` keys in `APPS`, so
  it then picks the first profile itself, waits for home, opens Search and types the title, with no touch
  needed (the phone shows each step). JioHotstar has none: pick your profile on the TV and tap **Search** on
  the phone. Do not use the `profile` keys for an account with a single profile and no "Who's watching?". The other apps open on their home page and the
  phone says what to search for. Add an app or a search link in `APPS`.
- **Title page:** for a title on Netflix, Prime Video or JioHotstar, Ask looks it up on JustWatch (public, no
  key) and shows a page on the TV in the home's glass style (poster, IMDb score and votes as JustWatch has them, description, one **Play on** button for each
  of those apps that has it) instead of opening an app. The launcher's `TitleActivity` draws it. **Play** asks
  this computer to open the title: **Netflix** gets the link if it is already running, otherwise it is
  restarted, the first profile is picked and then the link is sent, and the server checks Netflix's media
  session until it says playing. **Prime Video** opens the title's page and the server presses OK on it.
  **JioHotstar**'s `hotstar://content/<id>` link plays by itself (its web links are ignored). If this computer
  cannot be reached, the TV opens the link directly. If JustWatch finds no match, Ask opens the app the old way.
- **Memory:** the stick has ~900 MB, so before any app opens from here (Ask, an app button, a search) the
  server runs `am kill-all` and force-stops the other apps in `APPS`, plus Prime's player. For a video app
  (`OTT` in `server.py`) it also force-stops the `AGGRESSIVE` list: Amazon's Appstore, photos, sync, Alexa and
  Whisper helpers, the screensaver process and Amazon's own home, about 120 MB on this stick. They run four at a
  time (about 4 s; one after another it is 16 s). The **Free memory** button always does the aggressive version,
  keeps the app on screen, and shows the memory available before and after. Amazon's home stays down until the
  next Home key press, which then takes a few seconds.
- **No password:** anyone on your Wi-Fi can use it, like the TV remote on the table. Never forward a port
  to it. Put guests on a guest network.
- `python3 phone-remote/server.py --selftest` checks that the model's answer cannot open anything else, and the
  `.env` parsing.

Ideas from [couch-tv](https://github.com/Seagey/couch-tv) (MIT), whose own bridge needs Google's remote
protocol, which Fire OS 5 does not have.
