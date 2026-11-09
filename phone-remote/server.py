#!/usr/bin/env python3
"""Phone remote for the Fire TV, served from a computer at home. Standard library only.

Open http://<this computer>:5400 on a phone on the same Wi-Fi. Keys, typing and opening apps go to the TV
over adb; "Ask" hands a sentence ("put on that show about the lawyer with no degree") to Claude Code, which
picks one installed app and a search phrase, and the server opens that app's search.

Usage:  TV_HOST=192.168.1.26 python3 phone-remote/server.py        (PORT=5400, ADB=adb by default)

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
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

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


def open_search(app, query):
    """Open the app's own search for the words, or just the app when it has no search link."""
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
{{"say": "<short sentence for the phone, e.g. Opening Suits on Netflix>", "app": "<one package from the list, or null>", "query": "<words to search in that app, or null>"}}"""


def ask(question):
    claude = shutil.which(os.environ.get("CLAUDE_BIN", "claude"))
    if not claude:
        return {"say": "Claude Code is not installed on this computer."}
    apps = installed()
    prompt = ASK_PROMPT.format(apps=", ".join(f"{a['name']}: {a['package']}" for a in apps),
                               q=question.replace('"', "'")[:300])
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}   # use the Claude plan, never bill a key
    with tempfile.TemporaryDirectory() as cwd:   # no project files or CLAUDE.md in reach
        r = subprocess.run([claude, "-p", prompt, "--tools", "WebSearch", "--allowedTools", "WebSearch",
                            "--no-session-persistence", "--model", "sonnet"],
                           capture_output=True, text=True, timeout=120, env=env, cwd=cwd)
    m = re.search(r"\{.*\}", r.stdout, re.S)
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
    searched = open_search(BY_PACKAGE[pkg], query)
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
        print("selftest ok")
        raise SystemExit
    print(f"Phone remote for {SERIAL} on http://{os.uname().nodename}:{PORT}  (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
