"""ACP over an agent's stdio: one JSON-RPC 2.0 message a line, both ways.

The bridge is the client: it starts the agent, calls it (initialize, session/new,
session/prompt, session/list) and answers what the agent asks of it
(session/request_permission). Notifications (session/update) go to a handler.
Specification: https://agentclientprotocol.com
"""

import asyncio
import json
import os
import signal
from collections.abc import Awaitable, Callable
from typing import Any

PROTOCOL = 1

Json = dict[str, Any]
Handler = Callable[[str, Json], Awaitable[Any]]


class AcpError(Exception):
    def __init__(self, error: Json):
        super().__init__(error.get("message", "agent error"))
        self.error = error


class Agent:
    """One running agent process and the JSON-RPC between it and the bridge.

    [on_notify] hears every notification, [on_request] answers every request from the agent
    and returns its result (an exception becomes the error reply). [log] writes each line
    both ways, for reading what an agent really sends.
    """

    def __init__(self, argv: list[str], cwd: str, env: dict[str, str] | None = None,
                 on_notify: Handler | None = None, on_request: Handler | None = None,
                 log: Callable[[str, str], None] | None = None):
        self.argv, self.cwd, self.env = argv, cwd, env
        self.on_notify, self.on_request, self.log = on_notify, on_request, log
        self.proc: asyncio.subprocess.Process | None = None
        self.info: Json = {}
        self._next = 0
        self._waiting: dict[int, asyncio.Future] = {}
        self._tasks: set[asyncio.Task] = set()

    async def start(self) -> Json:
        self.proc = await asyncio.create_subprocess_exec(
            *self.argv, cwd=self.cwd, env={**os.environ, **(self.env or {})},
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE, limit=64 * 1024 * 1024,
            start_new_session=True)  # its own process group: npx's children stop with it
        self._spawn(self._read())
        self._spawn(self._drain_stderr())
        self.info = await self.call("initialize", {
            "protocolVersion": PROTOCOL,
            "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False},
                                   "terminal": False},
            "clientInfo": {"name": "acp-bridge", "version": "0.1.0"},
        })
        return self.info

    @property
    def can_list(self) -> bool:
        return "list" in (self.info.get("agentCapabilities", {}).get("sessionCapabilities") or {})

    @property
    def can_load(self) -> bool:
        return bool(self.info.get("agentCapabilities", {}).get("loadSession"))

    async def call(self, method: str, params: Json) -> Any:
        self._next += 1
        id_ = self._next
        future = asyncio.get_running_loop().create_future()
        self._waiting[id_] = future
        await self._send({"jsonrpc": "2.0", "id": id_, "method": method, "params": params})
        return await future

    async def notify(self, method: str, params: Json) -> None:
        await self._send({"jsonrpc": "2.0", "method": method, "params": params})

    async def stop(self) -> None:
        if not self.proc or self.proc.returncode is not None:
            return
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(self.proc.pid, sig)
            except ProcessLookupError:
                return
            try:
                await asyncio.wait_for(self.proc.wait(), 5)
                return
            except TimeoutError:
                pass

    async def _send(self, message: Json) -> None:
        line = json.dumps(message, ensure_ascii=False)
        if self.log:
            self.log(">", line)
        self.proc.stdin.write(line.encode() + b"\n")
        await self.proc.stdin.drain()

    async def _read(self) -> None:
        while line := await self.proc.stdout.readline():
            text = line.decode(errors="replace").strip()
            if not text:
                continue
            if self.log:
                self.log("<", text)
            try:
                message = json.loads(text)
            except json.JSONDecodeError:
                continue  # an agent printing to stdout by mistake
            if "method" in message and "id" in message:
                self._spawn(self._answer(message))
            elif "method" in message:
                # In line, not spawned: updates keep their order, and the history a session/load
                # replays is all heard before its reply.
                if self.on_notify:
                    await self.on_notify(message["method"], message.get("params") or {})
            elif (future := self._waiting.pop(message.get("id"), None)) and not future.done():
                if "error" in message:
                    future.set_exception(AcpError(message["error"]))
                else:
                    future.set_result(message.get("result"))
        gone = AcpError({"message": f"agent exited ({await self.proc.wait()})"})
        for future in self._waiting.values():
            if not future.done():
                future.set_exception(gone)
        self._waiting.clear()

    async def _answer(self, message: Json) -> None:
        reply: Json = {"jsonrpc": "2.0", "id": message["id"]}
        try:
            if not self.on_request:
                raise AcpError({"code": -32601, "message": "not offered"})
            reply["result"] = await self.on_request(message["method"], message.get("params") or {})
        except AcpError as e:
            reply["error"] = e.error
        except Exception as e:
            reply["error"] = {"code": -32603, "message": str(e)}
        await self._send(reply)

    async def _drain_stderr(self) -> None:
        while line := await self.proc.stderr.readline():
            if self.log:
                self.log("!", line.decode(errors="replace").rstrip())

    def _spawn(self, coro) -> None:
        task = asyncio.ensure_future(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
