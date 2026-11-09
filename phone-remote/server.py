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
import json
import os
import re
import shutil
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
    {"name": "Netflix", "package": "com.netflix.ninja", "search": None, "key_search": [21, 19, 23, 3.0]},
    {"name": "JioHotstar", "package": "in.startv.hotstar", "search": None, "key_search": [21, 19, 23, 3.0, 19]},
    {"name": "Prime Video", "package": "com.amazon.firebat", "search": None},
    {"name": "Apple TV", "package": "com.apple.atve.amazon.appletv", "search": None},
    {"name": "JioCinema", "package": "com.jio.media.stb.ondemand", "search": None},
    {"name": "FireMusic", "package": "com.azone.firemusic", "search": None},
]
BY_PACKAGE = {a["package"]: a for a in APPS}


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
    launch(package)


def key_search(app, query):
    """From the app's home (a profile already picked), open Search and type the words."""
    for step in app["key_search"]:
        if isinstance(step, float):
            time.sleep(step)
            continue
        SHELL.send(f"input keyevent {step}")
        time.sleep(1.2)
    time.sleep(1.5)
    input_text(query)


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
    searched = open_search(BY_PACKAGE[pkg], query, plan.get("play") is True)
    if query and not searched:
        say += f" (search for “{query}” on the TV)"
    elif BY_PACKAGE[pkg].get("key_search") and query:
        return {"say": say + ". Pick your profile on the TV, then tap Search.", "app": pkg, "query": query,
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
                launch(body["package"])
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
        assert parse_env("# c\nA=1\n B = 'x y' \nbad\nC=\n") == {"A": "1", "B": "x y", "C": ""}
        assert gemini_text({"candidates": [{"content": {"parts": [{"text": "{"}, {"text": "}"}]}}]}) == "{}"
        assert gemini_text({"promptFeedback": {"blockReason": "SAFETY"}}) == ""
        print("selftest ok")
        raise SystemExit
    print(f"Phone remote for {SERIAL} on http://{os.uname().nodename}:{PORT}  (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
