"""Pi, the coding agent (pi.dev), through the ACP adapter that runs `pi --mode rpc`.

github.com/svkozak/pi-acp. It runs the user's own `pi`, with the providers and keys pi is set
up with, and keeps pi's own sessions (~/.pi/agent/sessions), so a session started here goes on
in the terminal too. The bridge never reads pi's keys.
"""

from . import Spec

ADAPTER = "pi-acp@0.0.34"

SPEC = Spec(
    id="pi",
    name="Pi",
    argv=["npx", "-y", ADAPTER],
    needs=["npx", "pi"],
    setup="Install pi (npm install -g @earendil-works/pi-coding-agent, 0.81 or newer) and set up its"
          " model with `pi` once.",
)
