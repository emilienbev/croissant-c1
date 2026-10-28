# acp-bridge

Runs coding agents on your computer and lets Croissant's Agents app drive them from the R1:
your sessions grouped by folder, new chats, the replies as they stream, and the agent's
questions ("may I run this command?") answered on the device.

It speaks [ACP](https://agentclientprotocol.com), the Agent Client Protocol editors use to
plug agents in. The bridge starts each agent on this computer as its ACP adapter, under your
own install and sign-in, and serves them all to the R1 over one WebSocket. Nothing goes
through anyone else's server.

| Agent | Runs through | Needs on this computer |
|---|---|---|
| Claude Code | [claude-agent-acp](https://github.com/agentclientprotocol/claude-agent-acp) | `claude`, signed in |
| Codex (ChatGPT's coding agent) | [codex-acp](https://github.com/agentclientprotocol/codex-acp) | `codex`, signed in (the ChatGPT desktop app's sessions show too) |
| Hermes Agent | itself, `hermes acp` ([its ACP page](https://hermes-agent.nousresearch.com/docs/user-guide/features/acp)) | `hermes`, its model set with `hermes model`, its ACP extra added |
| Pi | [pi-acp](https://github.com/svkozak/pi-acp), over `pi --mode rpc` | `pi` 0.81 or newer, its model set up |

The adapters come from npm through `npx`, at the versions pinned in `acp_bridge/agents/`, so
Node.js 22 or newer is needed too. One agent is one file there. An agent this computer cannot
run yet is listed on the R1 as not set up, and `acp-bridge agents` says what it needs.

## Run it

```bash
cd acp-bridge && uv run acp-bridge serve
```

It listens on port 8775 on every address of this computer, and shows the pairing QRs first:
one for the home network's address and one for the tailnet's, when Tailscale is up. Scan one
once with the R1 (Agents, then the scan key). Each names its own address first and the other
after it, so either works at home and away. `uv run acp-bridge pair` shows them again. They
hold a token too. Every connection needs that
token; `acp-bridge pair --new` makes a new one, and every R1 paired before scans again. At
home the R1 reaches the bridge over Wi-Fi; away, over Tailscale. The token and the port are
kept in `~/.config/acp-bridge/config.json`.

## Try an agent without an R1

```bash
uv run acp-bridge agents
uv run acp-bridge sessions codex
uv run acp-bridge try claude "What is in this folder?" --cwd ~/some/project
```

`try` asks you any permission the agent needs. `--log FILE` writes every ACP message both
ways, to see what an agent really sends.

## What the R1 and the bridge say

The messages are described at the top of `acp_bridge/server.py`. The agent's own updates pass
through as ACP has them. The bridge keeps each session's updates while it runs, so an R1 that
slept gets only what it missed, a question still waiting among it. Opening a session that is
not in a turn here loads it from the agent again, so what was said on the computer since shows.

## Known limits

- The bridge keeps what it heard in memory: restarted, it loads a session again from the
  agent's own history.
- What is said on the computer shows on the R1 the next time the session is opened there, not
  while it is open.
- Claude Code replays a session from its last compaction only.
- Claude Code's Remote Control (claude.ai) is Anthropic's own and cannot be used here; the
  bridge runs your `claude` instead. It never reads your sign-in or keys.
