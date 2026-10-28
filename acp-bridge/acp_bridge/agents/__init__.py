"""The agents the bridge can run, one file each.

An agent file gives a [Spec]: its id and name, the command that starts it speaking ACP on
stdio, how to tell it is installed, and the words that tell a user how to set it up. Adding
an agent is adding a file here and a line in [ALL].
"""

import shutil
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Spec:
    id: str
    name: str
    argv: list[str]
    needs: list[str]  # commands that must be on PATH for it to run
    setup: str
    env: dict[str, str] = field(default_factory=dict)

    def missing(self) -> list[str]:
        return [c for c in self.needs if shutil.which(c) is None]


from . import claude, codex, hermes, pi  # noqa: E402

ALL: dict[str, Spec] = {s.id: s for s in (claude.SPEC, codex.SPEC, hermes.SPEC, pi.SPEC)}
