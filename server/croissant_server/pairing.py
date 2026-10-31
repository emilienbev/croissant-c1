"""The code the C1's onboarding scans to find this server."""

from __future__ import annotations

import ipaddress
import logging
import os
import secrets
import socket
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlencode

import segno

from .config import TAILNET, is_lan

log = logging.getLogger(__name__)

# A quiet zone of this many modules around the code. The spec asks for four; two scans
# fine and saves rows in a terminal.
BORDER = 2

# The LAN token's length in random bytes: 32 URL-safe characters.
TOKEN_BYTES = 24

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


def lan_host(hosts: list[str]) -> str | None:
    """The first of the addresses the server binds to that is on a private LAN."""
    return next((h for h in hosts if is_lan(h)), None)


def payload(hosts: list[str], port: int, name: str, lan_token: str | None = None) -> str | None:
    """
    What the code holds: `croissant://server?host=...&port=...&name=...`, and `&token=...`
    when the host is a LAN one. The Android onboarding parses exactly this. With a
    [lan_token] and a LAN address the code gives that address, since the server listens
    there because it was asked to (`--lan`); else the tailnet's. None when there is neither.
    """
    host = lan_host(hosts) if lan_token else None
    if host is not None:
        fields = {"host": host, "port": port, "name": name, "token": lan_token}
    else:
        host = tailnet_host(hosts)
        if host is None:
            return None
        fields = {"host": host, "port": port, "name": name}
    return "croissant://server?" + urlencode(fields)


def lan_token(path: Path) -> str:
    """
    The token a C1 paired over the LAN sends on every request (app.py, `gated`), made once
    and kept in [path], mode 600, so a paired C1 stays paired across restarts. Deleting the
    file makes a new one, and every C1 paired over the LAN scans the code again.
    """
    try:
        kept = path.read_text().strip()
    except FileNotFoundError:
        kept = ""
    if kept:
        return kept
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    token = secrets.token_urlsafe(TOKEN_BYTES)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token + "\n")
    log.info("made a new LAN pairing token in %s", path)
    return token


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


def announce(hosts: list[str], port: int, color: bool, lan_token: str | None = None) -> None:
    """Print the code for the C1 to scan, or say why there is none."""
    text = payload(hosts, port, machine_name(), lan_token)
    if text is None:
        log.info("no tailnet address to put in a pairing code")
        return
    if lan_token and lan_host(hosts):
        print(f"\nScan on the C1's Server step. This code is the way in over the LAN: show it only "
              f"to your C1.\n\n{render(text, color)}\n", flush=True)
    else:
        print(f"\nScan on the C1's Server step:\n\n{render(text, color)}\n\n{text}\n", flush=True)
