# Agents

<p align="center">
  <img alt="The agents page: a card for each agent in its own colour, Claude on the wheel with 1 running, Codex with 1 running, Hermes dimmed as not set up, and Pi, the Claude card ringed" src="../images/features/agents-list.png" width="300">
  <img alt="A Claude session: add a dark mode switch on an orange card, the reply in white, an edit to settings.tsx as a tool line, and a card asking to edit settings.tsx with always, allow and reject, the folder key at the bottom right" src="../images/features/agents-session.png" width="300">
</p>

Coding agents such as Claude Code and Codex, running on your computer and driven from the C1.

- On your computer, start [acp-bridge](../../acp-bridge/README.md) with `acp-bridge serve`. Then turn the wheel on Home to agents, tap it, and scan one of its QR codes with the scan key.
- Each agent is a card in its own colour, with how many of its sessions run under its name. Tap one for its sessions, grouped by folder, newest first. A yellow dot marks one waiting on you. Press and hold a card to pin the agent to the wheel.
- In a folder, open a session or start a new chat, then hold the side button and talk. Your words go to the agent as its next prompt.
- The reply streams in under your words, each tool call a line. When the agent asks before it acts, tap one of its options, such as allow or reject. While it works, stop ends the turn.
- The wheel scrolls the conversation. The folder key opens the session's folder on the computer to read its files, without changing anything.
- The bridge row, under the cards, lists the computers you paired. One is in use at a time.
