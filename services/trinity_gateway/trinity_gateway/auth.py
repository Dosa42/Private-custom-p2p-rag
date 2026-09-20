"""OAuth authorization server for this gateway's own MCP resource.

This does not exchange or repurpose OpenAI/ChatGPT session tokens. Public OAuth
clients register exact redirect URIs, then use authorization code + S256 PKCE.
"""

from __future__ import annotations

import base64
import hashlib
from html import escape
import json
import re
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from starlette.routing import Route

from .store import GrantError, Store, TRANSACTION_SECONDS


NO_STORE = {"Cache-Control": "no-store", "Pragma": "no-cache"}
_VERIFIER = re.compile(r"[A-Za-z0-9._~-]{43,128}\Z")
_CHALLENGE = re.compile(r"[A-Za-z0-9_-]{43}\Z")
_LOOPBACK = {"127.0.0.1", "::1", "localhost"}
_BODY_LIMIT = 16 * 1024


class AuthorizationServer:
    def __init__(self, store: Store, public_url: str, allowed_scopes: list[str] | set[str] | tuple[str, ...],
                 allow_loopback: bool = False) -> None:
        self.store = store
        self.public_url = public_url.rstrip("/")
        self.allow_loopback = allow_loopback
        if not self._valid_redirect(self.public_url):
            raise ValueError("public_url must be HTTPS (or an explicitly allowed HTTP loopback URL)")
        split = urlsplit(self.public_url)
        if split.query or split.path:
            raise ValueError("public_url must be an origin without a path or query")
        self.resource = self.public_url + "/mcp"
        self.allowed_scopes = tuple(sorted(set(allowed_scopes)))
        if not self.allowed_scopes or any(not x or any(c.isspace() for c in x) for x in self.allowed_scopes):
            raise ValueError("At least one non-empty OAuth scope is required")
        self.cookie_name = "__Host-trinity_oauth" if split.scheme == "https" else "trinity_oauth"
        self.secure_cookie = split.scheme == "https"
        self.routes = [
            Route("/.well-known/oauth-authorization-server", self.metadata, methods=["GET"]),
            Route("/oauth/register", self.register, methods=["POST"]),
            Route("/oauth/authorize", self.authorize, methods=["GET", "POST"]),
            Route("/oauth/token", self.token, methods=["POST"]),
            Route("/oauth/revoke", self.revoke, methods=["POST"]),
        ]

    def _valid_redirect(self, uri: str) -> bool:
        try:
            parts = urlsplit(uri)
            _ = parts.port
            return bool(
                parts.hostname and parts.username is None and parts.password is None and not parts.fragment
                and not any(ord(c) <= 32 or ord(c) == 127 for c in uri)
                and "\\" not in uri and "*" not in uri
                and (parts.scheme == "https" or (
                    self.allow_loopback and parts.scheme == "http" and parts.hostname in _LOOPBACK
                ))
            )
        except (ValueError, TypeError):
            return False

    @staticmethod
    def _error(error: str, description: str, status: int = 400) -> JSONResponse:
        return JSONResponse({"error": error, "error_description": description}, status_code=status, headers=NO_STORE)

    @staticmethod
    def _parameters(pairs: list[tuple[str, str]]) -> dict[str, str]:
        values: dict[str, str] = {}
        for key, value in pairs:
            if key in values:
                raise GrantError("invalid_request", f"Repeated parameter: {key}")
            if len(key) > 128 or len(value) > 4096:
                raise GrantError("invalid_request", "Parameter exceeds the supported size")
            values[key] = value
        return values

    async def _body(self, request: Request) -> bytes:
        result = bytearray()
        async for chunk in request.stream():
            result.extend(chunk)
            if len(result) > _BODY_LIMIT:
                raise GrantError("invalid_request", "Request body exceeds 16 KiB")
        return bytes(result)

    async def _form(self, request: Request) -> dict[str, str]:
        content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/x-www-form-urlencoded":
            raise GrantError("invalid_request", "Expected application/x-www-form-urlencoded")
        try:
            return self._parameters(parse_qsl((await self._body(request)).decode("utf-8"),
                                              keep_blank_values=True, max_num_fields=32,
                                              encoding="utf-8", errors="strict"))
        except (ValueError, UnicodeError) as error:
            if isinstance(error, GrantError):
                raise
            raise GrantError("invalid_request", "Malformed form parameters") from error

    def _scopes(self, value: str | None, permitted: str | None = None) -> str:
        requested = (self.allowed_scopes if permitted is None else permitted.split()) if value is None else value.split()
        permitted_set = set(self.allowed_scopes if permitted is None else permitted.split())
        if not requested or not set(requested).issubset(permitted_set):
            raise GrantError("invalid_scope", "Requested scope is not available to this client")
        return " ".join(sorted(set(requested)))

    @staticmethod
    def _redirect(uri: str, **params: str) -> RedirectResponse:
        parts = urlsplit(uri)
        # Registered callback query parameters remain byte-for-byte unchanged.
        query = parts.query + ("&" if parts.query else "") + urlencode(params)
        return RedirectResponse(urlunsplit((parts.scheme, parts.netloc, parts.path, query, "")),
                                status_code=303, headers=NO_STORE)

    async def metadata(self, request: Request) -> JSONResponse:
        return JSONResponse({
            "issuer": self.public_url,
            "authorization_endpoint": self.public_url + "/oauth/authorize",
            "token_endpoint": self.public_url + "/oauth/token",
            "registration_endpoint": self.public_url + "/oauth/register",
            "revocation_endpoint": self.public_url + "/oauth/revoke",
            "scopes_supported": list(self.allowed_scopes),
            "response_types_supported": ["code"],
            "response_modes_supported": ["query"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "token_endpoint_auth_methods_supported": ["none"],
            "revocation_endpoint_auth_methods_supported": ["none"],
            "code_challenge_methods_supported": ["S256"],
            "authorization_response_iss_parameter_supported": True,
        }, headers=NO_STORE)

    async def register(self, request: Request) -> Response:
        try:
            if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
                raise GrantError("invalid_client_metadata", "Expected application/json")
            try:
                data = json.loads(await self._body(request))
            except (ValueError, UnicodeError) as error:
                raise GrantError("invalid_client_metadata", "Malformed JSON metadata") from error
            if not isinstance(data, dict):
                raise GrantError("invalid_client_metadata", "Client metadata must be an object")
            if data.get("token_endpoint_auth_method", "none") != "none":
                raise GrantError("invalid_client_metadata", "Only public clients using PKCE are supported")
            uris = data.get("redirect_uris")
            if (not isinstance(uris, list) or not 1 <= len(uris) <= 10
                    or any(not isinstance(uri, str) or len(uri) > 2048 or not self._valid_redirect(uri) for uri in uris)):
                raise GrantError("invalid_redirect_uri", "Register 1–10 exact HTTPS redirect URIs")
            if any(key in {"code", "state", "iss", "error", "error_description"}
                   for uri in uris for key, _ in parse_qsl(urlsplit(uri).query)):
                raise GrantError("invalid_redirect_uri", "Redirect URI query cannot contain OAuth response parameters")
            name = data.get("client_name", "MCP client")
            if not isinstance(name, str) or not 1 <= len(name) <= 128 or any(ord(c) < 32 for c in name):
                raise GrantError("invalid_client_metadata", "client_name must contain 1–128 visible characters")
            grants = data.get("grant_types", ["authorization_code", "refresh_token"])
            if (not isinstance(grants, list) or "authorization_code" not in grants
                    or any(not isinstance(x, str) or x not in {"authorization_code", "refresh_token"} for x in grants)):
                raise GrantError("invalid_client_metadata", "Unsupported grant_types")
            if data.get("response_types", ["code"]) != ["code"]:
                raise GrantError("invalid_client_metadata", "Only the code response type is supported")
            if "scope" in data and not isinstance(data["scope"], str):
                raise GrantError("invalid_client_metadata", "scope must be a string")
            scope = self._scopes(data.get("scope"))
            client = await run_in_threadpool(self.store.register_client, name, list(dict.fromkeys(uris)), scope)
            return JSONResponse(client, status_code=201, headers=NO_STORE)
        except GrantError as error:
            return self._error(error.error, error.description)

    async def authorize(self, request: Request) -> Response:
        if request.method == "POST":
            return await self._approve(request)
        redirect = None
        values = {}
        try:
            values = self._parameters(list(request.query_params.multi_items()))
            client = await run_in_threadpool(self.store.get_client, values.get("client_id", ""))
            if not client:
                raise GrantError("invalid_client", "Unknown client")
            candidate_redirect = values.get("redirect_uri", "")
            if candidate_redirect not in client["redirect_uris"]:
                raise GrantError("invalid_request", "redirect_uri must exactly match a registered callback")
            redirect = candidate_redirect
            if values.get("response_type") != "code":
                raise GrantError("unsupported_response_type", "Only the code response type is supported")
            if values.get("code_challenge_method") != "S256" or not _CHALLENGE.fullmatch(values.get("code_challenge", "")):
                raise GrantError("invalid_request", "A valid S256 PKCE challenge is required")
            if not values.get("state"):
                raise GrantError("invalid_request", "A non-empty state value is required")
            if values.get("resource") != self.resource:
                raise GrantError("invalid_target", "resource must identify this MCP endpoint")
            scope = self._scopes(values.get("scope"), client["scope"])
            tx = await run_in_threadpool(self.store.create_authorization_transaction,
                client_id=client["client_id"], redirect_uri=redirect, state=values["state"],
                scope=scope, resource=self.resource, challenge=values["code_challenge"],
            )
            html = """<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Authorize Trinity</title>
<style>body{font:16px system-ui;max-width:38rem;margin:3rem auto;padding:0 1rem;background:#111827;color:#eef2ff}
label{display:block;margin-top:1rem}input{box-sizing:border-box;display:block;width:100%;padding:.7rem;margin:.4rem 0}
button{padding:.8rem 1rem;margin:1rem .5rem 0 0}code{overflow-wrap:anywhere}small{color:#cbd5e1}</style>
<h1>Authorize Trinity</h1><p><strong>CLIENT</strong> requests access to your Trinity data.</p>
<p>Requested permissions: <code>SCOPES</code></p><p>Callback: <code>CALLBACK</code></p>
<form method="post" action="/oauth/authorize">
<input type="hidden" name="transaction" value="TRANSACTION"><input type="hidden" name="csrf" value="CSRF">
<label>Username<input name="username" autocomplete="username" maxlength="128"></label>
<label>Password<input name="password" type="password" autocomplete="current-password" maxlength="1024"></label>
<button name="decision" value="approve" type="submit">Sign in and allow</button>
<button name="decision" value="deny" type="submit">Deny</button></form>
<p><small>Use the account provisioned by your Trinity gateway administrator.</small></p></html>"""
            # Replace only explicit template slots; all reflected fields are escaped.
            substitutions = {"CLIENT": client["client_name"], "SCOPES": scope, "CALLBACK": redirect,
                             "TRANSACTION": tx["transaction"], "CSRF": tx["csrf"]}
            html = re.sub(r"\b(CLIENT|SCOPES|CALLBACK|TRANSACTION|CSRF)\b",
                          lambda match: escape(substitutions[match.group()], quote=True), html)
            response = HTMLResponse(html, headers={**NO_STORE,
                "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
                "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY"})
            response.set_cookie(self.cookie_name, tx["browser"], max_age=TRANSACTION_SECONDS,
                                httponly=True, secure=self.secure_cookie, samesite="lax", path="/")
            return response
        except GrantError as error:
            if redirect is not None:
                params = {"error": error.error, "error_description": error.description}
                params["iss"] = self.public_url
                if values.get("state"):
                    params["state"] = values["state"]
                return self._redirect(redirect, **params)
            return self._error(error.error, error.description)

    async def _approve(self, request: Request) -> Response:
        try:
            origin = request.headers.get("origin")
            if origin is not None and origin != self.public_url:
                raise GrantError("invalid_request", "Authorization form origin does not match this server")
            values = await self._form(request)
            if values.get("decision") not in {"approve", "deny"}:
                raise GrantError("invalid_request", "Explicit consent or denial is required")
            password = values.get("password", "")
            if len(password.encode("utf-8")) > 1024:
                raise GrantError("invalid_request", "Password exceeds the supported size")
            result = await run_in_threadpool(self.store.complete_authorization,
                transaction=values.get("transaction", ""), csrf=values.get("csrf", ""),
                browser=request.cookies.get(self.cookie_name, ""), username=values.get("username", ""),
                password=password, approve=values["decision"] == "approve",
            )
            redirect_uri = result.pop("redirect_uri")
            response = self._redirect(redirect_uri, iss=self.public_url, **result)
            response.delete_cookie(self.cookie_name, path="/", secure=self.secure_cookie, httponly=True, samesite="lax")
            return response
        except GrantError as error:
            return self._error(error.error, error.description)

    async def token(self, request: Request) -> Response:
        try:
            if request.headers.get("authorization"):
                raise GrantError("invalid_client", "Client authentication must use the registered public client_id")
            values = await self._form(request)
            client_id = values.get("client_id", "")
            if not await run_in_threadpool(self.store.get_client, client_id):
                raise GrantError("invalid_client", "Unknown client")
            if values.get("resource") != self.resource:
                raise GrantError("invalid_target", "resource must identify this MCP endpoint")
            if values.get("grant_type") == "authorization_code":
                verifier = values.get("code_verifier", "")
                if not _VERIFIER.fullmatch(verifier):
                    raise GrantError("invalid_grant", "A valid PKCE code_verifier is required")
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode("ascii")
                result = await run_in_threadpool(self.store.exchange_code,
                                                  code=values.get("code", ""), client_id=client_id,
                                                  redirect_uri=values.get("redirect_uri", ""),
                                                  challenge=challenge, resource=self.resource)
            elif values.get("grant_type") == "refresh_token":
                scope = self._scopes(values["scope"]) if "scope" in values else None
                result = await run_in_threadpool(self.store.refresh,
                                                 token=values.get("refresh_token", ""), client_id=client_id,
                                                 resource=self.resource, scope=scope)
            else:
                raise GrantError("unsupported_grant_type", "Use authorization_code or refresh_token")
            return JSONResponse(result, headers=NO_STORE)
        except GrantError as error:
            return self._error(error.error, error.description)

    async def revoke(self, request: Request) -> Response:
        try:
            if request.headers.get("authorization"):
                raise GrantError("invalid_client", "Client authentication must use the registered public client_id")
            values = await self._form(request)
            if not await run_in_threadpool(self.store.get_client, values.get("client_id", "")):
                raise GrantError("invalid_client", "Unknown client")
            if not values.get("token"):
                raise GrantError("invalid_request", "token is required")
            await run_in_threadpool(self.store.revoke, values["token"], values["client_id"])
            return Response(status_code=200, headers=NO_STORE)
        except GrantError as error:
            return self._error(error.error, error.description)
