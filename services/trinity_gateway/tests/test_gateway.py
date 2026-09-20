"""Real HTTP + SDK + WebSocket tests with an explicit protocol test device.

The test device is a fixture for gateway transport verification, not proof that an
Android handset is deployed or that its real P2P data is reachable.
"""

import asyncio
import base64
import hashlib
import json
import socket
from contextlib import asynccontextmanager
from urllib.parse import parse_qs, urlsplit

import httpx
import httpx2
import pytest
import uvicorn
import websockets
from mcp import Client
from mcp.client.auth import OAuthClientProvider, AuthorizationCodeResult
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.auth import OAuthClientMetadata
from starlette.testclient import TestClient

from trinity_gateway.server import create_app


@pytest.fixture
def app(tmp_path):
    return create_app("http://127.0.0.1:8000", str(tmp_path / "gateway.sqlite3"), allow_loopback=True)


def test_http_challenge_and_discovery(app):
    with TestClient(app, base_url="http://127.0.0.1:8000") as client:
        response = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        assert response.status_code == 401
        assert 'resource_metadata="http://127.0.0.1:8000/.well-known/oauth-protected-resource/mcp"' in response.headers["www-authenticate"]
        metadata = client.get("/.well-known/oauth-protected-resource/mcp").json()
        assert metadata["resource"] == "http://127.0.0.1:8000/mcp"
        assert metadata["scopes_supported"] == ["trinity:read", "trinity:write", "trinity:sync"]
        authorization = client.get("/.well-known/oauth-authorization-server").json()
        assert authorization["issuer"] == "http://127.0.0.1:8000"
        assert authorization["code_challenge_methods_supported"] == ["S256"]


@asynccontextmanager
async def running_gateway(tmp_path):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    app = create_app(origin, str(tmp_path / "live.sqlite3"), allow_loopback=True, device_timeout=0.25)
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error"))
    task = asyncio.create_task(server.serve(sockets=[sock]))
    for _ in range(100):
        if server.started:
            break
        await asyncio.sleep(0.01)
    assert server.started
    try:
        yield app, origin
    finally:
        server.should_exit = True
        await asyncio.wait_for(task, 5)
        sock.close()


async def user_token(origin, username, password, scopes):
    """Exercise the real registration, browser form, code, and PKCE exchange."""
    import re
    import html

    verifier = "s" * 64
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    async with httpx.AsyncClient(base_url=origin, follow_redirects=False, trust_env=False) as client:
        registration = await client.post("/oauth/register", json={
            "client_name": "Gateway integration test", "redirect_uris": ["http://127.0.0.1:19701/callback"],
            "token_endpoint_auth_method": "none", "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
        })
        assert registration.status_code == 201, registration.text
        client_id = registration.json()["client_id"]
        params = {"client_id": client_id, "redirect_uri": "http://127.0.0.1:19701/callback",
                  "response_type": "code", "scope": scopes, "state": "test-state",
                  "resource": origin + "/mcp", "code_challenge": challenge, "code_challenge_method": "S256"}
        form = await client.get("/oauth/authorize", params=params)
        assert form.status_code == 200, form.text
        fields = dict((html.unescape(name), html.unescape(value)) for name, value in
                      re.findall(r'<input[^>]+name="([^"]+)"[^>]+value="([^"]*)"', form.text))
        fields.update(username=username, password=password, decision="approve")
        authorized = await client.post("/oauth/authorize", data=fields)
        assert authorized.status_code in (302, 303), authorized.text
        callback = parse_qs(urlsplit(authorized.headers["location"]).query)
        assert callback["state"] == ["test-state"]
        token = await client.post("/oauth/token", data={"grant_type": "authorization_code", "client_id": client_id,
                                 "code": callback["code"][0], "code_verifier": verifier,
                                 "redirect_uri": params["redirect_uri"], "resource": origin + "/mcp"})
        assert token.status_code == 200, token.text
        return token.json()["access_token"]


@asynccontextmanager
async def sdk_client(origin, token):
    async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + token}, trust_env=False) as http:
        async with Client(streamable_http_client(origin + "/mcp", http_client=http)) as client:
            yield client


async def test_real_sdk_websocket_routing_and_scopes(tmp_path):
    async with running_gateway(tmp_path) as (app, origin):
        alice = app.state.store.create_user("alice", "alice-password-test")
        bob = app.state.store.create_user("bob", "bob-password-test")
        device_token = app.state.store.create_device_token(alice, "phone")
        alice_token = await user_token(origin, "alice", "alice-password-test", "trinity:read trinity:write trinity:sync")
        bob_token = await user_token(origin, "bob", "bob-password-test", "trinity:read")
        read_token = await user_token(origin, "alice", "alice-password-test", "trinity:read")
        async with httpx.AsyncClient(trust_env=False) as wire:
            listed = await wire.post(origin + "/mcp", headers={
                "Authorization": "Bearer " + alice_token,
                "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-11-25",
            }, json={"jsonrpc": "2.0", "id": "wire-list", "method": "tools/list", "params": {}})
            assert listed.status_code == 200, listed.text
            wire_tools = listed.json()["result"]["tools"]
            assert wire_tools[0]["securitySchemes"] == wire_tools[0]["_meta"]["securitySchemes"]
        async with websockets.connect(origin.replace("http://", "ws://") + "/device/ws", proxy=None,
                                       additional_headers={"Authorization": "Bearer " + device_token}) as ws:
            ready = json.loads(await ws.recv())
            assert ready == {"type": "ready", "device_id": "phone", "principal": alice}
            assert alice != bob
            async with sdk_client(origin, alice_token) as client:
                descriptors = await client.list_tools()
                assert {tool.name for tool in descriptors.tools} == {"trinity_query", "trinity_ingest", "trinity_swarm_status", "trinity_sync", "trinity_retrieve", "trinity_delete"}
                query_tool = next(tool for tool in descriptors.tools if tool.name == "trinity_query")
                assert query_tool.meta["securitySchemes"] == [{"type": "oauth2", "scopes": ["trinity:read"]}]
                pending = asyncio.create_task(client.call_tool("trinity_query", {"query": "stored document", "device_id": "phone"}))
                request = json.loads(await ws.recv())
                assert request["principal"] == alice
                assert request["method"] == "tools/call"
                assert request["params"] == {"name": "trinity_query", "arguments": {"query": "stored document", "k": 5, "min_kappa": 0.0}}
                await ws.send(json.dumps({"type": "response", "id": request["id"], "result": {
                    "content": [{"type": "text", "text": "fixture-record-42"}], "structuredContent": {"record_id": 42}, "isError": False}}))
                result = await pending
                assert result.content[0].text == "fixture-record-42"
                assert result.structured_content == {"record_id": 42}
                timeout = await client.call_tool("trinity_sync", {})
                assert timeout.is_error and "timed out" in timeout.content[0].text
                # Consume the unanswered request, proving timeout came from a real device socket.
                assert json.loads(await ws.recv())["params"]["name"] == "trinity_sync"
            async with sdk_client(origin, bob_token) as client:
                denied = await client.call_tool("trinity_query", {"query": "alice data", "device_id": "phone"})
                assert denied.is_error and "No enrolled device" in denied.content[0].text
            async with sdk_client(origin, read_token) as client:
                denied = await client.call_tool("trinity_delete", {"obj_id": "secret"})
                assert denied.is_error
                assert 'scope="trinity:write"' in denied.meta["mcp/www_authenticate"][0]
                assert "insufficient_scope" in denied.meta["mcp/www_authenticate"][0]
                assert 'error_description="Authorize trinity:write to use this tool"' in denied.meta["mcp/www_authenticate"][0]
        async with sdk_client(origin, alice_token) as client:
            disconnected = await client.call_tool("trinity_swarm_status", {})
            assert disconnected.is_error and "No enrolled device" in disconnected.content[0].text


async def test_official_oauth_provider_discovery_pkce_and_token_exchange(tmp_path):
    import html
    import re

    class TestTokenStorage:
        tokens = None
        client_info = None

        async def get_tokens(self): return self.tokens
        async def set_tokens(self, value): self.tokens = value
        async def get_client_info(self): return self.client_info
        async def set_client_info(self, value): self.client_info = value

    async with running_gateway(tmp_path) as (app, origin):
        subject = app.state.store.create_user("sdkuser", "sdk-provider-password")
        storage = TestTokenStorage()
        callback = {}
        approved_scope = []
        async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as browser:
            async def redirect_handler(url):
                approved_scope.extend(parse_qs(urlsplit(url).query).get("scope", [""])[0].split())
                page = await browser.get(url)
                assert page.status_code == 200, page.text
                fields = dict((html.unescape(name), html.unescape(value)) for name, value in
                              re.findall(r'<input[^>]+name="([^"]+)"[^>]+value="([^"]*)"', page.text))
                fields.update(username="sdkuser", password="sdk-provider-password", decision="approve")
                response = await browser.post(origin + "/oauth/authorize", data=fields)
                assert response.status_code == 303, response.text
                callback.update({key: values[0] for key, values in parse_qs(urlsplit(response.headers["location"]).query).items()})

            async def callback_handler():
                return AuthorizationCodeResult(code=callback["code"], state=callback.get("state"), iss=callback.get("iss"))

            provider = OAuthClientProvider(
                server_url=origin + "/mcp",
                client_metadata=OAuthClientMetadata(redirect_uris=["http://127.0.0.1:19702/callback"],
                                                    token_endpoint_auth_method="none", scope="trinity:read",
                                                    client_name="Official MCP SDK integration test"),
                storage=storage, redirect_handler=redirect_handler, callback_handler=callback_handler,
            )
            async with httpx2.AsyncClient(auth=provider, trust_env=False) as http:
                async with Client(streamable_http_client(origin + "/mcp", http_client=http)) as client:
                    tools = await client.list_tools()
                    assert len(tools.tools) == 6
                    assert storage.tokens is not None
                    stored = app.state.store.verify_access_token(storage.tokens.access_token)
                    assert stored["subject"] == subject
                    assert stored["resource"] == origin + "/mcp"
                    # The SDK requests the scopes advertised by protected resource
                    # metadata; only the scopes actually shown and approved are issued.
                    assert set(stored["scope"].split()) == set(approved_scope)
                    assert set(approved_scope) <= {"trinity:read", "trinity:write", "trinity:sync"}
                    assert callback["iss"] == origin


async def test_reconnect_queued_send_deadline_and_revocation(tmp_path):
    async with running_gateway(tmp_path) as (app, origin):
        subject = app.state.store.create_user("relayuser", "relay-password")
        credential = app.state.store.create_device_token(subject, "phone")
        token = await user_token(origin, "relayuser", "relay-password", "trinity:read")
        url = origin.replace("http://", "ws://") + "/device/ws"
        headers = {"Authorization": "Bearer " + credential}
        async with websockets.connect(url, additional_headers=headers, proxy=None) as old:
            assert json.loads(await old.recv())["type"] == "ready"
            async with websockets.connect(url, additional_headers=headers, proxy=None) as current:
                assert json.loads(await current.recv())["principal"] == subject
                with pytest.raises(websockets.exceptions.ConnectionClosed):
                    await old.recv()
                connection = app.state.relay.connections[(subject, "phone")]
                async with sdk_client(origin, token) as client:
                    # Hold the real connection's send lock to simulate a queued call.
                    # Its deadline must cover lock acquisition, before any WS frame.
                    await connection.send_lock.acquire()
                    try:
                        result = await client.call_tool("trinity_query", {"query": "queued"})
                        assert result.is_error and "timed out" in result.content[0].text
                    finally:
                        connection.send_lock.release()
                    await connection.send_lock.acquire()
                    queued = asyncio.create_task(client.call_tool("trinity_query", {"query": "must not dispatch"}))
                    try:
                        for _ in range(100):
                            if connection.pending:
                                break
                            await asyncio.sleep(0.001)
                        assert connection.pending
                        app.state.store.revoke_device(subject, "phone")
                    finally:
                        connection.send_lock.release()
                    result = await queued
                    assert result.is_error and "revoked" in result.content[0].text
                    with pytest.raises(websockets.exceptions.ConnectionClosed):
                        await current.recv()
