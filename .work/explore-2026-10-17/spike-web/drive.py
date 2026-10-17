"""Spike driver: the spikeweb app in a PTY (100x30) and headless Chromium against its HTTP server, with shared state."""
import json
import os
import pty
import signal
import struct
import subprocess
import sys
import termios
import fcntl
import threading
import time
import urllib.request

S = "/tmp/claude-0/-home-user-tui4s/6d712dbd-bef7-57d0-b343-fd9d5dc2fafb/scratchpad"
WT = f"{S}/wt-spike-web"
OUT = f"{S}/spike-web/runs/" + os.environ.get("SPIKE_RUN", "default")
os.makedirs(OUT, exist_ok=True)
DRIVER = f"{S}/spike-web/driver"
sys.path.insert(0, f"{WT}/examples/ptycheck")
import vt  # noqa: E402

PORT = int(os.environ.get("SPIKE_PORT", "18080"))
URL = f"http://127.0.0.1:{PORT}/"
ROWS, COLS = 30, 100
RAW = f"{OUT}/session.log"
SPIKE_LOG = f"{OUT}/spike-app.log"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -- {detail}"), flush=True)


raw = bytearray()
restore_at = []
lock = threading.Lock()


def screen_text():
    with lock:
        data = bytes(raw)
    scr = vt.Screen(ROWS, COLS)
    scr.feed(data.decode("utf-8", "replace"))
    return scr.text(), scr.in_alt


def wait_screen(pred, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        txt, _ = screen_text()
        if pred(txt):
            return True, txt
        time.sleep(0.05)
    return False, screen_text()[0]


for f in (RAW, SPIKE_LOG):
    if os.path.exists(f):
        os.remove(f)

env = dict(os.environ)
env.pop("JAVA_TOOL_OPTIONS", None)  # avoids the JVM's "Picked up ..." line in the terminal
env["TERM"] = "xterm-256color"
env["JAVA_HOME"] = "/usr/lib/jvm/java-25-openjdk-amd64"  # the container default is JDK 21
env["SPIKE_LOG"] = SPIKE_LOG
env["JAVA_OPTS"] = os.environ.get("SPIKE_JAVA_OPTS", "")

t_spawn = time.time()
pid, fd = pty.fork()
if pid == 0:
    fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
    os.chdir(OUT)
    extra = os.environ.get("SPIKE_EXTRA_CP")
    if extra:
        cp = open(f"{WT}/examples/target/bin/classpath").read().strip() + ":" + extra
        java = env["JAVA_HOME"] + "/bin/java"
        flags = ["--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Xss16m",
                 "-Dkyo.scheduler.cycleIntervalNs=10000000", "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
                 "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100"]
        os.execve(java, [java] + flags + ["-cp", cp, "examples.spikeweb.SpikeWebApp", str(PORT)], env)
    os.execve(f"{WT}/examples/target/bin/spikeweb", ["spikeweb", str(PORT)], env)

with open(f"{OUT}/app.pid", "w") as pf:
    pf.write(str(pid))


def reader():
    while True:
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            break
        if not chunk:
            break
        with lock:
            raw.extend(chunk)
            if b"\x1b[?1049l" in chunk:
                restore_at.append(time.time())


th = threading.Thread(target=reader, daemon=True)
th.start()


def send(b):
    os.write(fd, b)


# ---- first frame and HTTP readiness ----
t_frame = None
t_http = None
deadline = time.time() + 60
while time.time() < deadline and (t_frame is None or t_http is None):
    if t_frame is None and "Count:" in screen_text()[0]:
        t_frame = time.time()
    if t_http is None:
        try:
            with urllib.request.urlopen(URL, timeout=1) as r:
                if r.status == 200:
                    t_http = time.time()
                    page0 = r.read().decode()
        except Exception:
            pass
    time.sleep(0.02)
check("first terminal frame (Count:)", t_frame is not None)
check("HTTP GET / is 200", t_http is not None)
if t_frame is None or t_http is None:
    print(screen_text()[0]); print(bytes(raw)[-2000:]); os.kill(pid, signal.SIGTERM); sys.exit(2)
first_screen = screen_text()[0]
with open(f"{OUT}/terminal-first.txt", "w") as f:
    f.write(first_screen + "\n")

def echo_latency(label, ch):
    t0 = time.time()
    send(ch.encode())
    ok, _ = wait_screen(lambda t: f"Message: {ch}" in t, 5)
    dt = time.time() - t0
    send(b"\x7f")
    wait_screen(lambda t: f"Message: {ch}" not in t, 5)
    print(f"  INFO terminal echo of one key ({label}): {dt:.3f}s ok={ok}", flush=True)
    return round(dt, 3)


time.sleep(0.5)
lat_before = [echo_latency("no browser", c) for c in "xyz"]

# ---- browser ----
benv = dict(os.environ)
benv["NODE_PATH"] = subprocess.check_output(["npm", "root", "-g"], text=True).strip()
benv["PLAYWRIGHT_BROWSERS_PATH"] = "/opt/pw-browsers"
benv.pop("JAVA_TOOL_OPTIONS", None)
node = subprocess.Popen(["node", f"{DRIVER}/browser.js"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=open(f"{OUT}/browser-stderr.log", "w"), text=True, env=benv, cwd=DRIVER)
with open(f"{OUT}/node.pid", "w") as pf:
    pf.write(str(node.pid))


def b(**cmd):
    node.stdin.write(json.dumps(cmd) + "\n")
    node.stdin.flush()
    while True:
        line = node.stdout.readline()
        if not line:
            return {"ok": False, "error": "node exited"}
        if line.startswith("RESULT "):
            r = json.loads(line[7:])
            print("  browser", cmd.get("op"), cmd.get("name", ""), "->", json.dumps(r)[:300], flush=True)
            return r


r = b(op="open", name="A", url=URL)
check("browser A opens the page and finds #count", r.get("ok"), r)
check("browser A starts at Count: 0", r.get("ok") and r["state"]["count"] == "Count: 0", r)

r = b(op="click", name="A", selector="#inc", n=3)
r = b(op="waitFor", name="A", pred="s.count === 'Count: 3'", timeout=5000)
check("browser A: 3 clicks give Count: 3 in the DOM", r.get("ok"), r)
t0 = time.time()
ok, txt = wait_screen(lambda t: "Count: 3" in t, 5)
check("terminal shows Count: 3 after the browser clicks", ok, txt)
print(f"  terminal caught up {time.time() - t0:.3f}s after the DOM showed 3", flush=True)

lat_after = [echo_latency("1 browser connected", c) for c in "xyz"]

# type a message in the PTY (the input has the focus at start)
for ch in "hello from tty":
    send(ch.encode())
    time.sleep(0.02)
ok, txt = wait_screen(lambda t: "hello from tty" in t, 5)
check("terminal input shows the typed text", ok, txt)
send(b"\r")
ok, txt = wait_screen(lambda t: "1. hello from tty" in t, 5)
check("terminal: Enter adds '1. hello from tty'", ok, txt)
r = b(op="waitFor", name="A", pred="s.messages.includes('1. hello from tty')", timeout=5000)
check("browser A shows the message typed in the terminal", r.get("ok"), r)

# a second page
r = b(op="open", name="B", url=URL)
check("browser B (second page) sees Count: 3", r.get("ok") and r["state"]["count"] == "Count: 3", r)
check("browser B sees the message", r.get("ok") and "1. hello from tty" in r["state"]["messages"], r)

# web -> terminal and web -> other web page
r = b(op="type", name="B", selector="#draft", text="hello from web")
r = b(op="waitFor", name="B", pred="s.messages.includes('2. hello from web')", timeout=5000)
check("browser B: typing + Enter adds '2. hello from web'", r.get("ok"), r)
ok, txt = wait_screen(lambda t: "2. hello from web" in t, 5)
check("terminal shows the message typed in browser B", ok, txt)
r = b(op="waitFor", name="A", pred="s.messages.includes('2. hello from web')", timeout=5000)
check("browser A shows the message typed in browser B", r.get("ok"), r)

# terminal button -> browsers
send(b"\t")
time.sleep(0.3)
send(b"\r")
ok, txt = wait_screen(lambda t: "Count: 4" in t, 5)
check("terminal: Tab to +1 and Enter gives Count: 4", ok, txt)
r = b(op="waitFor", name="A", pred="s.count === 'Count: 4'", timeout=5000)
check("browser A shows Count: 4 (pressed in the terminal)", r.get("ok"), r)
r = b(op="waitFor", name="B", pred="s.count === 'Count: 4'", timeout=5000)
check("browser B shows Count: 4", r.get("ok"), r)

# shared draft: what is typed in the terminal input shows in the browser input
send(b"\x1b[Z")  # Shift-Tab back to the input
time.sleep(0.3)
for ch in "draft":
    send(ch.encode())
    time.sleep(0.02)
t0 = time.time()
r = b(op="waitFor", name="A", pred="s.draft === 'draft'", timeout=3000)
t_b = time.time() - t0
ok, txt = wait_screen(lambda t: "Message: draft" in t, 5)
t_t = time.time() - t0
print(f"  INFO after the last typed key: browser input had 'draft' after {t_b:.3f}s, terminal after {t_t:.3f}s (ok={ok})", flush=True)
check("INFO shared draft: terminal typing shows in browser A's input", True, r)
print(f"  INFO browser A draft value after terminal typing: {r.get('state', {}).get('draft')!r}", flush=True)

b(op="screenshot", name="A", path=f"{OUT}/browser.png")
b(op="html", name="A", path=f"{OUT}/browser-A.html")
r = b(op="computed", name="A", selectors=["body", "h1", "#count", "#inc", "#draft", "#messages", "#messages li"])
with open(f"{OUT}/browser-computed.json", "w") as f:
    json.dump(r, f, indent=1)
r = b(op="console", name="A")
with open(f"{OUT}/browser-console.txt", "w") as f:
    f.write("\n".join(r.get("lines", [])) + "\n")

final_txt, in_alt = screen_text()
with open(f"{OUT}/terminal.txt", "w") as f:
    f.write(final_txt + "\n")

# stray output: log-like text on the alternate screen
bad = [w for w in ("INFO", "WARN", "ERROR", "Unsafe", "Exception", "kyo.", "Picked up", "http-ready") if w in final_txt]
check("no log text on the terminal screen", not bad, bad)

# ---- quit ----
if os.environ.get("SPIKE_QUIT_WITH_BROWSER"):
    t_quit = time.time()
    send(b"\x1b")  # quit while both pages hold their WebSocket
else:
    b(op="quit")
    node.wait(timeout=10)
    t_quit = time.time()
    send(b"\x1b")
code = None
deadline = time.time() + 15
while time.time() < deadline:
    wpid, status = os.waitpid(pid, os.WNOHANG)
    if wpid == pid:
        code = os.waitstatus_to_exitcode(status)
        t_exit = time.time()
        print(f"  INFO quit: restore bytes after {restore_at[0]-t_quit if restore_at else None}s, exit after {t_exit-t_quit:.3f}s", flush=True)
        break
    time.sleep(0.05)
th.join(timeout=2)
if os.environ.get("SPIKE_QUIT_WITH_BROWSER"):
    time.sleep(1.5)
    r = b(op="state", name="A")
    print(f"  INFO browser A after the app quit: {json.dumps(r)[:300]}", flush=True)
    b(op="screenshot", name="A", path=f"{OUT}/browser-after-quit.png")
    r = b(op="console", name="A")
    print(f"  INFO browser console: {r.get('lines')}", flush=True)
    b(op="quit")
    node.wait(timeout=10)
check("Esc quits with exit code 0", code == 0, code)
tail = bytes(raw).decode("utf-8", "replace")
check("restore: leaves the alternate screen", "\x1b[?1049l" in tail)
check("restore: shows the cursor", "\x1b[?25h" in tail[tail.rfind("\x1b[?1049h"):])
import re
ESC = re.compile(r"\x1b(\[[0-9;?<>=]*[ -/]*[@-~]|\][^\x07]*\x07|[78=>]|\(B)")
after = ESC.sub("", tail.split("\x1b[?1049l")[-1])
with open(f"{OUT}/after-restore.txt", "w") as f:
    f.write(after)
print(f"  after restore (repr): {after[:500]!r}", flush=True)
check("after restore: no 'Scope finalizer failed'", "Scope finalizer failed" not in after, after[:300])
check("after restore: nothing at all", after.strip() == "", after[:300])

with open(RAW, "wb") as f:
    f.write(bytes(raw))

# ---- timings ----
app_log = open(SPIKE_LOG).read() if os.path.exists(SPIKE_LOG) else ""
jvm_start = None
for line in app_log.splitlines():
    for tok in line.split():
        if tok.startswith("jvmStart="):
            jvm_start = int(tok.split("=")[1]) / 1000.0
timing = {
    "spawn_to_first_frame_s": round(t_frame - t_spawn, 3) if t_frame else None,
    "spawn_to_http_200_s": round(t_http - t_spawn, 3) if t_http else None,
    "jvm_start_to_first_frame_s": round(t_frame - jvm_start, 3) if (t_frame and jvm_start) else None,
    "jvm_start_to_http_200_s": round(t_http - jvm_start, 3) if (t_http and jvm_start) else None,
    "spawn_to_jvm_start_s": round(jvm_start - t_spawn, 3) if jvm_start else None,
    "quit_to_exit_s": round(time.time() - t_quit, 3),
    "quit_to_restore_bytes_s": round(restore_at[0] - t_quit, 3) if restore_at else None,
    "echo_latency_no_browser_s": lat_before,
    "echo_latency_one_browser_s": lat_after,
    "app_log": app_log,
}
with open(f"{OUT}/timings.json", "w") as f:
    json.dump(timing, f, indent=1)
print(json.dumps(timing, indent=1))
fails = [r for r in results if not r[1]]
print(f"{len(results) - len(fails)} PASS, {len(fails)} FAIL")
sys.exit(1 if fails else 0)
