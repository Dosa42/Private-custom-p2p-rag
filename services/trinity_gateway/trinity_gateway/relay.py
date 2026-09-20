"""A real outbound WebSocket relay, isolated by authenticated user and device."""

from __future__ import annotations

import asyncio
import secrets
from dataclasses import dataclass, field
from typing import Any

from mcp.types import CallToolResult
from starlette.websockets import WebSocket, WebSocketDisconnect

from .store import Store


class RelayError(Exception):
    """An explicit failure to execute a request on the enrolled device."""


@dataclass(eq=False)
class Connection:
    websocket: WebSocket
    subject: str
    device_id: str
    credential: str = field(repr=False)
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def fail_pending(self, message: str) -> None:
        for future in self.pending.values():
            if not future.done():
                future.set_exception(RelayError(message))


class DeviceRelay:
    def __init__(self, store: Store, timeout: float = 45.0):
        self.store = store
        self.timeout = timeout
        self.connections: dict[tuple[str, str], Connection] = {}

    async def websocket(self, websocket: WebSocket) -> None:
        authorization = websocket.headers.get("authorization", "")
        raw = authorization[7:] if authorization.lower().startswith("bearer ") else ""
        identity = await asyncio.to_thread(self.store.verify_device_token, raw) if raw else None
        if not identity:
            await websocket.close(code=1008, reason="Invalid device credential")
            return
        await websocket.accept()
        connection = Connection(websocket, identity["subject"], identity["device_id"], raw)
        key = (connection.subject, connection.device_id)
        previous = self.connections.get(key)
        self.connections[key] = connection
        try:
            if previous:
                previous.fail_pending("Device connection replaced; request outcome is unknown")
                try:
                    await asyncio.wait_for(previous.websocket.close(code=1012, reason="Device reconnected"), 5)
                except (RuntimeError, OSError, TimeoutError):
                    pass
            await websocket.send_json({"type": "ready", "device_id": connection.device_id,
                                       "principal": connection.subject})
            while True:
                message = await websocket.receive_json()
                if not isinstance(message, dict) or message.get("type") != "response":
                    raise RelayError("Device sent an invalid response envelope")
                request_id = message.get("id")
                future = connection.pending.get(request_id) if isinstance(request_id, str) else None
                if future is None or future.done():
                    # A response can arrive after a timed-out or cancelled caller.
                    continue
                if "error" in message:
                    error = message["error"]
                    detail = error.get("message", "Device execution failed") if isinstance(error, dict) else str(error)
                    future.set_exception(RelayError(detail))
                else:
                    try:
                        if not isinstance(message.get("result"), dict):
                            raise ValueError("result must be a CallToolResult object")
                        result = CallToolResult.model_validate(message["result"])
                        future.set_result(result)
                    except (ValueError, TypeError) as error:
                        future.set_exception(RelayError(f"Invalid device tool result: {error}"))
        except (WebSocketDisconnect, OSError, RuntimeError, ValueError, RelayError):
            pass
        finally:
            if self.connections.get(key) is connection:
                del self.connections[key]
            connection.fail_pending("Device disconnected; request outcome is unknown")
            try:
                await websocket.close()
            except (RuntimeError, OSError):
                pass

    async def call(self, subject: str, name: str, arguments: dict[str, Any],
                   device_id: str | None = None) -> CallToolResult:
        candidates = [connection for (owner, device), connection in self.connections.items()
                      if owner == subject and (device_id is None or device == device_id)]
        if not candidates:
            raise RelayError("No enrolled device is connected for this user and device selection")
        if len(candidates) > 1:
            raise RelayError("Multiple devices are connected; provide device_id: " +
                             ", ".join(sorted(connection.device_id for connection in candidates)))
        connection = candidates[0]
        request_id = secrets.token_urlsafe(24)
        future = asyncio.get_running_loop().create_future()
        connection.pending[request_id] = future
        try:
            try:
                # The deadline includes queued sends and socket backpressure,
                # not only the time spent awaiting the device's response.
                async with asyncio.timeout(self.timeout):
                    async with connection.send_lock:
                        if not await asyncio.to_thread(self.store.verify_device_token, connection.credential):
                            await connection.websocket.close(code=1008, reason="Device credential revoked")
                            raise RelayError("Device credential was revoked")
                        await connection.websocket.send_json({
                            "type": "request", "id": request_id, "principal": subject,
                            "method": "tools/call", "params": {"name": name, "arguments": arguments},
                        })
                    return await future
            except TimeoutError as error:
                raise RelayError("Device request timed out; operation outcome is unknown. "
                                 "Inspect device state before repeating a write.") from error
        except (OSError, RuntimeError, WebSocketDisconnect) as error:
            raise RelayError("Device connection failed; operation outcome is unknown") from error
        finally:
            connection.pending.pop(request_id, None)
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                # A disconnect may resolve this future while send itself fails.
                # The caller receives the explicit RelayError above in that case.
                future.exception()

    async def close(self) -> None:
        for connection in list(self.connections.values()):
            connection.fail_pending("Gateway is shutting down")
            await connection.websocket.close(code=1001)
        self.connections.clear()
