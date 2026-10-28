#!/usr/bin/env python3
"""
Put the Rabbit R1 into fastboot mode from a powered-off state.

This is a local, no-browser equivalent of the "Enter Fastboot Mode" button in
rabbit's official web flash tool (https://rabbit-hmi-oss.github.io/flashing/).

Mechanism, taken from that tool's js/fastboot-ops.js -> enterFastbootFromBrom():
the MediaTek preloader (USB 0e8d:2000) exposes a CDC serial port for a short
window after a powered-off device is plugged in. Writing the 8 ASCII bytes
"FASTBOOT" to that port at 115200 baud makes the device reboot into fastboot.

Nothing is written to flash. This only changes which mode the device boots into.

Usage:
    tools/mtkclient/.venv/bin/python scripts/enter-fastboot.py
  then plug in the powered-off R1 when prompted.
"""
import glob
import sys
import time

import serial

BAUD = 115200
CMD = b"FASTBOOT"
TIMEOUT_S = 120
POLL_S = 0.02  # the preloader window is ~1.5 s, so poll fast

IGNORE = ("Bluetooth", "debug-console")


def candidates():
    # macOS names the preloader's CDC port cu.usbmodem*, Linux ttyACM*.
    ports = set(glob.glob("/dev/cu.usbmodem*")) | set(glob.glob("/dev/cu.usbserial*")) \
        | set(glob.glob("/dev/ttyACM*"))
    return {p for p in ports if not any(x in p for x in IGNORE)}


def mtk_ports(ports):
    """The ones of [ports] on a MediaTek USB device (vendor 0e8d), as pyserial sees them;
    all of [ports] when it cannot tell."""
    try:
        from serial.tools import list_ports
        return {p.device for p in list_ports.comports() if p.vid == 0x0E8D} & set(ports)
    except Exception:
        return set(ports)


class Watch:
    """Which poll shows the preloader, fed one list of ports per poll.

    A port counts when it was absent from the poll before, not from a fixed baseline: an
    R1 that restarts can come back under the name it had. With [must_go], a port of those
    has to vanish first (an R1 in download mode after a read, restarting), and what appears
    before that is not the preloader.
    """

    def __init__(self, ports, must_go=()):
        self.prev = set(ports)
        self.must_go = set(must_go)
        self.gone = not self.must_go

    def feed(self, ports):
        ports = set(ports)
        new, self.prev = ports - self.prev, ports
        if not self.gone:
            if self.must_go <= ports:
                return None
            self.gone = True
        return sorted(new)[0] if new else None


class NoPreloader(Exception):
    pass


def send_fastboot(baseline, timeout_s=TIMEOUT_S, must_go=()):
    """Wait for a serial port to appear (see Watch), write FASTBOOT to it, return it.

    Raises NoPreloader on a timeout or when the port never opens. The installer
    (installer/croissant/stages.py) calls this with its own prompts around it.
    """
    deadline = time.time() + timeout_s
    watch = Watch(baseline, must_go)
    port = None
    while time.time() < deadline:
        port = watch.feed(candidates())
        if port:
            break
        time.sleep(POLL_S)
    if not port:
        raise NoPreloader(f"no preloader serial port appeared in {timeout_s}s")

    # The node can appear a few ms before it is openable; retry briefly.
    last = None
    for _ in range(100):
        try:
            with serial.Serial(port, BAUD, timeout=1) as ser:
                ser.write(CMD)
                ser.flush()
            return port
        except (serial.SerialException, OSError) as e:
            last = e
            time.sleep(0.02)
    raise NoPreloader(f"found {port} but could not open it: {last}")


def main():
    baseline = candidates()
    if baseline:
        print(f"note: already present before we start, will be ignored: {sorted(baseline)}")

    print("\n  1. Make sure the R1 is COMPLETELY powered off.")
    print("  2. Make sure the USB cable is UNPLUGGED.")
    print("\nWaiting for the preloader... plug the cable into the powered-off R1 now.")
    print("(Ctrl-C to abort)\n")

    try:
        port = send_fastboot(baseline)
    except NoPreloader as e:
        print(f"\n{e}.")
        print("Try: a different USB cable (must carry data), a different port, or")
        print("hold the side button ~15s to be certain the device is fully off.")
        return 1

    print(f"Preloader detected: {port}")
    print(f'Sent "{CMD.decode()}" at {BAUD} baud.')
    print("\nThe R1 should now reboot and show a FASTBOOT screen.")
    print("Confirm with:  fastboot devices")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nAborted.")
        sys.exit(130)
