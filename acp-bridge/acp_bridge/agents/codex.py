"""Codex (ChatGPT's coding agent), through the ACP adapter over the Codex app-server.

github.com/agentclientprotocol/codex-acp. CODEX_PATH points it at the user's own codex, so
it shares that install's sign-in and sessions, rather than the copy the adapter bundles.
"""

import shutil

from . import Spec

ADAPTER = "@agentclientprotocol/codex-acp@2.2.2"

SPEC = Spec(
    id="codex",
    name="Codex",
    argv=["npx", "-y", ADAPTER],
    needs=["npx", "codex"],
    setup="Install Codex (or the ChatGPT desktop app's `codex`) and sign in once with `codex`.",
    env={"CODEX_PATH": shutil.which("codex") or "codex", "NO_BROWSER": "1"},
)
