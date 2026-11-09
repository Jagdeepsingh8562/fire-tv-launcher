# Phone remote

A remote for the Fire TV on your phone, served from a computer at home. It needs no app on the TV: keys,
typing and opening apps go over adb, and **Ask** ("put on that show about the fake lawyer") goes to Claude
Code on this computer, which picks an installed app and what to search for in it.

```sh
TV_HOST=192.168.1.26 python3 phone-remote/server.py
```

Open `http://<this computer's address>:5400` on a phone on the same Wi-Fi and add it to the home screen.

- **Needs:** Python 3.9+, `adb` (on macOS `brew install --cask android-platform-tools`), ADB debugging on
  the TV, and for Ask, [Claude Code](https://claude.com/claude-code) signed in to your own plan
  (`ANTHROPIC_API_KEY` is removed from its environment, so it never bills an API key).
- **The computer must stay on and awake** while you use it.
- **Ask:** Claude gets web search only (to check which app streams a title in India), no files or shell. Its
  answer is checked: it can only choose an app from `APPS` in `server.py` and a plain search phrase.
  YouTube (SmartTube): "play X" finds YouTube's top result for X and plays it; anything vaguer opens the
  search results. Netflix and JioHotstar restart (they drop links at
  "Who's watching?"): pick your profile on the TV, tap **Search** on the phone, and it opens their Search and
  types the title. The other apps open on their home page and the
  phone says what to search for. Add an app or a search link in `APPS`.
- **No password:** anyone on your Wi-Fi can use it, like the TV remote on the table. Never forward a port
  to it. Put guests on a guest network.
- `python3 phone-remote/server.py --selftest` checks that Claude's answer cannot open anything else.

Ideas from [couch-tv](https://github.com/Seagey/couch-tv) (MIT), whose own bridge needs Google's remote
protocol, which Fire OS 5 does not have.
