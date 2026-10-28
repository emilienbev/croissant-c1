"""The code the C1's onboarding scans to find this server."""

from __future__ import annotations

import ipaddress
import logging
import socket
import subprocess
import sys
from urllib.parse import urlencode

import segno

from .config import TAILNET

log = logging.getLogger(__name__)

# A quiet zone of this many modules around the code. The spec asks for four; two scans
# fine and saves rows in a terminal.
BORDER = 2

# Black modules on a white ground, whatever the terminal's own colours are, so the code
# reads the same on a dark theme and a light one (256-colour palette: 16 black, 231 white).
INK = "\x1b[38;5;16;48;5;231m"
RESET = "\x1b[0m"


def tailnet_host(hosts: list[str]) -> str | None:
    """The first of the addresses the server binds to that is a tailnet one."""
    for h in hosts:
        try:
            if ipaddress.ip_address(h) in TAILNET:
                return h
        except ValueError:
            continue
    return None


def machine_name() -> str:
    """
    A name for this machine: on macOS its computer name, which is also what Tailscale calls
    it (the hostname is often a DHCP one like `Mac-3.lan`); elsewhere the hostname's first label.
    """
    if sys.platform == "darwin":
        try:
            name = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True,
                                  text=True, timeout=2).stdout.strip()
            if name:
                return name
        except (OSError, subprocess.SubprocessError):
            pass
    return socket.gethostname().split(".")[0]


def payload(hosts: list[str], port: int, name: str) -> str | None:
    """
    What the code holds: `croissant://server?host=...&port=...&name=...`. The Android
    onboarding parses exactly this. None when there is no tailnet address to put in it.
    """
    host = tailnet_host(hosts)
    if host is None:
        return None
    return "croissant://server?" + urlencode({"host": host, "port": port, "name": name})


def render(text: str, color: bool = True) -> str:
    """
    The code as half blocks, two rows of modules to a line. With `color` it is black on
    white; without, plain block characters (light modules are the filled ones), for a
    log file.
    """
    rows = [bytes(r) for r in segno.make(text, error="m").matrix]
    side = len(rows) + 2 * BORDER
    blank = bytes(side)
    grid = [blank] * BORDER + [bytes(BORDER) + r + bytes(BORDER) for r in rows] + [blank] * BORDER
    if len(grid) % 2:
        grid.append(blank)
    if color:
        glyph = {(0, 0): " ", (1, 0): "▀", (0, 1): "▄", (1, 1): "█"}
    else:
        glyph = {(0, 0): "█", (1, 0): "▄", (0, 1): "▀", (1, 1): " "}
    lines = []
    for top, bottom in zip(grid[0::2], grid[1::2]):
        line = "".join(glyph[t, b] for t, b in zip(top, bottom))
        lines.append(f"{INK}{line}{RESET}" if color else line)
    return "\n".join(lines)


def announce(hosts: list[str], port: int, color: bool) -> None:
    """Print the code for the C1 to scan, or say why there is none."""
    text = payload(hosts, port, machine_name())
    if text is None:
        log.info("no tailnet address to put in a pairing code")
        return
    print(f"\nScan on the C1's Server step:\n\n{render(text, color)}\n\n{text}\n", flush=True)
