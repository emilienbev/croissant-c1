"""Hermes Agent (Nous Research), which speaks ACP itself: `hermes acp`.

hermes-agent.nousresearch.com/docs/user-guide/features/acp. It runs with the provider and key
Hermes is set up with (`hermes model`, ~/.hermes); the bridge never reads them. It asks before
a dangerous command, and denies it when nobody answers in approvals.timeout (300 s by default).
"""

from . import Spec

SPEC = Spec(
    id="hermes",
    name="Hermes",
    argv=["hermes", "acp"],
    needs=["hermes"],
    setup="Install Hermes Agent (hermes-agent.nousresearch.com), pick its model with `hermes model`,"
          " add its ACP extra as its ACP page says, and check it with `hermes acp --check`.",
)
