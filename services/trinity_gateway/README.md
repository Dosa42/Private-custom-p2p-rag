# Trinity OAuth + MCP gateway

This service exposes the Android app's real tools to an MCP client over HTTPS.
It uses the official **MCP Python SDK 2.2.0**. It is the resource server and hosts
Trinity's authorization server. An Android device opens an outbound authenticated
WebSocket, so ChatGPT does not need to connect to the phone's localhost address.

## Install and run

Python 3.12 is the verified runtime. From this directory:

```sh
python -m venv .venv
.venv/bin/pip install -r requirements.lock
.venv/bin/pip install --no-deps .
export TRINITY_PUBLIC_URL=https://trinity.example.com
export TRINITY_DATABASE=/data/trinity.sqlite3
.venv/bin/uvicorn trinity_gateway.server:app_factory --factory --host 0.0.0.0 --port 8000 --workers 1
```

Replace the example hostname with the actual deployed HTTPS origin. The reverse
proxy must forward `Host`, HTTP POST/GET and WebSocket upgrades. The public MCP
URL is `$TRINITY_PUBLIC_URL/mcp`. `TRINITY_DATABASE` must point into a persistent
volume. Run **one worker**: live device connections and pending requests belong
to its event loop. Multi-worker deployment needs a shared relay/message broker
and is not implemented.

## Enroll the actual account and Android device

Use the same database path as the server. Passwords are read from a hidden prompt
or `TRINITY_USER_PASSWORD`; they are not accepted as command-line arguments.

```sh
.venv/bin/python -m trinity_gateway create-user alice
.venv/bin/python -m trinity_gateway create-device alice phone-a32
```

The second command prints an enrollment JSON containing `device_id`, `principal`
and `device_token`. Enter the public gateway origin and this device credential in
the Android app's MCP relay settings. This is an Android device credential;
ChatGPT's OAuth access token is issued separately after account login and consent.
The device must remain connected to execute its tools. The gateway never invents
tool output when the device is offline.

Revoke a lost device's credentials with:

```sh
.venv/bin/python -m trinity_gateway revoke-device alice phone-a32
```

The next request to that connection detects revocation and closes it. Re-enroll
the device with `create-device` when a new credential is needed. Protect the
persistent database as account and authorization state; credentials are hashed at
rest, and passwords use scrypt.

## Connect an MCP host

Register the public MCP URL in the host's remote MCP/app configuration. Discovery
provides Trinity's OAuth issuer, registration, authorization and token endpoints.
The host runs authorization-code PKCE with S256. The user signs in with the
Trinity account created above and explicitly authorizes the requested scopes.
The resulting access token identifies that user and is audience-bound to this
gateway's `/mcp` resource. This flow does not use a ChatGPT/OpenAI token as a
credential for Trinity.

| Tool | Scope | Device arguments |
| --- | --- | --- |
| `trinity_query` | `trinity:read` | `query`, optional `k=5`, `min_kappa=0` |
| `trinity_swarm_status` | `trinity:read` | none |
| `trinity_retrieve` | `trinity:read` | `obj_id` |
| `trinity_ingest` | `trinity:write` | `content`, optional `source="mcp"` |
| `trinity_delete` | `trinity:write` | `obj_id` |
| `trinity_sync` | `trinity:sync` | none |

All tools accept an optional `device_id`. It is required when more than one
device belonging to the user is connected. It cannot select another user's
device and is not forwarded as a tool argument.

Missing/invalid access tokens receive HTTP 401 with `WWW-Authenticate` and a
protected-resource discovery URL before tool dispatch. A valid access token
lacking a tool's scope receives `isError: true` and
`_meta["mcp/www_authenticate"]` in the tool result. Tool descriptors contain
`securitySchemes` at top level and in `_meta`. Both paths refer to the same real
authorization server; there is no synthetic authorization-success response.

## Device protocol

`GET /device/ws` uses a WebSocket upgrade with an `Authorization: Bearer ...`
device credential. The gateway verifies it against enrolled devices and sends:

```json
{"type":"ready","device_id":"phone-a32","principal":"user-uuid"}
```

For a tool invocation it sends:

```json
{"type":"request","id":"correlation-id","principal":"user-uuid","method":"tools/call","params":{"name":"trinity_query","arguments":{"query":"example","k":5,"min_kappa":0}}}
```

The device executes the operation for that principal and sends the actual MCP
result, with the same `id`:

```json
{"type":"response","id":"correlation-id","result":{"content":[{"type":"text","text":"actual device output"}],"isError":false}}
```

Failure uses `isError: true`, or an `error` object with a `message`. Unknown or
late correlation IDs are discarded. Disconnects and request timeouts become
explicit failures. The gateway never automatically repeats a write after a
timeout, since the device may already have applied it. Timeout is configurable
through `TRINITY_DEVICE_TIMEOUT` (seconds, default 45).

## Verification

```sh
.venv/bin/pip install -r requirements-test.lock
.venv/bin/python -m pytest -q
```

Tests cover the real authorization endpoints, persisted single-use codes and
rotating token state, scope/resource enforcement, SDK transport, and real
localhost WebSocket routing. The WebSocket fixture is an explicit protocol test
device. These tests do not claim that a physical Android handset or ChatGPT's
hosted login screen was exercised. Deployment needs the operator's real domain,
TLS routing, account creation and device enrollment.

For local-only tests, `TRINITY_ALLOW_LOOPBACK=1` allows an HTTP loopback origin.
It does not permit an arbitrary public HTTP origin. Production uses HTTPS.
