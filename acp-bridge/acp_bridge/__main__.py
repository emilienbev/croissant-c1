"""acp-bridge: runs ACP agents on this computer and serves them to the R1.

  acp-bridge serve                           serve the agents to paired R1s (acp_bridge/server.py),
                                             the pairing QR shown first in a terminal
  acp-bridge pair [--new]                    the QR again; --new makes a new token, and every R1
                                             paired before has to scan again
And to drive one agent from the terminal, without an R1:
  acp-bridge agents                          which agents this computer can run
  acp-bridge sessions <agent> [--cwd DIR]    the agent's sessions, grouped by folder
  acp-bridge try <agent> <prompt> [--cwd DIR] [--allow|--deny] [--log FILE]
                                             a new session, one prompt, the reply streamed;
                                             a permission is asked here, or answered by
                                             --allow (first allow option) or --deny
"""

import argparse
import asyncio
import logging
import sys
import time
from collections import defaultdict
from pathlib import Path

from .acp import Agent, Json
from .agents import ALL, Spec
from .config import PATH, Config, addresses, pairing


def spec_of(id_: str) -> Spec:
    if id_ not in ALL:
        sys.exit(f"no agent {id_!r}: {', '.join(ALL)}")
    spec = ALL[id_]
    if missing := spec.missing():
        sys.exit(f"{spec.name} needs {', '.join(missing)}. {spec.setup}")
    return spec


def show_qr(config: Config) -> None:
    """A QR for each address, side by side when the terminal is wide enough, each under its name."""
    import io
    import shutil

    import segno
    blocks = []
    for where, address in addresses() or [("this computer", "127.0.0.1")]:
        out = io.StringIO()
        segno.make(pairing(config, address), error="m").terminal(out=out, compact=True)
        lines = out.getvalue().rstrip("\n").split("\n")
        blocks.append([f"  {where}, {address}", ""] + lines)
    import re
    shown = lambda line: len(re.sub(r"\x1b\[[0-9;]*m", "", line))  # segno's lines carry colour codes
    width = max(shown(line) for b in blocks for line in b) + 4
    if len(blocks) > 1 and width * len(blocks) <= shutil.get_terminal_size((80, 24)).columns:
        for row in zip(*(b + [""] * (max(map(len, blocks)) - len(b)) for b in blocks)):
            print("".join(line + " " * (width - shown(line)) for line in row).rstrip())
    else:
        for b in blocks:
            print("\n".join(b) + "\n")
    print(f"\nScan either with the R1 (Agents, then the scan key): at home the first, away the tailnet's;"
          f" each knows the other address too. The token is in {PATH}.\n", flush=True)


def logger(path: str | None):
    if not path:
        return None
    out = open(path, "a", buffering=1)
    return lambda way, line: out.write(f"{time.strftime('%H:%M:%S')} {way} {line}\n")


async def sessions(spec: Spec, cwd: str | None) -> None:
    agent = Agent(spec.argv, cwd or str(Path.home()), spec.env)
    await agent.start()
    try:
        if not agent.can_list:
            print(f"{spec.name} does not list its sessions (no sessionCapabilities.list)")
            return
        found: list[Json] = []
        cursor = None
        while True:
            params: Json = {"cwd": cwd} if cwd else {}
            if cursor:
                params["cursor"] = cursor
            page = await agent.call("session/list", params)
            found += page.get("sessions", [])
            if not (cursor := page.get("nextCursor")):
                break
        folders: dict[str, list[Json]] = defaultdict(list)
        for s in found:
            folders[s["cwd"]].append(s)
        latest = lambda ss: max((s.get("updatedAt") or "" for s in ss), default="")
        for folder, ss in sorted(folders.items(), key=lambda f: latest(f[1]), reverse=True):
            print(f"\n{Path(folder).name or folder}  ({folder})")
            for s in sorted(ss, key=lambda s: s.get("updatedAt") or "", reverse=True):
                print(f"  {(s.get('updatedAt') or '')[:16]:16}  {s.get('title') or s['sessionId']}")
        print(f"\n{len(found)} sessions in {len(folders)} folders")
    finally:
        await agent.stop()


async def try_(spec: Spec, prompt: str, cwd: str, answer: str | None, log) -> None:
    async def on_notify(method: str, params: Json) -> None:
        if method != "session/update":
            return
        u = params.get("update", {})
        kind = u.get("sessionUpdate")
        if kind == "agent_message_chunk" and u.get("content", {}).get("type") == "text":
            print(u["content"]["text"], end="", flush=True)
        elif kind == "agent_thought_chunk":
            pass
        elif kind == "tool_call":
            print(f"\n[tool {u.get('kind', '')}: {u.get('title', '')} {u.get('status', '')}]", flush=True)
        elif kind == "tool_call_update" and u.get("status") in ("completed", "failed"):
            print(f"[tool {u.get('status')}]", flush=True)
        elif kind == "plan":
            print("\n[plan] " + " / ".join(e.get("content", "") for e in u.get("entries", [])), flush=True)

    async def on_request(method: str, params: Json) -> Json:
        if method != "session/request_permission":
            raise Exception(f"{method} not offered")
        options = params.get("options", [])
        title = params.get("toolCall", {}).get("title", "a tool")
        print(f"\n[permission] {title}")
        for i, o in enumerate(options):
            print(f"  {i}. {o['name']} ({o['kind']})")
        if answer == "allow":
            pick = next((o for o in options if o["kind"].startswith("allow")), None)
        elif answer == "deny":
            pick = next((o for o in options if o["kind"].startswith("reject")), None)
        else:
            line = await asyncio.to_thread(input, "  pick: ")
            pick = options[int(line)] if line.strip().isdigit() and int(line) < len(options) else None
        print(f"  -> {pick['name'] if pick else 'cancelled'}")
        if not pick:
            return {"outcome": {"outcome": "cancelled"}}
        return {"outcome": {"outcome": "selected", "optionId": pick["optionId"]}}

    agent = Agent(spec.argv, cwd, spec.env, on_notify, on_request, log)
    info = await agent.start()
    caps = info.get("agentCapabilities", {})
    print(f"{spec.name}: {info.get('agentInfo', {}).get('title') or info.get('agentInfo', {}).get('name', '')}"
          f" {info.get('agentInfo', {}).get('version', '')}, load {caps.get('loadSession', False)},"
          f" session {sorted((caps.get('sessionCapabilities') or {}).keys())},"
          f" auth {[m.get('id') for m in info.get('authMethods', [])]}")
    try:
        new = await agent.call("session/new", {"cwd": cwd, "mcpServers": []})
        print(f"session {new['sessionId']}\n")
        started = time.monotonic()
        done = await agent.call("session/prompt", {
            "sessionId": new["sessionId"], "prompt": [{"type": "text", "text": prompt}]})
        print(f"\n\n[{done.get('stopReason')}, {time.monotonic() - started:.1f} s]")
    finally:
        await agent.stop()


def main() -> None:
    p = argparse.ArgumentParser(prog="acp-bridge", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("serve")
    pa = sub.add_parser("pair")
    pa.add_argument("--new", action="store_true")
    sub.add_parser("agents")
    s = sub.add_parser("sessions")
    s.add_argument("agent")
    s.add_argument("--cwd")
    t = sub.add_parser("try")
    t.add_argument("agent")
    t.add_argument("prompt")
    t.add_argument("--cwd", default=".")
    t.add_argument("--log")
    a = t.add_mutually_exclusive_group()
    a.add_argument("--allow", dest="answer", action="store_const", const="allow")
    a.add_argument("--deny", dest="answer", action="store_const", const="deny")
    args = p.parse_args()

    if args.command == "serve":
        from .server import Bridge
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
        config = Config.load()
        print(f"acp-bridge on {', '.join(a for _, a in addresses()) or 'no address'}, port {config.port}.", flush=True)
        # The QR holds the token: shown in a terminal only, never into a log or a pipe.
        if sys.stdout.isatty():
            show_qr(config)
        else:
            print("Pair an R1 with: acp-bridge pair", flush=True)
        try:
            asyncio.run(Bridge(config).run())
        except KeyboardInterrupt:
            pass
    elif args.command == "pair":
        import secrets
        config = Config.load()
        if args.new:
            config.token = secrets.token_urlsafe(24)
            config.save()
        show_qr(config)
    elif args.command == "agents":
        for spec in ALL.values():
            missing = spec.missing()
            print(f"{spec.id:8} {spec.name:8} {'ready' if not missing else 'needs ' + ', '.join(missing)}")
    elif args.command == "sessions":
        asyncio.run(sessions(spec_of(args.agent), str(Path(args.cwd).resolve()) if args.cwd else None))
    else:
        asyncio.run(try_(spec_of(args.agent), args.prompt, str(Path(args.cwd).resolve()),
                         args.answer, logger(args.log)))


if __name__ == "__main__":
    main()
