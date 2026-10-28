# /// script
# requires-python = ">=3.12"
# ///
"""Run lpac on the Mac against the eUICC in the R1, through a root bridge over adb.

lpac talks to the card with its stdio APDU backend; this script answers those
requests by forwarding them to EsimBridge (a dex run as root on the R1 with
app_process), which opens logical channels through the phone service. HTTP to the
SM-DP+ goes from the Mac through lpac's curl backend.

    uv run device/esim/lpac-r1.py chip info
    uv run device/esim/lpac-r1.py profile list
    uv run device/esim/lpac-r1.py --trace /tmp/apdu.log profile download -a 'LPA:1$...'
    uv run device/esim/lpac-r1.py --sim-reset

Everything after the options is passed to lpac unchanged. See device/esim/README.md.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
LPAC = HERE / "bin" / "lpac"
DEX_LOCAL = HERE / "build" / "esim-bridge.dex"
DEX_DEVICE = "/data/local/tmp/esim-bridge.dex"


def only_device() -> str:
    """The serial of the one device `adb devices` lists; exits when there are none or several."""
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    found = [l.split()[0] for l in out.splitlines()[1:] if l.split()[1:2] == ["device"]]
    if len(found) != 1:
        raise SystemExit(f"{len(found)} devices on adb: pass --serial or set R1_SERIAL")
    return found[0]


def sdk_dir() -> Path:
    props = REPO / "android" / "local.properties"
    for line in props.read_text().splitlines():
        if line.startswith("sdk.dir="):
            return Path(line.split("=", 1)[1])
    return Path.home() / "Library" / "Android" / "sdk"


def build_dex() -> None:
    src = HERE / "EsimBridge.java"
    if DEX_LOCAL.exists() and DEX_LOCAL.stat().st_mtime >= src.stat().st_mtime:
        return
    sdk = sdk_dir()
    android_jar = sdk / "platforms" / "android-33" / "android.jar"
    d8 = sorted((sdk / "build-tools").glob("*/d8"))[-1]
    classes = HERE / "build" / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "--release", "11", "-cp", str(android_jar), "-d", str(classes), str(src)], check=True)
    subprocess.run([str(d8), "--min-api", "33", "--lib", str(android_jar), "--output", str(HERE / "build"),
                    *map(str, classes.glob("*.class"))], check=True)
    (HERE / "build" / "classes.dex").replace(DEX_LOCAL)


def adb(serial: str, *args: str, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, **kw)


def push_dex(serial: str) -> None:
    local = hashlib.sha256(DEX_LOCAL.read_bytes()).hexdigest()
    remote = adb(serial, "shell", f"sha256sum {DEX_DEVICE} 2>/dev/null").stdout.split(" ")[0]
    if remote != local:
        adb(serial, "push", str(DEX_LOCAL), DEX_DEVICE, check=True)


class Bridge:
    def __init__(self, serial: str, trace):
        cmd = f'su -c "CLASSPATH={DEX_DEVICE} app_process /system/bin EsimBridge"'
        self.p = subprocess.Popen(["adb", "-s", serial, "shell", "-T", cmd], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True, bufsize=1)
        self.trace = trace
        first = self.p.stdout.readline().strip()
        if first != "ready":
            raise SystemExit(f"bridge did not start: {first!r}")

    def ask(self, line: str) -> str:
        t0 = time.monotonic()
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        answer = self.p.stdout.readline().strip()
        if self.trace:
            self.trace.write(f"{(time.monotonic() - t0) * 1000:6.0f} ms  > {line}\n{'':11}< {answer}\n")
            self.trace.flush()
        return answer

    def close(self) -> None:
        self.p.stdin.close()
        self.p.wait(timeout=10)


def sim_state(serial: str) -> list[str]:
    return adb(serial, "shell", "getprop gsm.sim.state; getprop vendor.gsm.ril.uicc.mccmnc").stdout.split()


def wait_for(serial: str, want, timeout: float) -> list[str]:
    deadline = time.monotonic() + timeout
    state = sim_state(serial)
    while not want(state) and time.monotonic() < deadline:
        time.sleep(0.5)
        state = sim_state(serial)
    return state


def sim_reset(serial: str, trace) -> int:
    bridge = Bridge(serial, trace)
    t0 = time.monotonic()
    print(f"before: {sim_state(serial)}", file=sys.stderr)
    print(f"simpower 0: {bridge.ask('simpower 0')}", file=sys.stderr)
    state = wait_for(serial, lambda s: s and s[0] != "LOADED", 15)
    print(f"{time.monotonic() - t0:5.1f} s down: {state}", file=sys.stderr)
    print(f"simpower 1: {bridge.ask('simpower 1')}", file=sys.stderr)
    state = wait_for(serial, lambda s: s and s[0] == "LOADED", 60)
    print(f"{time.monotonic() - t0:5.1f} s up: {state}", file=sys.stderr)
    bridge.close()
    return 0 if state and state[0] == "LOADED" else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("R1_SERIAL"))
    ap.add_argument("--trace", type=argparse.FileType("w"), help="log every APDU with its round trip time")
    ap.add_argument("--sim-reset", action="store_true",
                    help="power the SIM down and up so the modem reads the enabled profile, then exit")
    ap.add_argument("lpac_args", nargs=argparse.REMAINDER)
    args = ap.parse_args()
    args.serial = args.serial or only_device()
    if args.sim_reset:
        build_dex()
        push_dex(args.serial)
        return sim_reset(args.serial, args.trace)
    if not LPAC.exists():
        raise SystemExit(f"{LPAC} missing: see device/esim/README.md")

    build_dex()
    push_dex(args.serial)
    bridge = Bridge(args.serial, args.trace)

    env = dict(os.environ, LPAC_APDU="stdio", LPAC_HTTP="curl")
    lpac = subprocess.Popen([str(LPAC), *args.lpac_args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            text=True, bufsize=1, env=env)
    channel = None
    result = None
    try:
        for raw in lpac.stdout:
            msg = json.loads(raw)
            kind, payload = msg.get("type"), msg.get("payload", {})
            if kind != "apdu":
                if kind == "lpa":
                    result = msg
                else:
                    print(json.dumps(msg), file=sys.stderr)
                continue
            func, param = payload.get("func"), payload.get("param")
            reply = {"ecode": 0}
            if func == "logic_channel_open":
                answer = bridge.ask(f"open {param}")
                if answer.startswith("ok "):
                    channel = int(answer[3:])
                    reply = {"ecode": channel}
                else:
                    print(f"bridge: {answer}", file=sys.stderr)
                    reply = {"ecode": -1}
            elif func == "logic_channel_close":
                if channel is not None:
                    bridge.ask(f"close {channel}")
                    channel = None
            elif func == "transmit":
                answer = bridge.ask(f"tx {channel} {param}")
                if answer.startswith("ok "):
                    reply = {"ecode": 0, "data": answer[3:]}
                else:
                    print(f"bridge: {answer}", file=sys.stderr)
                    reply = {"ecode": -1}
            lpac.stdin.write(json.dumps({"type": "apdu", "payload": reply}) + "\n")
            lpac.stdin.flush()
    finally:
        code = lpac.wait()
        bridge.close()
    if result is not None:
        print(json.dumps(result, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
