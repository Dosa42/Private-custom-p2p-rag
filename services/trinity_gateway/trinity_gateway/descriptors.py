"""OpenAI tool-descriptor extensions at the HTTP serialization boundary.

MCP Python 2.2's version-specific wire models discard unknown top-level fields.
The SDK preserves _meta. Mirror only the explicitly registered securitySchemes
into its OpenAI top-level extension after SDK serialization, without altering
authorization, tool execution, errors, or other protocol response types.
"""

import json


class OpenAIDescriptorExtensions:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        response_start = None
        chunks = []

        async def extend(message):
            nonlocal response_start
            if message["type"] == "http.response.start":
                content_type = dict(message.get("headers", [])).get(b"content-type", b"")
                if content_type.startswith(b"application/json"):
                    response_start = message
                    return
            if response_start is not None and message["type"] == "http.response.body":
                chunks.append(message.get("body", b""))
                if message.get("more_body", False):
                    return
                body = b"".join(chunks)
                try:
                    payload = json.loads(body)
                    result = payload.get("result", {}) if isinstance(payload, dict) else {}
                    tools = result.get("tools") if isinstance(result, dict) else None
                    changed = False
                    if isinstance(tools, list):
                        for tool in tools:
                            if not isinstance(tool, dict):
                                continue
                            metadata = tool.get("_meta")
                            schemes = metadata.get("securitySchemes") if isinstance(metadata, dict) else None
                            if schemes is not None:
                                tool["securitySchemes"] = schemes
                                changed = True
                    if changed:
                        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                except (UnicodeError, ValueError):
                    # Preserve the original SDK response if it isn't JSON.
                    pass
                headers = [(key, value) for key, value in response_start.get("headers", []) if key.lower() != b"content-length"]
                headers.append((b"content-length", str(len(body)).encode("ascii")))
                await send({**response_start, "headers": headers})
                await send({"type": "http.response.body", "body": body})
                response_start = None
                return
            await send(message)

        await self.app(scope, receive, extend)
