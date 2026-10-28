"""What the bridge keeps between runs, in ~/.config/acp-bridge/config.json:

  token     the secret every R1 sends; made on first run, new with `acp-bridge pair --new`
  port      where it listens, on every address of this computer
  folders   where a new chat may start, besides the folders the agents already know

And how an R1 finds it: the addresses it can be reached at (this computer on the home
network, and on the tailnet when Tailscale is up), one pairing QR for each, which names its
own address first and the other after it, so either works at home and away.
"""

import json
import os
import secrets
import shutil
import socket
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path

PATH = Path(os.environ.get("ACP_BRIDGE_CONFIG", Path.home() / ".config" / "acp-bridge" / "config.json"))
PORT = 8775


@dataclass
class Config:
    token: str = ""
    port: int = PORT
    folders: list[str] = field(default_factory=list)

    @classmethod
    def load(cls) -> "Config":
        config = cls(**json.loads(PATH.read_text())) if PATH.exists() else cls()
        if not config.token:
            config.token = secrets.token_urlsafe(24)
            config.save()
        return config

    def save(self) -> None:
        PATH.parent.mkdir(parents=True, exist_ok=True)
        PATH.write_text(json.dumps(asdict(self), indent=2) + "\n")
        PATH.chmod(0o600)


def addresses() -> list[tuple[str, str]]:
    """This computer's IPv4 addresses an R1 can reach, each with where: the home network's, then the tailnet's."""
    found: list[tuple[str, str]] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # no packet goes out: it only picks the route's address
            found.append(("home network", s.getsockname()[0]))
    except OSError:
        pass
    if tailscale := shutil.which("tailscale"):
        try:
            out = subprocess.run([tailscale, "ip", "-4"], capture_output=True, text=True, timeout=5).stdout
            found += [("tailnet", a) for a in out.split() if a and a not in {x for _, x in found}]
        except (OSError, subprocess.TimeoutExpired):
            pass
    return found


def pairing(config: Config, first: str | None = None) -> str:
    """What a QR holds: acp-bridge://<address>:<port>?token=...&also=<the others>, [first] the address it names first."""
    hosts = [a for _, a in addresses()] or ["127.0.0.1"]
    first = first if first in hosts else hosts[0]
    rest = [a for a in hosts if a != first]
    link = f"acp-bridge://{first}:{config.port}?token={config.token}"
    return link + (f"&also={','.join(rest)}" if rest else "")
