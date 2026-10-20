#!/usr/bin/env python3
# The smoke test of a native Counter (scripts/native-image.sh counter): it
# starts the binary in a pseudo-terminal of 100x30, waits for "Count: 0",
# presses +, waits for "Count: 1", presses q, and checks the exit code 0 and
# the exit from the alternate screen (the terminal is restored).
# Usage: python3 scripts/native-smoke.py <binary>   (the CI job "native")
# claude 2026-10-20: created (L5b, ADR 0015); changed: no bytecode files in
# the source tree, a limit on the wait for the exit (review fix)
import fcntl, os, pty, select, signal, struct, sys, termios, time
sys.dont_write_bytecode = True  # as examples/ptycheck/run.sh: no __pycache__ in the source tree
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples", "ptycheck"))
import vt  # noqa: E402  (the terminal emulator of the PTY kit)

binary = os.path.abspath(sys.argv[1])
pid, fd = pty.fork()
if pid == 0:
    os.execve(binary, [binary], {"TERM": "xterm-256color", "PATH": os.environ.get("PATH", "")})
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 100, 0, 0))
screen, raw, t0 = vt.Screen(30, 100), b"", time.monotonic()


def wait_for(text, seconds=20):
    global raw
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if select.select([fd], [], [], 0.05)[0]:
            try:
                data = os.read(fd, 65536)
            except OSError:
                data = b""
            if not data:
                return text in screen.text()
            raw += data
            screen.feed(data.decode("utf-8", "replace"))
        if text in screen.text():
            return True
    return False


ok = wait_for("Count: 0")
print(f"native-smoke: first frame {'after %.0f ms' % ((time.monotonic() - t0) * 1000) if ok else 'MISSING'}")
os.write(fd, b"+")
ok = wait_for("Count: 1", 5) and ok
os.write(fd, b"q")
wait_for("\0", 5)  # reads to the end of the output
status, end = None, time.monotonic() + 10
while status is None and time.monotonic() < end:
    done, st = os.waitpid(pid, os.WNOHANG)
    if done == pid:
        status = st
    else:
        time.sleep(0.05)
if status is None:  # q did not end the binary in 10 s
    os.kill(pid, signal.SIGKILL)
    _, status = os.waitpid(pid, 0)
    print("native-smoke: no exit 10 s after q: killed")
    ok = False
code = os.waitstatus_to_exitcode(status)
restored = b"\x1b[?1049l" in raw
print(f"native-smoke: Count: 1 {'seen' if ok else 'MISSING'}; exit code {code}; alternate screen left: {restored}")
sys.exit(0 if ok and code == 0 and restored else 1)
