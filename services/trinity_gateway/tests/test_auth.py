"""Exercise real HTTP authorization routes and persistent credential storage."""

import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
from html.parser import HTMLParser
import json
import sqlite3
from urllib.parse import parse_qs, urlsplit

import pytest
from starlette.applications import Starlette
from starlette.testclient import TestClient

from trinity_gateway.auth import AuthorizationServer
from trinity_gateway.store import GrantError, Store


ORIGIN = "https://gateway.example"
RESOURCE = ORIGIN + "/mcp"
CALLBACK = "https://client.example/oauth/callback?preserved=1"
SCOPES = ["trinity:read", "trinity:write", "trinity:sync"]
VERIFIER = "a" * 64
CHALLENGE = base64.urlsafe_b64encode(hashlib.sha256(VERIFIER.encode()).digest()).rstrip(b"=").decode()


class Inputs(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.values = {}
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "input" and attrs.get("type") == "hidden":
            self.values[attrs["name"]] = attrs["value"]


@pytest.fixture
def oauth(tmp_path):
    store = Store(tmp_path / "auth.sqlite3")
    subject = store.create_user("owner", "owner-password")
    auth = AuthorizationServer(store, ORIGIN, SCOPES)
    with TestClient(Starlette(routes=auth.routes), base_url=ORIGIN, follow_redirects=False) as client:
        registration = client.post("/oauth/register", json={
            "client_name": "Real test client", "redirect_uris": [CALLBACK],
            "token_endpoint_auth_method": "none",
        })
        assert registration.status_code == 201
        yield client, store, subject, registration.json()["client_id"]


def authorization_form(oauth, **overrides):
    client, _, _, client_id = oauth
    params = {"client_id": client_id, "redirect_uri": CALLBACK, "response_type": "code",
              "state": "state-bound-to-client", "resource": RESOURCE, "scope": "trinity:read trinity:write",
              "code_challenge_method": "S256", "code_challenge": CHALLENGE, **overrides}
    response = client.get("/oauth/authorize", params=params)
    assert response.status_code == 200, response.text
    assert "frame-ancestors 'none'" in response.headers["content-security-policy"]
    return Inputs(response.text).values


def authorize(oauth, **overrides):
    client, *_ = oauth
    form = authorization_form(oauth, **overrides)
    response = client.post("/oauth/authorize", data={**form, "username": "owner",
                                                   "password": "owner-password", "decision": "approve"})
    assert response.status_code == 303, response.text
    values = parse_qs(urlsplit(response.headers["location"]).query)
    assert values["state"] == ["state-bound-to-client"]
    assert values["preserved"] == ["1"]
    assert values["iss"] == [ORIGIN]
    return values["code"][0]


def exchange(oauth, code, **overrides):
    client, _, _, client_id = oauth
    return client.post("/oauth/token", data={"grant_type": "authorization_code", "code": code,
                                            "client_id": client_id, "redirect_uri": CALLBACK,
                                            "code_verifier": VERIFIER, "resource": RESOURCE, **overrides})


def refresh(oauth, token, **overrides):
    client, _, _, client_id = oauth
    return client.post("/oauth/token", data={"grant_type": "refresh_token", "refresh_token": token,
                                            "client_id": client_id, "resource": RESOURCE, **overrides})


def test_complete_flow_persists_user_identity_without_raw_credentials(oauth):
    _, store, subject, client_id = oauth
    code = authorize(oauth)
    response = exchange(oauth, code)
    assert response.status_code == 200, response.text
    token = response.json()
    assert token["token_type"] == "Bearer"
    assert response.headers["cache-control"] == "no-store"
    reopened = Store(store.db_path)
    identity = reopened.verify_access_token(token["access_token"])
    assert identity["subject"] == subject
    assert identity["client_id"] == client_id
    assert identity["resource"] == RESOURCE
    assert identity["scope"] == "trinity:read trinity:write"
    assert reopened.get_user("OWNER")["subject"] == subject
    with sqlite3.connect(store.db_path) as db:
        dump = "\n".join(db.iterdump())
    for raw in ("owner-password", code, token["access_token"], token["refresh_token"]):
        assert raw not in dump


@pytest.mark.parametrize("override", [
    {"code_verifier": "b" * 64}, {"redirect_uri": "https://attacker.example/callback"},
    {"resource": "https://different.example/mcp"}, {"client_id": "unknown-client"},
])
def test_code_binding_failures_do_not_consume_legitimate_grant(oauth, override):
    code = authorize(oauth)
    assert exchange(oauth, code, **override).status_code == 400
    assert exchange(oauth, code).status_code == 200


def test_code_is_single_use_and_replay_revokes_issued_family(oauth):
    _, store, *_ = oauth
    code = authorize(oauth)
    tokens = exchange(oauth, code).json()
    assert exchange(oauth, code).json()["error"] == "invalid_grant"
    assert store.verify_access_token(tokens["access_token"]) is None
    assert refresh(oauth, tokens["refresh_token"]).status_code == 400


def test_refresh_rotation_reuse_revokes_entire_family(oauth):
    _, store, *_ = oauth
    initial = exchange(oauth, authorize(oauth)).json()
    rotated = refresh(oauth, initial["refresh_token"])
    assert rotated.status_code == 200
    current = rotated.json()
    assert current["refresh_token"] != initial["refresh_token"]
    assert store.verify_access_token(current["access_token"])
    assert refresh(oauth, initial["refresh_token"]).json()["error"] == "invalid_grant"
    assert store.verify_access_token(initial["access_token"]) is None
    assert store.verify_access_token(current["access_token"]) is None
    assert refresh(oauth, current["refresh_token"]).status_code == 400


def test_refresh_scope_can_only_narrow(oauth):
    tokens = exchange(oauth, authorize(oauth, scope="trinity:read")).json()
    response = refresh(oauth, tokens["refresh_token"], scope="trinity:read trinity:write")
    assert response.status_code == 400
    assert response.json()["error"] == "invalid_scope"
    assert refresh(oauth, tokens["refresh_token"], scope="trinity:read").status_code == 200


def test_refresh_rotation_is_atomic(oauth):
    _, store, _, client_id = oauth
    initial = exchange(oauth, authorize(oauth)).json()

    def attempt():
        try:
            return store.refresh(token=initial["refresh_token"], client_id=client_id,
                                 resource=RESOURCE, scope=None)
        except GrantError as error:
            return error.error

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(lambda _: attempt(), range(2)))
    assert sum(isinstance(result, dict) for result in results) == 1
    assert results.count("invalid_grant") == 1
    winner = next(result for result in results if isinstance(result, dict))
    assert store.verify_access_token(winner["access_token"]) is None


def test_csrf_is_bound_to_browser_and_transaction(oauth):
    client, *_ = oauth
    form = authorization_form(oauth)
    data = {**form, "username": "owner", "password": "owner-password", "decision": "approve"}
    assert client.post("/oauth/authorize", data={**data, "csrf": "wrong"}).status_code == 400
    assert client.post("/oauth/authorize", data=data, headers={"origin": "https://evil.example"}).status_code == 400
    cookies = client.cookies
    client.cookies = {}
    assert client.post("/oauth/authorize", data=data).status_code == 400
    client.cookies = cookies
    result = client.post("/oauth/authorize", data={**data, "redirect_uri": "https://evil.example"})
    assert result.status_code == 303
    assert result.headers["location"].startswith(CALLBACK + "&")
    assert client.post("/oauth/authorize", data=data).status_code == 400


def test_login_denial_and_wrong_password_never_issue_code(oauth):
    client, *_ = oauth
    form = authorization_form(oauth)
    response = client.post("/oauth/authorize", data={**form, "username": "owner",
                                                   "password": "wrong", "decision": "approve"})
    assert response.status_code == 400
    assert response.json()["error"] == "access_denied"
    denied = client.post("/oauth/authorize", data={**form, "decision": "deny"})
    assert denied.status_code == 303
    assert parse_qs(urlsplit(denied.headers["location"]).query)["error"] == ["access_denied"]


def test_expired_access_codes_and_refresh_tokens_are_rejected(oauth):
    _, store, *_ = oauth
    code = authorize(oauth)
    with sqlite3.connect(store.db_path) as db:
        db.execute("UPDATE authorization_codes SET expires_at=0")
    assert exchange(oauth, code).status_code == 400
    tokens = exchange(oauth, authorize(oauth)).json()
    with sqlite3.connect(store.db_path) as db:
        db.execute("UPDATE access_tokens SET expires_at=0")
        db.execute("UPDATE refresh_tokens SET expires_at=0")
    assert store.verify_access_token(tokens["access_token"]) is None
    assert refresh(oauth, tokens["refresh_token"]).status_code == 400


def test_revocation_is_client_bound_and_revokes_refresh_family(oauth):
    client, store, _, client_id = oauth
    tokens = exchange(oauth, authorize(oauth)).json()
    other = client.post("/oauth/register", json={"redirect_uris": ["https://other.example/callback"]}).json()
    assert client.post("/oauth/revoke", data={"client_id": other["client_id"],
                                             "token": tokens["refresh_token"]}).status_code == 200
    assert store.verify_access_token(tokens["access_token"])
    assert client.post("/oauth/revoke", data={"client_id": client_id,
                                             "token": tokens["refresh_token"]}).status_code == 200
    assert store.verify_access_token(tokens["access_token"]) is None
    assert refresh(oauth, tokens["refresh_token"]).status_code == 400


def test_device_tokens_are_separate_rotatable_and_revocable(oauth):
    _, store, subject, _ = oauth
    raw = store.create_device_token(subject, "android-1")
    assert store.verify_device_token(raw) == {"subject": subject, "device_id": "android-1"}
    assert store.verify_access_token(raw) is None
    next_raw = store.create_device_token(subject, "android-1")
    assert store.verify_device_token(raw) is None
    assert Store(store.db_path).verify_device_token(next_raw)
    store.revoke_device(subject, "android-1")
    assert store.verify_device_token(next_raw) is None


@pytest.mark.parametrize("metadata", [
    {"redirect_uris": ["http://client.example/callback"]},
    {"redirect_uris": ["https://user@client.example/callback"]},
    {"redirect_uris": ["https://@client.example/callback"]},
    {"redirect_uris": ["https://client.example/callback#fragment"]},
    {"redirect_uris": ["https://client.example/callback?iss=https://evil.example"]},
    {"redirect_uris": ["https://*.example/callback"]},
    {"redirect_uris": ["http://127.0.0.1:1455/callback"]},
    {"token_endpoint_auth_method": "client_secret_basic"},
    {"grant_types": ["authorization_code", {}]},
    {"response_types": ["token"]}, {"scope": ["trinity:read"]},
    {"scope": "trinity:admin"}, {"redirect_uris": []},
])
def test_bad_client_metadata_is_rejected_cleanly(oauth, metadata):
    client, *_ = oauth
    response = client.post("/oauth/register", json={"redirect_uris": [CALLBACK], **metadata})
    assert response.status_code == 400
    assert "error" in response.json()


def test_metadata_and_explicit_development_loopback_mode(tmp_path):
    store = Store(tmp_path / "auth.sqlite3")
    with pytest.raises(ValueError):
        AuthorizationServer(store, "http://127.0.0.1:8080", SCOPES)
    auth = AuthorizationServer(store, "http://127.0.0.1:8080", SCOPES, allow_loopback=True)
    with TestClient(Starlette(routes=auth.routes), base_url="http://127.0.0.1:8080") as client:
        metadata = client.get("/.well-known/oauth-authorization-server").json()
        assert metadata["code_challenge_methods_supported"] == ["S256"]
        assert metadata["token_endpoint_auth_methods_supported"] == ["none"]
        assert client.post("/oauth/register", json={"redirect_uris": ["http://localhost:1455/callback"]}).status_code == 201


def test_invalid_authorization_never_redirects_to_unregistered_uri(oauth):
    client, _, _, client_id = oauth
    response = client.get("/oauth/authorize", params={"client_id": client_id, "redirect_uri": "https://evil.example"})
    assert response.status_code == 400
    assert "location" not in response.headers
    response = client.get("/oauth/authorize", params={"client_id": client_id, "redirect_uri": CALLBACK,
                                                    "response_type": "token", "state": "original-state"})
    assert response.status_code == 303
    error = parse_qs(urlsplit(response.headers["location"]).query)
    assert error["error"] == ["unsupported_response_type"]
    assert error["state"] == ["original-state"]


def test_duplicate_and_oversized_parameters_fail_closed(oauth):
    client, *_ = oauth
    duplicate = client.post("/oauth/token", content="grant_type=authorization_code&grant_type=refresh_token",
                            headers={"content-type": "application/x-www-form-urlencoded"})
    assert duplicate.status_code == 400
    oversized = client.post("/oauth/register", content=json.dumps({"client_name": "x" * 17000}),
                            headers={"content-type": "application/json"})
    assert oversized.status_code == 400
