#!/usr/bin/env python3
"""Phone remote for the Fire TV, served from a computer at home. Standard library only.

Open http://<this computer>:5400 on a phone on the same Wi-Fi. Keys, typing and opening apps go to the TV
over adb; "Ask" hands a sentence ("put on that show about the lawyer with no degree") to Claude Code, which
picks one installed app and a search phrase, and the server opens that app's search. The picking is done by
Gemini (GEMINI_API_KEY) or, without a key, by Claude Code.

Usage:  python3 phone-remote/server.py     settings come from the environment or a .env file at the repo root
        (see .env.example): TV_HOST, PORT, ADB, GEMINI_API_KEY, GEMINI_MODEL, AI_PROVIDER

No password: anyone on the Wi-Fi can use it, like the TV remote on the table. Keep it off the internet.
Ideas from couch-tv (github.com/Seagey/couch-tv, MIT), cut down for Fire OS 5 where its bridge cannot run.
"""
import base64
import json
import os
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

def parse_env(text):
    """KEY=value lines (blank lines, # comments and surrounding quotes allowed) as a dict."""
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip("'\"")
    return out


def load_env(path):
    """Fill in settings from a .env file; real environment variables win, empty values are ignored."""
    try:
        values = parse_env(Path(path).read_text())
    except OSError:
        return
    for k, v in values.items():
        if v:
            os.environ.setdefault(k, v)


load_env(Path(__file__).resolve().parent.parent / ".env")

TV = os.environ.get("TV_HOST", "192.168.1.26")
SERIAL = TV if ":" in TV else TV + ":5555"
ADB = os.environ.get("ADB", "adb")
PORT = int(os.environ.get("PORT", "5400"))
HERE = Path(__file__).resolve().parent

KEYS = {
    "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "back": 4, "home": 3, "menu": 82,
    "playpause": 85, "rewind": 89, "forward": 90, "volup": 24, "voldown": 25, "mute": 164,
    "sleep": 223, "wake": 224,
}

# What this TV has, in the order the phone shows them. search: a link that opens the app's own search for the
# words ({q}), checked on the stick; None means the app opens on its home page.
APPS = [
    {"name": "YouTube", "package": "org.smarttube.stable",
     "search": "https://www.youtube.com/results?search_query={q}"},
    # Netflix and JioHotstar ignore links once running and drop them at "Who's watching?", and Netflix takes
    # 15-30 s to get there with no sign it has. So they are restarted, the person picks a profile on the TV and
    # taps "Search" on the phone, and then keys open Search and type (key_search: keycodes, and a float is a
    # pause in seconds for a screen to load; checked on this
    # stick; Left Up OK opens Search from the side menu, JioHotstar needs one more Up into its search box).
    # boot: seconds after a restart before the app can be ready (Netflix is at 100-200% CPU for ~40 s here).
    # profile: keys that pick the first profile on "Who's watching?" (focus starts on it, so just OK), so the
    # whole search runs without the person touching anything. Leave it out and the phone asks them to pick.
    {"name": "Netflix", "package": "com.netflix.ninja", "search": None, "key_search": [21, 19, 23, 3.0], "boot": 45,
     "profile": [23]},
    {"name": "JioHotstar", "package": "in.startv.hotstar", "search": None, "key_search": [21, 19, 23, 3.0, 19], "boot": 15},
    {"name": "Prime Video", "package": "com.amazon.firebat", "search": None},
    {"name": "Apple TV", "package": "com.apple.atve.amazon.appletv", "search": None},
    {"name": "JioCinema", "package": "com.jio.media.stb.ondemand", "search": None},
    {"name": "FireMusic", "package": "com.azone.firemusic", "search": None},
]
BY_PACKAGE = {a["package"]: a for a in APPS}


STATUS = {"text": "", "busy": False}   # what the phone shows while a key search is under way
LAUNCHED = {}                          # package -> when we last restarted it


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Shell:
    """One long-lived `adb shell` for keys, so a press costs a pipe write instead of a new adb process."""

    def __init__(self):
        self.proc = None
        self.lock = threading.Lock()

    def send(self, line):
        with self.lock:
            for _ in range(2):
                if self.proc is None or self.proc.poll() is not None:
                    subprocess.run([ADB, "connect", SERIAL], capture_output=True, timeout=10)
                    self.proc = subprocess.Popen([ADB, "-s", SERIAL, "shell"], stdin=subprocess.PIPE,
                                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    self.proc.stdin.write((line + "\n").encode())
                    self.proc.stdin.flush()
                    return
                except (BrokenPipeError, OSError):
                    self.proc = None
            raise RuntimeError("TV not reachable over adb")


SHELL = Shell()


def adb(*args, timeout=20):
    """A one-off adb shell command; returns its output."""
    subprocess.run([ADB, "connect", SERIAL], capture_output=True, timeout=10)
    r = subprocess.run([ADB, "-s", SERIAL, "shell", *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout


def adb_quick(*args, timeout=8):
    """A one-off adb shell command without reconnecting first; for polling."""
    r = subprocess.run([ADB, "-s", SERIAL, "shell", *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout


def find_pid(ps_text, package):
    m = re.search(r"^\S+\s+(\d+)\s.*\s" + re.escape(package) + r"\s*$", ps_text, re.M)
    return m.group(1) if m else None


def cpu_sample(text):
    """(uptime seconds, process CPU ticks) from `cat /proc/uptime /proc/<pid>/stat`; ticks are 1/100 s."""
    up, stat = text.splitlines()[:2]
    rest = stat.rsplit(")", 1)[1].split()   # field n of proc(5) is rest[n-3]; utime 14, stime 15
    return float(up.split()[0]), int(rest[11]) + int(rest[12])


def wait_calm(package, boot=0, timeout=90):
    """Wait until the app has finished loading: `boot` seconds since we restarted it, then three one-second
    samples in a row under 60% of a core. Measured here: Netflix runs at 100-200% for ~40 s after launch, then
    30-50%, then near 0. Proceeds anyway after the timeout."""
    end = time.time() + timeout
    pid = prev = None
    calm = 0
    while time.time() < end:
        time.sleep(1)
        try:
            if pid is None:
                pid = find_pid(adb_quick("ps"), package)
                continue
            now = cpu_sample(adb_quick("cat", "/proc/uptime", f"/proc/{pid}/stat"))
        except (ValueError, IndexError, subprocess.TimeoutExpired):
            pid = prev = None
            calm = 0
            continue
        if prev and now[0] > prev[0]:
            calm = calm + 1 if (now[1] - prev[1]) / (now[0] - prev[0]) < 60 else 0
        prev = now
        if calm >= 3 and time.time() - LAUNCHED.get(package, 0) >= boot:
            return


def shell_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def input_text(text):
    # `input text` takes %s for a space and needs shell metacharacters escaped.
    safe = re.sub(r"[^\w .,!?@'\-:/]", "", text)[:200].replace(" ", "%s")
    SHELL.send("input text " + shell_quote(safe))


def installed():
    out = adb("pm", "list", "packages")
    have = {line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line}
    return [a for a in APPS if a["package"] in have]


def launch(package):
    adb("monkey", "-p", package, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")


def restart(package):
    adb("am", "force-stop", package)
    LAUNCHED[package] = time.time()
    launch(package)


# This stick has 895 MB and about 450 MB of it is Amazon's own services, which cannot be removed without root.
# The memory we can free is apps left in the background (Prime's player alone is ~96 MB), and an app that
# opens while others linger is slow and laggy. So before one opens, every other app of ours is stopped.
SAME_APP = [{"com.amazon.firebat", "com.amazon.avod"}]   # Prime's screens and its player are two packages
LAUNCHER = "com.gathin.tvhome"

# The apps that stream video. Opening one frees memory harder: it also stops everything in AGGRESSIVE. Keep this in
# step with OTT and AGGRESSIVE in launcher/.../AppAdapter.java, which does the same for taps on the TV itself.
OTT = {"org.smarttube.stable", "com.netflix.ninja", "in.startv.hotstar", "com.amazon.firebat",
       "com.apple.atve.amazon.appletv", "com.jio.media.stb.ondemand"}
# Measured on this stick (895 MB, 2026-11-10): stopping these frees about 120 MB for good. The small Whisper, Alexa
# and discovery services start straight back; the rest stay down. Amazon's own home (com.amazon.tv.launcher) is in
# the list: with this launcher in front it does not come to the screen, but the next Home key press has to start it
# again, which takes a few seconds. Never list the keyboard (com.amazon.tv.ime), Bluetooth or this launcher.
AGGRESSIVE = [
    "com.amazon.alexa.externalmediaplayer.fireos", "com.amazon.discoveryservice.core.android",
    "com.amazon.kso.blackbird", "com.amazon.tv.alexaalerts", "com.amazon.whisperjoin.middleware.np",
    "com.amazon.whisperjoin.wss.wifiprovisioner", "com.amazon.whisperlink.core.android", "com.amazon.sync.service",
    "com.amazon.bueller.photos", "com.amazon.ceviche", "com.amazon.diode", "com.amazon.venezia",
    "com.amazon.prism.android.service", "com.amazon.identity.auth.device.authorization",
    "com.amazon.tv.notificationcenter", "com.amazon.tv.settings.v2", "com.amazon.webview.chromium",
    "com.amazon.platform.fdrw", "com.android.providers.tv", "com.android.providers.downloads",
    "io.github.thewombraider.pipestv", "com.amazon.tv.launcher",
]


def free_command(keep=None, aggressive=False):
    """Shell line that drops cached background processes and stops every app in APPS except `keep`; with
    aggressive, the AGGRESSIVE list too. Each `am force-stop` starts a Java process (~0.7 s on this CPU), so they run
    four at a time: 24 of them take about 4 s that way and 16 s one after another."""
    spare = {keep}.union(*[g for g in SAME_APP if keep in g])
    pkgs = {a["package"] for a in APPS}.union(*SAME_APP)
    if aggressive:
        pkgs |= set(AGGRESSIVE)
    pkgs = sorted(pkgs - spare)
    lanes = [pkgs[i::4] for i in range(4) if pkgs[i::4]]
    return "am kill-all; " + " ".join("(" + "; ".join(f"am force-stop {p}" for p in lane) + ") &" for lane in lanes) + " wait"


def free_memory(keep=None):
    """The quick clean-up before an app opens (about 2 s). Best effort: a failure must not stop the app opening."""
    try:
        adb(free_command(keep), timeout=15)
    except Exception as e:
        log(f"free_memory failed: {e}")


def deep_clean_later(pkg):
    """For a video app: once it has finished loading, also stop the AGGRESSIVE list. Before the app opens it added
    7-10 s and was not felt (JioHotstar, 2026-11-10), and what it stops starts again when an app launches; after the
    load it costs the user no wait and nothing respawns it for a while."""
    if pkg not in OTT:
        return

    def run():
        try:
            wait_calm(pkg, BY_PACKAGE[pkg].get("boot", 0))
            adb(free_command(pkg, aggressive=True), timeout=25)
            log(f"deep clean after {BY_PACKAGE[pkg]['name']} loaded")
        except Exception as e:
            log(f"deep clean failed: {e}")
    threading.Thread(target=run, daemon=True).start()


def top_package(dump):
    """The package of the app on screen, from `dumpsys activity activities | grep mResumedActivity`."""
    m = re.search(r"mResumedActivity: ActivityRecord\{\S+ u\d+ ([\w.]+)/", dump)
    return m.group(1) if m else None


def meminfo_mb(text):
    """Free plus cached memory in MB from /proc/meminfo; cached pages are given back the moment an app needs them."""
    return sum(int(n) for n in re.findall(r"^(?:MemFree|Cached):\s+(\d+) kB", text, re.M)) // 1024


def available_mb():
    return meminfo_mb(adb_quick("cat", "/proc/meminfo"))


def free_now():
    """The phone's Free memory button: the aggressive clean-up, sparing the app on screen."""
    top = top_package(adb("dumpsys activity activities | grep mResumedActivity"))
    before = available_mb()
    adb(free_command(None if top == LAUNCHER else top, aggressive=True), timeout=25)
    time.sleep(2)
    return {"say": f"Memory available: {before} → {available_mb()} MB"}


# ---- title cards -------------------------------------------------------------------------------------------
# "Put on X" shows a title page on the TV first (TitleActivity in the launcher), and the person picks where to
# play it. The title, its pictures and each app's id for it come from JustWatch's public GraphQL (no key).
# JustWatch's name for an app -> (our package, where the app's id sits in JustWatch's link, the app's own link).
JW_APPS = {
    "Netflix": ("com.netflix.ninja", r"netflix\.com/title/(\d+)", "https://www.netflix.com/watch/{}"),
    "Amazon Prime Video": ("com.amazon.firebat", r"gti=(amzn1\.dv\.gti\.[\w-]+)", "https://app.primevideo.com/detail?gti={}"),
    "JioHotstar": ("in.startv.hotstar", r"hotstar\.com/in/(\d+)", "hotstar://content/{}"),
}
LINKS = {pkg: link for pkg, _, link in JW_APPS.values()}
JW_QUERY = """query($q:String!){popularTitles(country:IN, first:3, filter:{searchQuery:$q}){edges{node{objectType
content(country:IN, language:"en"){title originalReleaseYear shortDescription posterUrl runtime
scoring{imdbScore imdbVotes} backdrops(profile:S1440, format:JPG){backdropUrl}}
offers(country:IN, platform:ANDROID_TV){standardWebURL package{clearName}}}}}}"""


def short_count(n):
    return f"{n / 1e6:.1f}M" if n >= 1e6 else f"{round(n / 1e3)}K" if n >= 1e3 else str(n)


def jw_card(node, have, first=None):
    """The TV's title card from one JustWatch title, with a Play button per app in `have` (packages), `first`
    leading. None when none of our apps streams it."""
    c = node["content"]
    offers = {}
    for o in node["offers"]:
        spec = JW_APPS.get(o["package"]["clearName"])
        m = spec and re.search(spec[1], o["standardWebURL"] or "")
        if m and spec[0] in have and spec[0] not in offers:
            offers[spec[0]] = {"package": spec[0], "name": BY_PACKAGE[spec[0]]["name"], "id": m.group(1),
                               "uri": spec[2].format(m.group(1))}
    if not offers:
        return None
    img = "https://images.justwatch.com"
    poster = c.get("posterUrl") or ""
    back = (c.get("backdrops") or [{}])[0].get("backdropUrl") or ""
    score = c.get("scoring") or {}   # IMDb's, as JustWatch has it; some titles have none
    return {"title": c["title"], "year": c.get("originalReleaseYear") or "",
            "imdb": score.get("imdbScore") or "", "votes": short_count(score["imdbVotes"]) if score.get("imdbVotes") else "",
            "kind": "Series" if node["objectType"] == "SHOW" else "Film", "runtime": c.get("runtime") or 0,
            "desc": c.get("shortDescription") or "",
            "poster": img + poster.replace("{profile}", "s592").replace("{format}", "jpg") if poster else "",
            "backdrop": img + back if back else "",
            "offers": sorted(offers.values(), key=lambda o: o["package"] != first)}


def justwatch(query, have, first=None):
    body = json.dumps({"query": JW_QUERY, "variables": {"q": query}}).encode()
    req = urllib.request.Request("https://apis.justwatch.com/graphql", body,
                                 {"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=15) as r:
        edges = json.load(r)["data"]["popularTitles"]["edges"]
    for e in edges:   # the first title that one of our apps has
        card = jw_card(e["node"], have, first)
        if card:
            return card
    return None


def lan_ip():
    """This computer's address as the TV sees it."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((TV.split(":")[0], 9))   # UDP connect sends nothing; it only picks the route
        return s.getsockname()[0]


def show_card(card):
    card = dict(card, server=f"http://{lan_ip()}:{PORT}")   # where Play asks for the title to be opened
    raw = base64.urlsafe_b64encode(json.dumps(card).encode()).decode()
    adb("am", "start", "-n", "com.gathin.tvhome/.TitleActivity", "--es", "card", raw)


def title_card(query, first):
    """Show the title page for the words; the card, or None (any failure means: open the app the old way)."""
    try:
        card = justwatch(query, {a["package"] for a in installed()}, first)
        if card:
            show_card(card)
        return card
    except Exception as e:
        log(f"title card failed: {e}")
        return None


def send_link(pkg, uri):
    return adb("am", "start", "-a", "android.intent.action.VIEW", "-d", shell_quote(uri), "-p", pkg)


def media_playing(dump, pkg):
    """True when `pkg` has a media session that is playing (state=3), from `dumpsys media_session`."""
    return any(seg.split()[:1] == [pkg] and "state=PlaybackState {state=3" in seg for seg in dump.split("package=")[1:])


def wait_playing(pkg, seconds):
    end = time.time() + seconds
    while time.time() < end:
        time.sleep(2)
        try:
            if media_playing(adb_quick("dumpsys", "media_session"), pkg):
                return True
        except subprocess.TimeoutExpired:
            pass
    return False


def play_netflix(link):
    pkg = "com.netflix.ninja"
    if find_pid(adb_quick("ps"), pkg):   # already running: past "Who's watching?" it takes the link as it is
        send_link(pkg, link)
        if wait_playing(pkg, 15):
            return True
        STATUS["text"] = "Netflix did not take it; restarting it…"
    restart(pkg)
    STATUS["text"] = "Waiting for Netflix to start…"
    wait_calm(pkg, BY_PACKAGE[pkg]["boot"])
    STATUS["text"] = "Picking the first profile…"
    time.sleep(2)
    for step in BY_PACKAGE[pkg]["profile"]:
        SHELL.send(f"input keyevent {step}")
        time.sleep(0.8)
    time.sleep(8)
    wait_calm(pkg)
    STATUS["text"] = "Opening it in Netflix…"
    time.sleep(2)
    send_link(pkg, link)
    return wait_playing(pkg, 20)


def play_prime(link):
    """The link opens the title's page; OK on its focused button starts it (the player is another package)."""
    send_link("com.amazon.firebat", link)
    for _ in range(15):   # a cold Prime takes a while to show its page and longer to start the video
        time.sleep(2)
        if "DetailPageActivity" in adb_quick("dumpsys activity activities | grep mResumedActivity"):
            break
    else:
        return False
    time.sleep(1.5)
    SHELL.send("input keyevent 23")
    return wait_playing("com.amazon.avod", 50)


def play(pkg, tid):
    """Open a title in its app after Play on the TV's title card."""
    app = BY_PACKAGE[pkg]
    link = LINKS[pkg].format(tid)
    STATUS.update(text=f"Opening it in {app['name']}…", busy=True)
    try:
        free_memory(pkg)
        if pkg == "com.netflix.ninja":
            ok = play_netflix(link)
        elif pkg == "com.amazon.firebat":
            ok = play_prime(link)
        else:
            send_link(pkg, link)
            ok = True   # JioHotstar's link plays by itself; its session is not checked
        STATUS["text"] = f"Playing in {app['name']}" if ok else f"Opened {app['name']}; press Play if it has not started"
        log(f"play {app['name']} {tid}: {'ok' if ok else 'not confirmed'}")
    except Exception as e:
        STATUS["text"] = f"Could not play: {e}"
        log(f"play failed: {e}")
    finally:
        STATUS["busy"] = False
    deep_clean_later(pkg)


def key_search(app, query, pick=False):
    """From the app's home (a profile already picked), open Search and type the words. Waits for the app to
    finish loading first, so a tap while it is still starting does not lose the keys. With pick, it is on
    "Who's watching?" after that wait, so it presses OK on the focused first profile and waits for home."""
    STATUS.update(text=f"Waiting for {app['name']} to start…", busy=True)
    try:
        log(f"key_search {app['name']}: waiting for it to load")
        wait_calm(app["package"], app.get("boot", 0))
        if pick:
            STATUS["text"] = "Picking the first profile…"
            log("on the profile screen, picking the first profile")
            time.sleep(2)   # let the screen finish drawing
            for step in app["profile"]:
                SHELL.send(f"input keyevent {step}")
                time.sleep(0.8)
            STATUS["text"] = f"Waiting for {app['name']} home to load…"
            time.sleep(8)
            wait_calm(app["package"])
        STATUS["text"] = f"Opening {app['name']} search…"
        log("loaded, sending keys")
        time.sleep(2)
        for step in app["key_search"]:
            if isinstance(step, float):
                time.sleep(step)
                continue
            SHELL.send(f"input keyevent {step}")
            time.sleep(1.2)
        time.sleep(1.5)
        input_text(query)
        STATUS["text"] = f"Typed “{query}” into {app['name']}"
        log("typed")
    except Exception as e:
        STATUS["text"] = f"Could not search: {e}"
        log(f"key_search failed: {e}")
    finally:
        STATUS["busy"] = False


def youtube_first_video(query):
    """The id of YouTube's top video result for the words, or None. Read from the search page's own data."""
    req = urllib.request.Request(
        "https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(query),
        headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return first_video_id(r.read().decode("utf-8", "ignore"))
    except Exception:
        return None


def first_video_id(html):
    m = re.search(r'"videoRenderer":\{"videoId":"([\w-]{11})"', html)
    return m.group(1) if m else None


def open_search(app, query, play=False):
    """Open the app's own search for the words, or just the app when it has no search link. With play, YouTube
    opens the top video result itself instead of the results page."""
    free_memory(app["package"])
    if play and query and app["package"] == "org.smarttube.stable":
        vid = youtube_first_video(query)
        if vid:
            out = adb("am", "start", "-a", "android.intent.action.VIEW", "-d",
                      shell_quote("https://www.youtube.com/watch?v=" + vid), "-p", app["package"])
            if "Error" not in out:
                return True
    if app.get("key_search") and query:
        restart(app["package"])   # always from the top, so "Who's watching?" then home is where the keys start
        return True
    if not app.get("search") or not query:
        launch(app["package"])
        return False
    link = app["search"].format(q=urllib.parse.quote_plus(query))
    target = ["-n", app["component"]] if app.get("component") else ["-p", app["package"]]
    out = adb("am", "start", "-a", "android.intent.action.VIEW", "-d", shell_quote(link), *target)
    if "Error" in out:
        launch(app["package"])
        return False
    return True


ASK_PROMPT = """You choose what to open on a Fire TV in Jaipur, India.
Apps on the TV (name: package): {apps}
The person said: "{q}"

Work out the actual title or thing they mean. If it is a film or series, pick the app above that streams it
in India (search the web if unsure). Music, clips, trailers and anything else free go to YouTube.
Reply with ONLY one line of JSON, nothing else:
{{"say": "<short sentence for the phone, e.g. Opening Suits on Netflix>", "app": "<one package from the list, or null>", "query": "<words to search in that app, or null>", "play": <true when they asked to play one particular song or video, false to just browse or search>}}"""


def ask_claude(prompt):
    claude = shutil.which(os.environ.get("CLAUDE_BIN", "claude"))
    if not claude:
        raise RuntimeError("No GEMINI_API_KEY is set and Claude Code is not installed on this computer.")
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}   # use the Claude plan, never bill a key
    with tempfile.TemporaryDirectory() as cwd:   # no project files or CLAUDE.md in reach
        r = subprocess.run([claude, "-p", prompt, "--tools", "WebSearch", "--allowedTools", "WebSearch",
                            "--no-session-persistence", "--model", "claude-haiku-5-5"],
                           capture_output=True, text=True, timeout=120, env=env, cwd=cwd)
    return r.stdout


def gemini_text(data):
    """The reply text of a generateContent response, or "" when it was blocked or empty."""
    try:
        return "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
    except (KeyError, IndexError, TypeError):
        return ""


def ask_gemini(prompt):
    """One generateContent call with Google Search grounding. Tries the fast Flash-Lite model first and the full
    Flash second (the free tier's models are often overloaded or out of quota one at a time). If a plan refuses
    grounding it asks once more without search (the model then answers from what it knows)."""
    key = os.environ["GEMINI_API_KEY"]
    models = [os.environ["GEMINI_MODEL"]] if os.environ.get("GEMINI_MODEL") else \
        ["gemini-flash-lite-latest", "gemini-flash-latest"]

    def call(model, search):
        body = {"contents": [{"parts": [{"text": prompt}]}]}
        if search:
            body["tools"] = [{"google_search": {}}]
        req = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{urllib.parse.quote(model)}:generateContent",
            json.dumps(body).encode(),
            {"Content-Type": "application/json", "x-goog-api-key": key})   # header, so it never lands in a URL
        with urllib.request.urlopen(req, timeout=25) as r:
            return gemini_text(json.load(r))

    why = "no answer"
    for model in models:
        try:
            try:
                return call(model, True)
            except urllib.error.HTTPError as e:
                if e.code not in (400, 403, 429):
                    raise
                return call(model, False)
        except urllib.error.HTTPError as e:
            try:
                msg = json.load(e)["error"]["message"]
            except Exception:
                msg = ""
            why = f"Gemini said {e.code}" + (f": {msg[:100]}" if msg else "")
        except (OSError, ValueError):   # timeout, no network, bad reply
            why = "Gemini did not answer in time"
    raise RuntimeError(why)


def provider():
    return os.environ.get("AI_PROVIDER") or ("gemini" if os.environ.get("GEMINI_API_KEY") else "claude")


def ask(question):
    apps = installed()
    prompt = ASK_PROMPT.format(apps=", ".join(f"{a['name']}: {a['package']}" for a in apps),
                               q=question.replace('"', "'")[:300])
    try:
        text = ask_gemini(prompt) if provider() == "gemini" else ask_claude(prompt)
    except RuntimeError as e:
        return {"say": str(e)}
    m = re.search(r"\{.*\}", text, re.S)
    try:
        plan = json.loads(m.group(0)) if m else {}
    except ValueError:
        plan = {}
    return act(plan, {a["package"] for a in apps})


def act(plan, allowed):
    """Carry out Claude's answer, trusting only an installed package and a plain search phrase."""
    say = str(plan.get("say") or "Sorry, I could not work that out.")[:160]
    pkg = plan.get("app")
    if pkg not in allowed:
        return {"say": say}
    query = re.sub(r"[^\w .,'&:!?\-]", "", str(plan.get("query") or ""))[:100].strip()
    card = title_card(query, pkg) if pkg in LINKS and query else None
    if card:   # the title page is on the TV; Play there opens the app
        return {"say": f"Showing {card['title']} on the TV. Pick where to play it.", "app": pkg, "query": query}
    searched = open_search(BY_PACKAGE[pkg], query, plan.get("play") is True)
    if not BY_PACKAGE[pkg].get("key_search"):   # those apps are driven by keys next; a clean-up now would get in the way
        deep_clean_later(pkg)
    if query and not searched:
        say += f" (search for “{query}” on the TV)"
    elif BY_PACKAGE[pkg].get("key_search") and query and BY_PACKAGE[pkg].get("profile"):
        STATUS.update(text=f"Waiting for {BY_PACKAGE[pkg]['name']} to start…", busy=True)
        threading.Thread(target=key_search, args=(BY_PACKAGE[pkg], query, True), daemon=True).start()
        return {"say": say + ". It takes about 45 s to start; I will pick the first profile and search.",
                "app": pkg, "query": query, "auto": True}
    elif BY_PACKAGE[pkg].get("key_search") and query:
        return {"say": say + ". It takes about 45 s to start. Pick your profile on the TV, then tap Search.",
                "app": pkg, "query": query,
                "then": "Search for " + query}
    return {"say": say, "app": pkg, "query": query}


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            page = (HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
        elif self.path == "/api/status":
            self._json(200, STATUS)
        elif self.path == "/api/apps":
            self._json(200, [{"name": a["name"], "package": a["package"]} for a in installed()])
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            if self.path == "/api/key":
                code = KEYS.get(body.get("key"))
                if code is None:
                    return self._json(400, {"error": "unknown key"})
                if body.get("key") == "home":   # our launcher, not Amazon's home screen
                    SHELL.send("am start -n com.gathin.tvhome/.MainActivity")
                else:
                    SHELL.send(f"input keyevent {code}")
                return self._json(200, {"ok": True})
            if self.path == "/api/text":
                input_text(str(body.get("text", "")))
                return self._json(200, {"ok": True})
            if self.path == "/api/launch":
                if body.get("package") not in BY_PACKAGE:
                    return self._json(400, {"error": "unknown app"})
                free_memory(body["package"])
                launch(body["package"])
                deep_clean_later(body["package"])
                return self._json(200, {"ok": True})
            if self.path == "/api/free":
                return self._json(200, free_now())
            if self.path == "/api/play":   # Play on the TV's title card
                pkg, tid = body.get("package"), str(body.get("id", ""))
                if pkg not in LINKS or not re.fullmatch(r"[\w.\-]{1,80}", tid):
                    return self._json(400, {"error": "bad title"})
                threading.Thread(target=play, args=(pkg, tid), daemon=True).start()
                return self._json(200, {"ok": True})
            if self.path == "/api/search":   # second step for key_search apps, after a profile is picked
                app = BY_PACKAGE.get(body.get("app"))
                query = re.sub(r"[^\w .,'&:!?\-]", "", str(body.get("query", "")))[:100].strip()
                if not app or not app.get("key_search") or not query:
                    return self._json(400, {"error": "nothing to search"})
                threading.Thread(target=key_search, args=(app, query), daemon=True).start()
                return self._json(200, {"ok": True})
            if self.path == "/api/ask":
                return self._json(200, ask(str(body.get("q", ""))))
            self._json(404, {"error": "not found"})
        except Exception as e:   # one bad request must not stop the remote
            self._json(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    if "--selftest" in __import__("sys").argv:
        assert act({"app": "com.evil.app", "say": "x"}, {"org.smarttube.stable"}) == {"say": "x"}
        assert act({"say": None}, set())["say"].startswith("Sorry")
        assert shell_quote("a'b") == "'a'\\''b'"
        assert BY_PACKAGE["com.netflix.ninja"].get("key_search")
        assert first_video_id('x"videoRenderer":{"videoId":"s3a4OQR-10M","thumbnail"') == "s3a4OQR-10M"
        assert first_video_id("no results here") is None
        assert find_pid("u0_a4     13657 197   1550616 200664 ffffffff 00000000 S com.netflix.ninja\r\n", "com.netflix.ninja") == "13657"
        assert find_pid("u0_a4 1 2 3 4 5 6 S com.netflix.ninjaX\n", "com.netflix.ninja") is None
        assert cpu_sample("2496.61 7846.07\n13657 (m.netflix.ninja) S 197 197 0 0 -1 1077936448 38211 9501 460 80 200 49 21 14 10 -10\n") == (2496.61, 249)
        assert parse_env("# c\nA=1\n B = 'x y' \nbad\nC=\n") == {"A": "1", "B": "x y", "C": ""}
        assert gemini_text({"candidates": [{"content": {"parts": [{"text": "{"}, {"text": "}"}]}}]}) == "{}"
        assert gemini_text({"promptFeedback": {"blockReason": "SAFETY"}}) == ""
        cmd = free_command("org.smarttube.stable")
        assert cmd.startswith("am kill-all;") and "force-stop com.netflix.ninja" in cmd
        assert "force-stop org.smarttube.stable" not in cmd and "force-stop com.amazon.avod" in cmd
        assert "force-stop com.amazon.firebat" not in free_command("com.amazon.avod")   # Prime's two packages go together
        assert "force-stop com.amazon.venezia" not in cmd and "force-stop com.amazon.tv.launcher" not in cmd   # light
        hard = free_command("com.netflix.ninja", aggressive=True)
        assert "force-stop com.amazon.venezia" in hard and "force-stop com.amazon.tv.launcher" in hard
        assert "force-stop com.netflix.ninja" not in hard and hard.endswith(" wait") and hard.count("&") == 4
        for never in (LAUNCHER, "com.amazon.tv.ime", "com.android.bluetooth", "com.amazon.client.metrics"):
            assert never not in free_command(None, aggressive=True) and never not in AGGRESSIVE
        assert OTT <= {a["package"] for a in APPS} | {"com.amazon.avod"}
        node = {"objectType": "SHOW", "content": {"title": "Suits", "originalReleaseYear": 2011, "shortDescription": "d",
                "posterUrl": "/poster/1/{profile}/suits.{format}", "backdrops": [{"backdropUrl": "/backdrop/2/s1440/suits.jpg"}],
                "runtime": 44, "scoring": {"imdbScore": 8.4, "imdbVotes": 552819}},
                "offers": [{"standardWebURL": "https://www.netflix.com/title/70195800", "package": {"clearName": "Netflix"}},
                           {"standardWebURL": "https://app.primevideo.com/detail?gti=amzn1.dv.gti.c8a9-1", "package": {"clearName": "Amazon Prime Video"}},
                           {"standardWebURL": "https://www.hotstar.com/in/1971064814", "package": {"clearName": "JioHotstar"}}]}
        c = jw_card(node, {"com.netflix.ninja", "in.startv.hotstar"}, "in.startv.hotstar")   # Prime is not installed
        assert [o["package"] for o in c["offers"]] == ["in.startv.hotstar", "com.netflix.ninja"]
        assert c["offers"][0]["uri"] == "hotstar://content/1971064814" and c["offers"][1]["id"] == "70195800"
        assert c["poster"] == "https://images.justwatch.com/poster/1/s592/suits.jpg" and c["kind"] == "Series"
        assert c["imdb"] == 8.4 and c["votes"] == "553K"
        assert short_count(1234567) == "1.2M" and short_count(999) == "999"
        assert jw_card(dict(node, content=dict(node["content"], scoring=None)), {"com.netflix.ninja"})["imdb"] == ""
        assert jw_card(node, {"org.smarttube.stable"}) is None
        assert set(LINKS) == {"com.netflix.ninja", "com.amazon.firebat", "in.startv.hotstar"}
        dump = "  package=com.netflix.ninja\n  state=PlaybackState {state=3, position=51204}\n  package=com.amazon.avod\n  state=PlaybackState {state=2}\n"
        assert media_playing(dump, "com.netflix.ninja") and not media_playing(dump, "com.amazon.avod")
        assert meminfo_mb("MemTotal: 895216 kB\nMemFree: 71236 kB\nBuffers: 5 kB\nCached: 325328 kB\nSwapCached: 9 kB\n") == 387
        assert top_package("  mResumedActivity: ActivityRecord{71e0acb u0 in.startv.hotstar/com.hotstar.MainActivity t7104}\n") == "in.startv.hotstar"
        print("selftest ok")
        raise SystemExit
    print(f"Phone remote for {SERIAL} on http://{os.uname().nodename}:{PORT}  (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
