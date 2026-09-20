"""Official Python MCP SDK server plus OAuth and authenticated Android relay."""

from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager
from urllib.parse import urlsplit

from mcp.server import MCPServer
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken, TokenVerifier
from mcp.server.auth.settings import AuthSettings
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import AnyHttpUrl
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route, WebSocketRoute

from .auth import AuthorizationServer
from .descriptors import OpenAIDescriptorExtensions
from .relay import DeviceRelay, RelayError
from .store import Store

SCOPES = ["trinity:read", "trinity:write", "trinity:sync"]
TOOL_SCOPES = {"trinity_query": "trinity:read", "trinity_ingest": "trinity:write",
               "trinity_swarm_status": "trinity:read", "trinity_sync": "trinity:sync",
               "trinity_retrieve": "trinity:read", "trinity_delete": "trinity:write"}


class DatabaseTokenVerifier(TokenVerifier):
    def __init__(self, store: Store, issuer: str):
        self.store, self.issuer = store, issuer

    async def verify_token(self, token: str) -> AccessToken | None:
        record = await asyncio.to_thread(self.store.verify_access_token, token)
        if record is None:
            return None
        return AccessToken(token=token, client_id=record["client_id"],
                           scopes=record["scope"].split(), expires_at=record["expires_at"],
                           resource=record["resource"], subject=record["subject"],
                           claims={"iss": self.issuer})


def error_result(message: str, meta: dict | None = None) -> CallToolResult:
    return CallToolResult(content=[TextContent(type="text", text=message)], is_error=True, meta=meta)


def create_app(public_url: str, db_path: str, *, allow_loopback: bool = False,
               device_timeout: float = 45.0) -> Starlette:
    public_url = public_url.rstrip("/")
    parts = urlsplit(public_url)
    if (parts.path or parts.query or parts.fragment or parts.username or parts.password or not parts.hostname):
        raise ValueError("TRINITY_PUBLIC_URL must be an origin, for example https://trinity.example.com")
    if parts.scheme != "https" and not (allow_loopback and parts.scheme == "http" and
                                        parts.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("TRINITY_PUBLIC_URL requires HTTPS; explicit loopback mode is for local tests")
    store = Store(db_path)
    relay = DeviceRelay(store, timeout=device_timeout)
    authorization = AuthorizationServer(store, public_url, SCOPES, allow_loopback=allow_loopback)
    resource_url = public_url + "/mcp"
    metadata_url = public_url + "/.well-known/oauth-protected-resource/mcp"
    mcp = MCPServer(
        "Trinity Private P2P RAG", version="1.0.0",
        instructions="Tools execute on the authenticated user's enrolled Android device. "
                     "If more than one device is connected, supply device_id. "
                     "A disconnected device or failed operation is reported explicitly.",
        token_verifier=DatabaseTokenVerifier(store, public_url),
        auth=AuthSettings(issuer_url=AnyHttpUrl(public_url), resource_server_url=AnyHttpUrl(resource_url),
                          required_scopes=[], validate_token_resource=True),
    )

    async def dispatch(name: str, arguments: dict, device_id: str | None) -> CallToolResult:
        token = get_access_token()
        required = TOOL_SCOPES[name]
        if token is None or not token.subject or required not in token.scopes:
            error = "invalid_token" if token is None else "insufficient_scope"
            challenge = (f'Bearer error="{error}", error_description="Authorize {required} to use this tool", '
                         f'resource_metadata="{metadata_url}", scope="{required}"')
            return error_result("Sign in and authorize " + required + " to use this tool.",
                                {"mcp/www_authenticate": [challenge]})
        try:
            return await relay.call(token.subject, name, arguments, device_id)
        except RelayError as error:
            return error_result(str(error))

    def metadata(name):
        return {"securitySchemes": [{"type": "oauth2", "scopes": [TOOL_SCOPES[name]]}]}

    @mcp.tool(meta=metadata("trinity_query"), annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
    async def trinity_query(query: str, k: int = 5, min_kappa: float = 0,
                            device_id: str | None = None) -> CallToolResult:
        """Search the user's private on-device RAG index and peer cache."""
        if not query.strip() or k < 1 or not 0 <= min_kappa <= 1:
            return error_result("query must be nonempty, k positive, and min_kappa between 0 and 1")
        return await dispatch("trinity_query", {"query": query, "k": k, "min_kappa": min_kappa}, device_id)

    @mcp.tool(meta=metadata("trinity_ingest"), annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, open_world_hint=False))
    async def trinity_ingest(content: str, source: str = "mcp", device_id: str | None = None) -> CallToolResult:
        """Store and index text in the authenticated user's device workspace."""
        if not content.strip():
            return error_result("content must not be empty")
        return await dispatch("trinity_ingest", {"content": content, "source": source}, device_id)

    @mcp.tool(meta=metadata("trinity_swarm_status"), annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
    async def trinity_swarm_status(device_id: str | None = None) -> CallToolResult:
        """Read actual peer and storage status from the user's enrolled device."""
        return await dispatch("trinity_swarm_status", {}, device_id)

    @mcp.tool(meta=metadata("trinity_sync"), annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, open_world_hint=True))
    async def trinity_sync(device_id: str | None = None) -> CallToolResult:
        """Request peer synchronization on the enrolled device and report its actual status."""
        return await dispatch("trinity_sync", {}, device_id)

    @mcp.tool(meta=metadata("trinity_retrieve"), annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
    async def trinity_retrieve(obj_id: str, device_id: str | None = None) -> CallToolResult:
        """Retrieve an object owned by the authenticated user from their device."""
        return await dispatch("trinity_retrieve", {"obj_id": obj_id}, device_id)

    @mcp.tool(meta=metadata("trinity_delete"), annotations=ToolAnnotations(read_only_hint=False, destructive_hint=True, open_world_hint=False))
    async def trinity_delete(obj_id: str, device_id: str | None = None) -> CallToolResult:
        """Delete an object owned by the authenticated user from their device."""
        return await dispatch("trinity_delete", {"obj_id": obj_id}, device_id)

    async def protected_metadata(request):
        return JSONResponse({"resource": resource_url, "authorization_servers": [public_url],
                             "scopes_supported": SCOPES, "bearer_methods_supported": ["header"]})

    async def health(request):
        return JSONResponse({"status": "ok", "service": "trinity-gateway", "version": "1.0.0"})

    # The external Host is retained by the HTTPS proxy. Explicitly permit that origin.
    transport_security = TransportSecuritySettings(enable_dns_rebinding_protection=True,
                                                   allowed_hosts=[parts.netloc], allowed_origins=[public_url])
    mcp_app = mcp.streamable_http_app(stateless_http=True, json_response=True,
                                      transport_security=transport_security)

    @asynccontextmanager
    async def lifespan(app):
        async with mcp_app.router.lifespan_context(mcp_app):
            yield
            await relay.close()

    routes = [*authorization.routes,
              Route("/.well-known/oauth-protected-resource/mcp", protected_metadata),
              Route("/.well-known/oauth-protected-resource", protected_metadata),
              Route("/healthz", health), WebSocketRoute("/device/ws", relay.websocket),
              Mount("/", app=OpenAIDescriptorExtensions(mcp_app))]
    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.store, app.state.relay, app.state.mcp = store, relay, mcp
    return app


def app_factory() -> Starlette:
    return create_app(os.environ["TRINITY_PUBLIC_URL"],
                      os.environ.get("TRINITY_DATABASE", "/data/trinity.sqlite3"),
                      allow_loopback=os.environ.get("TRINITY_ALLOW_LOOPBACK") == "1",
                      device_timeout=float(os.environ.get("TRINITY_DEVICE_TIMEOUT", "45")))
